package com.credresolve.smartdialer.safety;

import com.credresolve.smartdialer.dialing.PacingEngine;
import com.credresolve.smartdialer.service.Database;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * The only application service which inserts calls. Approval and consumption share one DB
 * transaction.
 */
@Service
public class SafetyController {
  private final Database db;
  private final PacingEngine pacing;

  public SafetyController(Database db, PacingEngine pacing) {
    this.db = db;
    this.pacing = pacing;
  }

  public Map<String, Object> tick(UUID campaign, String mode) {
    return db.tx(
        () -> {
          var c = db.lock(campaign);
          int available =
              db.count(
                  "select count(*) from agents where campaign_id=? and state='AVAILABLE' and current_call_id is null and last_heartbeat>now()-interval '30 seconds'",
                  campaign);
          int reserved =
              db.count(
                  "select count(*) from agents where campaign_id=? and state in ('RESERVED','DIALING') and last_heartbeat>now()-interval '30 seconds'",
                  campaign);
          int exposure =
              db.count(
                  "select count(*) from calls where campaign_id=? and state in ('RESERVED','INITIATED','RINGING','ANSWERED')",
                  campaign);
          int connected =
              db.count(
                  "select count(*) from calls where campaign_id=? and state='CONNECTED'", campaign);
          var stats =
              db.one(
                  "select coalesce(avg(extract(epoch from (answered_at-created_at))),3) setup, coalesce(avg(extract(epoch from (ended_at-connected_at))),90) talk from calls where campaign_id=? and answered_at is not null",
                  campaign);
          double p = ((Number) c.get("answer_rate")).doubleValue();
          var proposal =
              pacing.propose(
                  mode,
                  available,
                  exposure,
                  connected,
                  p,
                  ((Number) stats.get("setup")).doubleValue(),
                  ((Number) stats.get("talk")).doubleValue());
          var health = db.one("select * from provider_health where provider=?", c.get("provider"));
          boolean healthy =
              !(boolean) health.get("outage")
                  && (health.get("circuit_until") == null
                      || ((java.sql.Timestamp) health.get("circuit_until"))
                          .toInstant()
                          .isBefore(java.time.Instant.now()));
          // High abandonment or insufficient observations switches to deterministic progressive
          // behavior.
          int samples =
              db.count(
                  "select count(*) from calls where campaign_id=? and ended_at is not null",
                  campaign);
          int abandoned =
              db.count(
                  "select count(*) from calls where campaign_id=? and reason='NO_AGENT' and created_at>now()-interval '5 minutes'",
                  campaign);
          boolean fallback = samples < 20 || abandoned > 0;
          var limit =
              SafetyPolicy.decide(
                  mode,
                  proposal.requested(),
                  available,
                  available + reserved,
                  exposure,
                  exposure + connected,
                  ((Number) c.get("max_active")).intValue(),
                  p,
                  ((Number) c.get("risk_limit")).doubleValue(),
                  healthy,
                  fallback);
          if (!c.get("status").equals("RUNNING"))
            limit = new SafetyPolicy.Limit(0, "REJECT", 0, "Campaign stopped");
          var ids = new ArrayList<UUID>();
          boolean bind =
              mode.equals("PROGRESSIVE") || limit.action().equals("FALLBACK_TO_PROGRESSIVE");
          for (int i = 0; i < limit.approved(); i++) {
            var borrowers =
                db.sql.queryForList(
                    "select cb.borrower_id from campaign_borrowers cb where cb.campaign_id=? and cb.status='ELIGIBLE' and not exists(select 1 from calls x where x.borrower_id=cb.borrower_id and x.state not in ('COMPLETED','FAILED','CANCELLED')) order by cb.borrower_id limit 1 for update of cb skip locked",
                    campaign);
            if (borrowers.isEmpty()) break;
            UUID borrower = Database.uuid(borrowers.getFirst().get("borrower_id"));
            // Lock the global borrower too: memberships in different campaigns may race.
            if (db.sql
                .queryForList(
                    "select id from borrowers where id=? for update skip locked", borrower)
                .isEmpty()) break;
            if (db.count(
                    "select count(*) from calls where borrower_id=? and state not in ('COMPLETED','FAILED','CANCELLED')",
                    borrower)
                > 0) break;
            UUID agent = null;
            if (bind) {
              var agents =
                  db.sql.queryForList(
                      "select id from agents where campaign_id=? and state='AVAILABLE' and current_call_id is null and last_heartbeat>now()-interval '30 seconds' order by id limit 1 for update skip locked",
                      campaign);
              if (agents.isEmpty()) break;
              agent = Database.uuid(agents.getFirst().get("id"));
            }
            UUID call = UUID.randomUUID();
            String key = "call-" + call;
            db.sql.update(
                "insert into calls(id,campaign_id,borrower_id,agent_id,provider,state,idempotency_key) values(?,?,?,?,?,'RESERVED',?)",
                call,
                campaign,
                borrower,
                agent,
                c.get("provider"),
                key);
            if (agent != null)
              db.sql.update(
                  "update agents set state='RESERVED',current_call_id=?,reserved_at=now(),reservation_expiry=now()+interval '30 seconds',version=version+1 where id=?",
                  call,
                  agent);
            db.sql.update(
                "update campaign_borrowers set status='ALLOCATED' where campaign_id=? and borrower_id=?",
                campaign,
                borrower);
            db.sql.update(
                "insert into reservations(id,call_id,agent_id,borrower_id,expires_at) values(?,?,?,?,now()+interval '30 seconds')",
                UUID.randomUUID(),
                call,
                agent,
                borrower);
            db.sql.update(
                "insert into provider_dispatches(call_id,idempotency_key) values(?,?)", call, key);
            db.sql.update(
                "insert into outbox_events(id,call_id) values(?,?)", UUID.randomUUID(), call);
            ids.add(call);
          }
          UUID decision = UUID.randomUUID();
          String reason =
              limit.reason()
                  + (fallback && mode.equals("PREDICTIVE")
                      ? "; cold start or recent abandonment triggers fallback"
                      : "");
          db.sql.update(
              "insert into pacing_decisions(id,campaign_id,mode,requested,probability,explanation) values(?,?,?,?,?,?)",
              decision,
              campaign,
              mode,
              proposal.requested(),
              p,
              proposal.explanation());
          String action =
              ids.isEmpty() ? "REJECT" : ids.size() < limit.approved() ? "REDUCE" : limit.action();
          db.sql.update(
              "insert into safety_decisions(id,campaign_id,action,approved,capacity,exposure,risk,reason) values(?,?,?,?,?,?,?,?)",
              decision,
              campaign,
              action,
              ids.size(),
              available + reserved,
              exposure,
              limit.risk(),
              reason);
          return Map.ofEntries(
              Map.entry("id", decision),
              Map.entry("requestedCalls", proposal.requested()),
              Map.entry("approvedCalls", ids.size()),
              Map.entry("estimatedAnswerProbability", p),
              Map.entry("expectedConnectedCalls", (exposure + ids.size()) * p),
              Map.entry("agentCapacity", available + reserved),
              Map.entry("inFlightExposure", exposure),
              Map.entry("safetyMargin", 0.15),
              Map.entry("risk", limit.risk()),
              Map.entry("action", action),
              Map.entry("explanation", reason + ". " + proposal.explanation()),
              Map.entry("calls", ids));
        });
  }
}
