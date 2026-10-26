package com.credresolve.smartdialer.controller;

import com.credresolve.smartdialer.provider.MockProviders;
import com.credresolve.smartdialer.recovery.DispatchWorker;
import com.credresolve.smartdialer.repository.CampaignRepository;
import com.credresolve.smartdialer.safety.SafetyController;
import com.credresolve.smartdialer.service.*;
import com.credresolve.smartdialer.simulation.Simulator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final Database db;
  private final SafetyController safety;
  private final MockProviders providers;
  private final EventService events;
  private final Simulator simulations;
  private final CampaignRepository campaigns;
  private final DispatchWorker worker;

  public ApiController(
      Database db,
      SafetyController safety,
      MockProviders providers,
      EventService events,
      Simulator simulations,
      CampaignRepository campaigns,
      DispatchWorker worker) {
    this.db = db;
    this.safety = safety;
    this.providers = providers;
    this.events = events;
    this.simulations = simulations;
    this.campaigns = campaigns;
    this.worker = worker;
  }

  public record AgentInput(@NotBlank @Size(max = 100) String name, @NotNull UUID campaignId) {}

  public record StatusInput(@Pattern(regexp = "AVAILABLE|PAUSED|OFFLINE") @NotNull String status) {}

  public record CampaignInput(
      @NotBlank @Size(max = 120) String name,
      @NotNull @Pattern(regexp = "PROGRESSIVE|PREDICTIVE") String mode,
      @NotNull @Pattern(regexp = "A|B") String provider,
      @Min(1) @Max(10000) int maxActive,
      @DecimalMin("0") @DecimalMax("0.1") double riskLimit) {}

  public record TickInput(@NotNull UUID campaignId) {}

  public record EventInput(
      @NotNull @Pattern(regexp = "A|B") String provider,
      @NotBlank @Size(max = 200) String eventId,
      @NotNull UUID callId,
      @NotNull @Pattern(regexp = "RINGING|ANSWERED|COMPLETED|FAILED|CANCELLED") String type) {}

  public record ProviderInput(
      boolean outage,
      @Min(0) @Max(30000) int latencyMs,
      @DecimalMin("0") @DecimalMax("1") double failureRate,
      long seed) {}

  @GetMapping("/agents")
  public Object agents() {
    return db.sql.queryForList("select * from agents order by name limit 1000");
  }

  @PostMapping("/agents")
  public Object createAgent(@Valid @RequestBody AgentInput a) {
    UUID id = UUID.randomUUID();
    db.sql.update(
        "insert into agents(id,name,campaign_id,state) values(?,?,?,'OFFLINE')",
        id,
        a.name(),
        a.campaignId());
    return db.one("select * from agents where id=?", id);
  }

  @PatchMapping("/agents/{id}/status")
  public Object status(@PathVariable UUID id, @Valid @RequestBody StatusInput input) {
    return db.tx(
        () -> {
          UUID campaign =
              Database.uuid(
                  db.one("select campaign_id from agents where id=?", id).get("campaign_id"));
          db.lock(campaign);
          var a = db.one("select * from agents where id=? for update", id);
          if (a.get("state").equals(input.status())) return a;
          StateMachines.agent(a.get("state").toString(), input.status());
          if (a.get("current_call_id") != null && !input.status().equals("OFFLINE"))
            throw new IllegalArgumentException(
                "Active call must resolve before agent becomes available");
          db.sql.update(
              "update agents set state=?,last_heartbeat=now(),version=version+1 where id=?",
              input.status(),
              id);
          return db.one("select * from agents where id=?", id);
        });
  }

  @PostMapping("/agents/{id}/heartbeat")
  public Object heartbeat(@PathVariable UUID id) {
    if (db.sql.update("update agents set last_heartbeat=now() where id=?", id) == 0)
      throw new NoSuchElementException();
    return Map.of("status", "OK");
  }

  @PostMapping("/demo/heartbeat")
  public Object demoHeartbeat() {
    db.sql.update("update agents set last_heartbeat=now() where state<>'OFFLINE'");
    return Map.of(
        "status",
        "OK",
        "note",
        "Explicit synthetic demo heartbeat; real agents require their own authenticated session");
  }

  @PostMapping("/demo/activate")
  public Object activateDemo() {
    return db.tx(
        () -> {
          for (var c : db.sql.queryForList("select id from campaigns order by id")) {
            db.lock(Database.uuid(c.get("id")));
            db.sql.update(
                "update agents set state='AVAILABLE',last_heartbeat=now(),version=version+1 where campaign_id=? and current_call_id is null and state in ('OFFLINE','AVAILABLE')",
                c.get("id"));
          }
          return Map.of("status", "OK");
        });
  }

  @GetMapping("/campaigns")
  public Object campaigns() {
    return campaigns.findAll();
  }

  @PostMapping("/campaigns")
  public Object createCampaign(@Valid @RequestBody CampaignInput c) {
    UUID id = UUID.randomUUID();
    db.tx(
        () -> {
          db.sql.update(
              "insert into campaigns(id,name,mode,provider,max_active,risk_limit) values(?,?,?,?,?,?)",
              id,
              c.name(),
              c.mode(),
              c.provider(),
              c.maxActive(),
              c.riskLimit());
          for (int i = 0; i < 200; i++) {
            UUID b = UUID.randomUUID();
            db.sql.update(
                "insert into borrowers(id,name) values(?,?)", b, "Synthetic borrower " + (i + 1));
            db.sql.update(
                "insert into campaign_borrowers(campaign_id,borrower_id) values(?,?)", id, b);
          }
          return null;
        });
    return db.one("select * from campaigns where id=?", id);
  }

  @PostMapping("/campaigns/{id}/{action:start|stop}")
  public Object campaignAction(@PathVariable UUID id, @PathVariable String action) {
    return db.tx(
        () -> {
          db.lock(id);
          db.sql.update(
              "update campaigns set status=? where id=?",
              action.equals("start") ? "RUNNING" : "STOPPED",
              id);
          return db.one("select * from campaigns where id=?", id);
        });
  }

  @GetMapping("/calls")
  public Object calls() {
    return db.sql.queryForList(
        "select c.*,b.name borrower_name,a.name agent_name,extract(epoch from(coalesce(c.ended_at,now())-c.created_at)) duration_seconds from calls c join borrowers b on b.id=c.borrower_id left join agents a on a.id=c.agent_id order by c.created_at desc limit 300");
  }

  @GetMapping("/calls/{id}")
  public Object call(@PathVariable UUID id) {
    return Map.of(
        "call",
        db.one("select * from calls where id=?", id),
        "events",
        db.sql.queryForList("select * from call_events where call_id=? order by received_at", id));
  }

  @PostMapping("/dialer/{mode:progressive|predictive}/tick")
  public Object tick(@PathVariable String mode, @Valid @RequestBody TickInput input) {
    return safety.tick(input.campaignId(), mode.toUpperCase());
  }

  @GetMapping("/providers/health")
  public Object health() {
    return List.of(
        Map.of("name", "A", "health", providers.get("A").getHealth()),
        Map.of("name", "B", "health", providers.get("B").getHealth()));
  }

  @PatchMapping("/providers/{name:A|B}/mock")
  public Object provider(@PathVariable String name, @Valid @RequestBody ProviderInput input) {
    db.sql.update(
        "update provider_health set outage=?,latency_ms=?,failure_rate=?,seed=?,failures=0,circuit_until=null where provider=?",
        input.outage(),
        input.latencyMs(),
        input.failureRate(),
        input.seed(),
        name);
    return providers.get(name).getHealth();
  }

  @PostMapping("/providers/mock/events")
  public Object event(@Valid @RequestBody EventInput event) {
    return events.process(event.provider(), event.eventId(), event.callId(), event.type());
  }

  @PostMapping("/operations/recover")
  public Object recover() {
    worker.recoverAgents();
    worker.pollProvider();
    return Map.of("processedDispatch", worker.dispatchOne());
  }

  @PostMapping("/simulations/run")
  public Object simulate(@Valid @RequestBody Simulator.Config config) {
    return simulations.run(config);
  }

  @GetMapping("/simulations/{id}")
  public Object simulation(@PathVariable UUID id) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(
            db.one("select result::text result from simulation_runs where id=?", id)
                .get("result")
                .toString());
  }

  @GetMapping("/safety/decisions")
  public Object decisions() {
    return db.sql.queryForList(
        "select s.*,p.requested,p.probability,p.mode,p.explanation from safety_decisions s join pacing_decisions p using(id) order by s.created_at desc limit 100");
  }

  @GetMapping("/dashboard/metrics")
  public Object metrics() {
    var a =
        db.one(
            "select count(*) total,count(*) filter(where state='AVAILABLE' and last_heartbeat>now()-interval '30 seconds') available,count(*) filter(where state in ('RESERVED','DIALING')) reserved,count(*) filter(where state='CONNECTED') connected from agents");
    var c =
        db.one(
            "select count(*) total,count(*) filter(where state not in ('COMPLETED','FAILED','CANCELLED')) active,count(*) filter(where state='COMPLETED') completed,count(*) filter(where answered_at is not null) answered,count(*) filter(where reason='NO_AGENT') abandoned from calls");
    double total = ((Number) c.get("total")).doubleValue(),
        answered = ((Number) c.get("answered")).doubleValue(),
        agents = ((Number) a.get("total")).doubleValue();
    return Map.of(
        "agents",
        a,
        "calls",
        c,
        "answerRate",
        total == 0 ? 0 : answered / total,
        "agentUtilization",
        agents == 0 ? 0 : ((Number) a.get("connected")).doubleValue() / agents,
        "abandonmentRate",
        answered == 0 ? 0 : ((Number) c.get("abandoned")).doubleValue() / answered,
        "providers",
        health(),
        "safety",
        db.one(
            "select count(*) filter(where action in ('APPROVE','REDUCE','FALLBACK_TO_PROGRESSIVE')) approvals,count(*) filter(where action='REJECT') rejections,count(*) filter(where action='FALLBACK_TO_PROGRESSIVE') fallbacks from safety_decisions"));
  }
}
