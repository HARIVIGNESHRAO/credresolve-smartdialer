package com.credresolve.smartdialer.provider;

import com.credresolve.smartdialer.service.Database;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MockProviders {
  private final Database db;

  public MockProviders(Database db) {
    this.db = db;
  }

  public TelecomProvider get(String name) {
    if (!Set.of("A", "B").contains(name)) throw new IllegalArgumentException("Unknown provider");
    return new DurableMock(name);
  }

  public static class AmbiguousTimeout extends RuntimeException {
    public AmbiguousTimeout() {
      super("Provider accepted request but response timed out; reconcile by idempotency key");
    }
  }

  private class DurableMock implements TelecomProvider {
    private final String name;

    DurableMock(String name) {
      this.name = name;
    }

    public ProviderHealth getHealth() {
      var h =
          db.one(
              "select *, circuit_until>now() as open from provider_health where provider=?", name);
      boolean open = Boolean.TRUE.equals(h.get("open"));
      return new ProviderHealth(
          !(boolean) h.get("outage") && !open,
          ((Number) h.get("latency_ms")).intValue(),
          ((Number) h.get("failure_rate")).doubleValue(),
          open ? "OPEN" : "CLOSED");
    }

    public ProviderCallStatus getCallStatus(String key) {
      if (!getHealth().healthy()) return new ProviderCallStatus(null, "UNKNOWN");
      var rows =
          db.sql.queryForList(
              "select *,now()>=finish_at finished,now()>=answer_at answered from mock_provider_calls where idempotency_key=? and provider=?",
              key,
              name);
      if (rows.isEmpty()) return new ProviderCallStatus(null, "NOT_FOUND");
      var c = rows.getFirst();
      return new ProviderCallStatus(
          c.get("id").toString(),
          (boolean) c.get("finished")
              ? ((boolean) c.get("will_answer") && !(boolean) c.get("fail")
                  ? "COMPLETED"
                  : "FAILED")
              : (boolean) c.get("answered")
                      && (boolean) c.get("will_answer")
                      && !(boolean) c.get("fail")
                  ? "ANSWERED"
                  : "RINGING");
    }

    public CallInitiationResult initiateCall(CallRequest request) {
      var old =
          db.sql.queryForList(
              "select id from mock_provider_calls where idempotency_key=?",
              request.idempotencyKey());
      if (!old.isEmpty()) return new CallInitiationResult(old.getFirst().get("id").toString());
      var h = getHealth();
      if (!h.healthy()) throw new IllegalStateException("Provider unavailable");
      var settings = db.one("select * from provider_health where provider=?", name);
      var random =
          new Random(
              ((Number) settings.get("seed")).longValue() ^ request.idempotencyKey().hashCode());
      boolean answered = random.nextDouble() < 0.5,
          fail = random.nextDouble() < h.failureRate(),
          timeout = name.equals("B") && random.nextDouble() < 0.2;
      String id = name + "-" + request.callId();
      // Independent transaction: provider may accept even if the caller crashes before persisting
      // the response.
      db.tx(
          () -> {
            db.sql.update(
                "insert into mock_provider_calls(id,provider,idempotency_key,call_id,answer_at,finish_at,will_answer,fail) values(?,?,?,?,now()+make_interval(secs=>?),now()+make_interval(secs=>?),?,?) on conflict(idempotency_key) do nothing",
                id,
                name,
                request.idempotencyKey(),
                request.callId(),
                2 + h.latencyMs() / 1000.0,
                answered ? 18.0 : 5.0,
                answered,
                fail);
            return null;
          });
      try {
        Thread.sleep(h.latencyMs());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AmbiguousTimeout();
      }
      if (timeout) throw new AmbiguousTimeout();
      return new CallInitiationResult(id);
    }
  }
}
