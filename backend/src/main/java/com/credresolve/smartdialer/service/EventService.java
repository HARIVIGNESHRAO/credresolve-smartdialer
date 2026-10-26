package com.credresolve.smartdialer.service;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EventService {
  private final Database db;

  public EventService(Database db) {
    this.db = db;
  }

  public Map<String, Object> process(String provider, String eventId, UUID callId, String type) {
    if (!Set.of("RINGING", "ANSWERED", "COMPLETED", "FAILED", "CANCELLED").contains(type))
      throw new IllegalArgumentException("Unsupported provider event");
    return db.tx(
        () -> {
          var initial = db.one("select campaign_id from calls where id=?", callId);
          UUID campaign = Database.uuid(initial.get("campaign_id"));
          db.lock(campaign);
          var call = db.one("select * from calls where id=? for update", callId);
          if (!provider.equals(call.get("provider")))
            throw new IllegalArgumentException("Provider does not own this call");
          int inserted =
              db.sql.update(
                  "insert into call_events(provider,event_id,call_id,event_type) values(?,?,?,?) on conflict do nothing",
                  provider,
                  eventId,
                  callId,
                  type);
          if (inserted == 0) return Map.of("outcome", "DUPLICATE", "state", call.get("state"));
          String old = call.get("state").toString(), next = type, outcome = "APPLIED";
          if (Database.terminal(old) || !StateMachines.call(old, type)) {
            outcome = "IGNORED";
          } else {
            UUID agent = Database.uuid(call.get("agent_id"));
            if (type.equals("ANSWERED")) {
              if (agent == null) {
                var a =
                    db.sql.queryForList(
                        "select id from agents where campaign_id=? and state='AVAILABLE' and current_call_id is null and last_heartbeat>now()-interval '30 seconds' order by id limit 1 for update skip locked",
                        campaign);
                if (!a.isEmpty()) {
                  agent = Database.uuid(a.getFirst().get("id"));
                  db.sql.update("update calls set agent_id=? where id=?", agent, callId);
                }
              }
              boolean live =
                  agent != null
                      && db.count(
                              "select count(*) from agents where id=? and state in ('AVAILABLE','RESERVED','DIALING') and last_heartbeat>now()-interval '30 seconds' and (current_call_id is null or current_call_id=?)",
                              agent,
                              callId)
                          > 0;
              db.sql.update(
                  "update calls set answered_at=coalesce(answered_at,now()) where id=?", callId);
              if (live) {
                next = "CONNECTED";
                db.sql.update(
                    "update calls set connected_at=coalesce(connected_at,now()) where id=?",
                    callId);
                db.sql.update(
                    "update agents set state='CONNECTED',current_call_id=?,reservation_expiry=null,version=version+1 where id=?",
                    callId,
                    agent);
              } else {
                next = "FAILED";
                db.sql.update("update calls set reason='NO_AGENT' where id=?", callId);
              }
            }
            if (type.equals("COMPLETED") && call.get("answered_at") == null) {
              // Completion is authoritative terminal evidence, but does not fabricate an
              // answer/connect timestamp.
              db.sql.update(
                  "update calls set reason=coalesce(reason,'COMPLETION_BEFORE_ANSWER') where id=?",
                  callId);
            }
            db.sql.update(
                "update calls set state=?,updated_at=now(),ended_at=case when ? then now() else ended_at end where id=?",
                next,
                Database.terminal(next),
                callId);
            if (Database.terminal(next)) {
              if (agent != null) {
                db.sql.update(
                    "update agents set state=case when state='OFFLINE' then 'OFFLINE' when state='CONNECTED' then 'WRAP_UP' else 'AVAILABLE' end,current_call_id=null,reserved_at=null,reservation_expiry=case when state='CONNECTED' then now()+interval '3 seconds' else null end,version=version+1 where id=? and current_call_id=?",
                    agent,
                    callId);
              }
              db.sql.update(
                  "update reservations set released_at=now() where call_id=? and released_at is null",
                  callId);
              db.sql.update(
                  "update campaign_borrowers set status='DONE' where campaign_id=? and borrower_id=?",
                  campaign,
                  call.get("borrower_id"));
              db.sql.update(
                  "update outbox_events set status='DONE',lease_until=null where call_id=?",
                  callId);
              db.sql.update(
                  "update provider_dispatches set status='DONE',lease_until=null where call_id=?",
                  callId);
              db.sql.update(
                  "update campaigns set answer_rate=0.85*answer_rate+0.15*? where id=?",
                  (type.equals("COMPLETED") && call.get("connected_at") != null
                          || type.equals("ANSWERED"))
                      ? 1.0
                      : 0.0,
                  campaign);
            }
          }
          db.sql.update(
              "update call_events set outcome=? where provider=? and event_id=?",
              outcome,
              provider,
              eventId);
          return Map.of("outcome", outcome, "state", outcome.equals("IGNORED") ? old : next);
        });
  }
}
