package com.credresolve.smartdialer.simulation;

import com.credresolve.smartdialer.dialing.PacingEngine;
import com.credresolve.smartdialer.safety.SafetyPolicy;
import com.credresolve.smartdialer.service.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Simulator {
  public record Config(
      @NotNull @Pattern(regexp = "[ABCD]") String scenario,
      @Min(1) @Max(500) int agents,
      @Min(30) @Max(7200) int durationSeconds,
      @Min(0) @Max(30000) int providerLatencyMs,
      @DecimalMin("0") @DecimalMax("1") double failureRate,
      @Min(0) @Max(500) int dropouts,
      long seed) {}

  private final Database db;
  private final ObjectMapper json;
  private final PacingEngine pacing;

  public Simulator(Database db, ObjectMapper json, PacingEngine pacing) {
    this.db = db;
    this.json = json;
    this.pacing = pacing;
  }

  public Map<String, Object> run(Config c) {
    if (c.dropouts() > c.agents())
      throw new IllegalArgumentException("Dropouts cannot exceed agent count");
    UUID id = UUID.randomUUID();
    var result =
        Map.<String, Object>of(
            "id",
            id,
            "config",
            c,
            "progressive",
            simulate(c, "PROGRESSIVE"),
            "predictive",
            simulate(c, "PREDICTIVE"));
    try {
      db.tx(
          () -> {
            try {
              db.sql.update(
                  "insert into simulation_runs(id,config,result) values(?,?::jsonb,?::jsonb)",
                  id,
                  json.writeValueAsString(c),
                  json.writeValueAsString(result));
              for (String mode : List.of("progressive", "predictive"))
                db.sql.update(
                    "insert into simulation_metrics(run_id,mode,metrics) values(?,?,?::jsonb)",
                    id,
                    mode,
                    json.writeValueAsString(result.get(mode)));
              return null;
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
    } catch (RuntimeException e) {
      throw e;
    }
    return result;
  }

  private static final class Pending {
    int answerAt, finishAt, agent;
    boolean answer, fail, handled;

    Pending(int t, int setup, int talk, int a, boolean answer, boolean fail) {
      answerAt = t + setup;
      finishAt = answerAt + (answer && !fail ? talk : 0);
      agent = a;
      this.answer = answer;
      this.fail = fail;
    }
  }

  public Map<String, Object> simulate(Config c, String mode) {
    Random random = new Random(c.seed());
    int[] busy = new int[c.agents()];
    Arrays.fill(busy, -1);
    List<Pending> pending = new ArrayList<>();
    List<Map<String, Object>> series = new ArrayList<>();
    List<String> log = new ArrayList<>();
    int initiated = 0,
        answered = 0,
        connected = 0,
        failed = 0,
        abandoned = 0,
        approvals = 0,
        rejections = 0,
        fallbacks = 0,
        changes = 0,
        lastPacing = -1;
    long occupiedSeconds = 0, waiting = 0;
    double ewma = 0.5;
    int observations = 0;
    boolean recentAbandonment = false;
    double base =
        switch (c.scenario()) {
          case "A" -> 0.2;
          case "B" -> 0.5;
          case "C" -> 0.7;
          default -> 0.2;
        };
    int baseTalk =
        switch (c.scenario()) {
          case "A" -> 120;
          case "B" -> 90;
          case "C" -> 180;
          default -> 120;
        };
    for (int t = 0; t < c.durationSeconds(); t++) {
      int live = c.agents() - (t >= c.durationSeconds() / 2 ? c.dropouts() : 0);
      boolean outage =
          c.scenario().equals("D")
              && t >= c.durationSeconds() / 3
              && t < c.durationSeconds() / 3 + 15;
      if (t == c.durationSeconds() / 2 && c.dropouts() > 0)
        log.add(
            "t="
                + t
                + ": "
                + c.dropouts()
                + " agents disappeared; capacity recomputed before next approval");
      if (c.scenario().equals("D") && t == c.durationSeconds() / 3)
        log.add(
            "t="
                + t
                + ": provider outage; safety rejects new calls for 15 virtual seconds; existing calls retained");
      double rate = c.scenario().equals("D") && t > c.durationSeconds() / 2 ? 0.7 : base;
      int talk = c.scenario().equals("D") && t > c.durationSeconds() / 2 ? 180 : baseTalk;
      for (var iterator = pending.iterator(); iterator.hasNext(); ) {
        Pending call = iterator.next();
        if (!call.handled && t >= call.answerAt) {
          call.handled = true;
          observations++;
          ewma = 0.85 * ewma + 0.15 * (call.answer && !call.fail ? 1 : 0);
          if (call.answer && !call.fail) {
            answered++;
            int a = call.agent;
            if (a < 0) {
              for (int i = 0; i < live; i++)
                if (busy[i] <= t) {
                  a = i;
                  break;
                }
            }
            if (a < 0 || a >= live) {
              abandoned++;
              failed++;
              recentAbandonment = true;
              call.finishAt = t;
              if (call.agent >= 0) busy[call.agent] = t;
            } else {
              call.agent = a;
              busy[a] = call.finishAt;
              connected++;
              waiting += Math.max(0, t - call.answerAt); /* Immediate bridge; no answer queue. */
            }
          } else {
            failed++;
            if (call.agent >= 0) busy[call.agent] = t;
          }
        }
        if (call.handled && t >= call.finishAt) iterator.remove();
      }
      int free = 0, talking = 0, reserved = 0, exposure = 0;
      for (int i = 0; i < live; i++) if (busy[i] <= t) free++;
      for (Pending call : pending)
        if (!call.handled) {
          exposure++;
          if (call.agent >= 0 && call.agent < live) reserved++;
        } else if (call.answer && !call.fail && call.agent >= 0 && call.agent < live) talking++;
      occupiedSeconds += talking;
      var proposal =
          pacing.propose(
              mode, free, exposure, talking, ewma, 3 + c.providerLatencyMs() / 1000.0, talk);
      var decision =
          SafetyPolicy.decide(
              mode,
              proposal.requested(),
              free,
              free + reserved,
              exposure,
              pending.size(),
              c.agents() * 2,
              ewma,
              0.01,
              !outage,
              observations < 20 || recentAbandonment);
      int n = decision.approved();
      if (n > 0) approvals++;
      else rejections++;
      if (decision.action().equals("FALLBACK_TO_PROGRESSIVE")) fallbacks++;
      if (lastPacing != n) {
        changes++;
        lastPacing = n;
      }
      boolean bind =
          mode.equals("PROGRESSIVE") || decision.action().equals("FALLBACK_TO_PROGRESSIVE");
      for (int k = 0; k < n; k++) {
        int a = -1;
        if (bind) {
          for (int j = 0; j < live; j++)
            if (busy[j] <= t) {
              a = j;
              break;
            }
          if (a < 0) break;
        }
        int setup = Math.max(1, 3 + c.providerLatencyMs() / 1000),
            length = Math.max(1, (int) Math.round(talk * (0.7 + 0.6 * random.nextDouble())));
        boolean answer = random.nextDouble() < rate, fail = random.nextDouble() < c.failureRate();
        Pending call = new Pending(t, setup, length, a, answer, fail);
        pending.add(call);
        initiated++;
        if (a >= 0) busy[a] = call.finishAt;
      }
      if (t % 30 == 0)
        series.add(
            Map.of(
                "second",
                t,
                "initiated",
                initiated,
                "connected",
                connected,
                "active",
                pending.size(),
                "capacity",
                live,
                "approved",
                n,
                "answerEstimate",
                ewma));
    }
    long capacitySeconds =
        (long) c.agents() * c.durationSeconds()
            - (long) c.dropouts() * (c.durationSeconds() - c.durationSeconds() / 2);
    return Map.ofEntries(
        Map.entry("mode", mode),
        Map.entry("callsInitiated", initiated),
        Map.entry("callsAnswered", answered),
        Map.entry("callsConnected", connected),
        Map.entry("callsFailed", failed),
        Map.entry(
            "agentUtilization",
            capacitySeconds == 0 ? 0 : (double) occupiedSeconds / capacitySeconds),
        Map.entry("averageWaitingSeconds", connected == 0 ? 0 : (double) waiting / connected),
        Map.entry("abandonedConnectedCalls", abandoned),
        Map.entry("abandonmentRate", answered == 0 ? 0 : (double) abandoned / answered),
        Map.entry("safetyApprovals", approvals),
        Map.entry("safetyRejections", rejections),
        Map.entry("fallbackEvents", fallbacks),
        Map.entry("pacingChanges", changes),
        Map.entry("timeline", series),
        Map.entry("events", log),
        Map.entry("censoredActiveCalls", pending.size()));
  }
}
