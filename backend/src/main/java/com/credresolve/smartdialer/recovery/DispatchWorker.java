package com.credresolve.smartdialer.recovery;

import com.credresolve.smartdialer.provider.*;
import com.credresolve.smartdialer.service.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DispatchWorker {
  private final Database db;
  private final MockProviders providers;
  private final EventService events;

  public DispatchWorker(Database db, MockProviders providers, EventService events) {
    this.db = db;
    this.providers = providers;
    this.events = events;
  }

  public boolean dispatchOne() {
    var candidates =
        db.sql.queryForList(
            "select c.id,c.campaign_id from calls c join outbox_events o on o.call_id=c.id where o.status<>'DONE' and (o.lease_until is null or o.lease_until<now()) order by o.created_at limit 10");
    for (var candidate : candidates) {
      UUID id = Database.uuid(candidate.get("id")),
          campaign = Database.uuid(candidate.get("campaign_id"));
      var claimed =
          db.tx(
              () -> {
                var c = db.lock(campaign);
                var call = db.one("select * from calls where id=? for update", id);
                var rows =
                    db.sql.queryForList(
                        "select * from outbox_events where call_id=? and status<>'DONE' and (lease_until is null or lease_until<now()) for update skip locked",
                        id);
                if (rows.isEmpty()) return Map.<String, Object>of();
                if (Database.terminal(call.get("state").toString())) {
                  db.sql.update("update outbox_events set status='DONE' where call_id=?", id);
                  return Map.<String, Object>of();
                }
                boolean neverSent =
                    db.count(
                            "select count(*) from provider_dispatches where call_id=? and attempts=0",
                            id)
                        > 0;
                if (neverSent
                    && (!c.get("status").equals("RUNNING")
                        || call.get("agent_id") != null
                            && db.count(
                                    "select count(*) from agents where id=? and state='RESERVED' and last_heartbeat>now()-interval '30 seconds'",
                                    call.get("agent_id"))
                                == 0)) {
                  events.process(
                      call.get("provider").toString(), "cancel-before-send-" + id, id, "CANCELLED");
                  return Map.<String, Object>of();
                }
                db.sql.update(
                    "update outbox_events set status='CLAIMED',lease_until=now()+interval '10 seconds' where call_id=?",
                    id);
                db.sql.update(
                    "update provider_dispatches set status='UNCERTAIN',attempts=attempts+1,lease_until=now()+interval '10 seconds' where call_id=?",
                    id);
                return call;
              });
      if (claimed.isEmpty()) continue;
      String providerName = claimed.get("provider").toString(),
          key = claimed.get("idempotency_key").toString();
      TelecomProvider provider = providers.get(providerName);
      try {
        var status = provider.getCallStatus(key);
        if (status.status().equals("UNKNOWN"))
          throw new IllegalStateException("Provider reconciliation unavailable");
        String providerId = status.providerCallId();
        if (status.status().equals("NOT_FOUND")) {
          // A stopped campaign may only reconcile, never initiate a previously uncertain request.
          boolean running =
              db.one("select status from campaigns where id=?", campaign)
                  .get("status")
                  .equals("RUNNING");
          boolean agentLive =
              claimed.get("agent_id") == null
                  || db.count(
                          "select count(*) from agents where id=? and state in ('RESERVED','DIALING') and last_heartbeat>now()-interval '30 seconds'",
                          claimed.get("agent_id"))
                      > 0;
          if (!running || !agentLive) {
            events.process(providerName, "stopped-absent-" + id, id, "CANCELLED");
            return true;
          }
          providerId =
              provider.initiateCall(new TelecomProvider.CallRequest(id, key)).providerCallId();
        }
        final String externalId = providerId;
        db.tx(
            () -> {
              db.lock(campaign);
              db.sql.update(
                  "update calls set provider_call_id=?,state=case when state='RESERVED' then 'INITIATED' else state end,updated_at=now() where id=?",
                  externalId,
                  id);
              db.sql.update(
                  "update agents set state='DIALING',version=version+1 where current_call_id=? and state='RESERVED'",
                  id);
              db.sql.update(
                  "update provider_dispatches set status='ACKNOWLEDGED',lease_until=null where call_id=? and status<>'DONE'",
                  id);
              db.sql.update(
                  "update outbox_events set status='DONE',lease_until=null where call_id=?", id);
              return null;
            });
        if (Set.of("ANSWERED", "COMPLETED", "FAILED").contains(status.status()))
          events.process(
              providerName, "reconcile-" + id + "-" + status.status(), id, status.status());
        db.sql.update(
            "update provider_health set failures=0,circuit_until=null where provider=?",
            providerName);
      } catch (RuntimeException ex) {
        db.sql.update(
            "update provider_dispatches set last_error=? where call_id=?", ex.getMessage(), id);
        db.sql.update(
            "update provider_health set failures=failures+1,circuit_until=case when failures>=2 then now()+interval '15 seconds' else circuit_until end where provider=?",
            providerName);
      }
      return true;
    }
    return false;
  }

  public void pollProvider() {
    // Event IDs derive from durable provider IDs, so every scheduler can safely deliver the same
    // callback.
    for (var c :
        db.sql.queryForList(
            "select *,now()>=finish_at finished,now()>=answer_at answered from mock_provider_calls where not completed_sent order by created_at limit 200")) {
      String p = c.get("provider").toString();
      if (!providers.get(p).getHealth().healthy()) continue;
      UUID id = Database.uuid(c.get("call_id"));
      String external = c.get("id").toString();
      boolean finish = (boolean) c.get("finished"),
          answer =
              (boolean) c.get("answered")
                  && (boolean) c.get("will_answer")
                  && !(boolean) c.get("fail");
      boolean disorder = p.equals("B") && Math.floorMod(external.hashCode(), 5) == 0;
      if (finish && disorder) {
        events.process(
            p,
            external + "-complete",
            id,
            (boolean) c.get("will_answer") && !(boolean) c.get("fail") ? "COMPLETED" : "FAILED");
      }
      if (answer && !(boolean) c.get("answered_sent") && (!disorder || finish)) {
        events.process(p, external + "-answer", id, "ANSWERED");
        if (p.equals("B")) {
          events.process(p, external + "-answer", id, "ANSWERED");
          events.process(p, external + "-answer-duplicate", id, "ANSWERED");
        }
        db.sql.update("update mock_provider_calls set answered_sent=true where id=?", external);
      }
      if (finish) {
        events.process(
            p,
            external + "-complete",
            id,
            (boolean) c.get("will_answer") && !(boolean) c.get("fail") ? "COMPLETED" : "FAILED");
        if (disorder) events.process(p, external + "-late-ring", id, "RINGING");
        db.sql.update("update mock_provider_calls set completed_sent=true where id=?", external);
      } else events.process(p, external + "-ring", id, "RINGING");
    }
  }

  public void recoverAgents() {
    for (var c : db.sql.queryForList("select id from campaigns"))
      db.tx(
          () -> {
            db.lock(Database.uuid(c.get("id")));
            db.sql.update(
                "update agents set state='OFFLINE',version=version+1 where campaign_id=? and state<>'OFFLINE' and last_heartbeat<now()-interval '30 seconds'",
                c.get("id"));
            db.sql.update(
                "update agents set state='AVAILABLE',reservation_expiry=null,version=version+1 where campaign_id=? and state='WRAP_UP' and reservation_expiry<now()",
                c.get("id"));
            return null;
          });
    // Reservation expiry is a reconciliation trigger, never permission to redial or free a possibly
    // live call.
    for (var call :
        db.sql.queryForList(
            "select c.* from calls c join reservations r on r.call_id=c.id where r.released_at is null and r.expires_at<now() and c.state not in ('COMPLETED','FAILED','CANCELLED') limit 200")) {
      String p = call.get("provider").toString();
      var status = providers.get(p).getCallStatus(call.get("idempotency_key").toString());
      UUID id = Database.uuid(call.get("id"));
      if (Set.of("COMPLETED", "FAILED", "ANSWERED").contains(status.status()))
        events.process(p, "lease-reconcile-" + id + "-" + status.status(), id, status.status());
    }
  }
}
