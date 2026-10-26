package com.credresolve.smartdialer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.credresolve.smartdialer.provider.*;
import com.credresolve.smartdialer.recovery.DispatchWorker;
import com.credresolve.smartdialer.safety.*;
import com.credresolve.smartdialer.service.*;
import com.credresolve.smartdialer.simulation.Simulator;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = "smartdialer.scheduling=false")
@AutoConfigureMockMvc
class CoreIntegrationTest {
  static PostgreSQLContainer<?> postgres;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    String url = System.getenv("TEST_DATABASE_URL");
    if (url == null) {
      postgres = new PostgreSQLContainer<>("postgres:17-alpine");
      postgres.start();
      r.add("spring.datasource.url", postgres::getJdbcUrl);
      r.add("spring.datasource.username", postgres::getUsername);
      r.add("spring.datasource.password", postgres::getPassword);
    } else {
      r.add("spring.datasource.url", () -> url);
      r.add("spring.datasource.username", () -> "smartdialer");
      r.add("spring.datasource.password", () -> "smartdialer");
    }
  }

  @Autowired Database db;
  @Autowired SafetyController safety;
  @Autowired EventService events;
  @Autowired DispatchWorker worker;
  @Autowired MockProviders providers;
  @Autowired Simulator simulator;
  @Autowired MockMvc api;
  UUID campaign;

  @BeforeEach
  void setup() {
    db.sql.execute(
        "truncate simulation_metrics,simulation_runs,call_events,reservations,outbox_events,provider_dispatches,mock_provider_calls,calls,agents,campaign_borrowers,borrowers,safety_decisions,pacing_decisions,campaigns cascade");
    db.sql.update(
        "update provider_health set outage=false,failures=0,circuit_until=null,latency_ms=0,failure_rate=0,seed=42");
    campaign = UUID.randomUUID();
    db.sql.update(
        "insert into campaigns(id,name,mode,provider,status,max_active,risk_limit) values(?,'Test','PROGRESSIVE','A','RUNNING',1000,0.01)",
        campaign);
    addAgents(1);
    addBorrowers(200);
  }

  void addAgents(int n) {
    for (int i = 0; i < n; i++)
      db.sql.update(
          "insert into agents(id,name,campaign_id,state) values(?,'Test agent',?,'AVAILABLE')",
          UUID.randomUUID(),
          campaign);
  }

  void addBorrowers(int n) {
    for (int i = 0; i < n; i++) {
      UUID b = UUID.randomUUID();
      db.sql.update("insert into borrowers(id,name) values(?,'Synthetic')", b);
      db.sql.update("insert into campaign_borrowers values(?,?,'ELIGIBLE')", campaign, b);
    }
  }

  UUID allocate() {
    var result = safety.tick(campaign, "PROGRESSIVE");
    return (UUID) ((List<?>) result.get("calls")).getFirst();
  }

  void race(int n, Callable<?> work) throws Exception {
    try (var pool = Executors.newFixedThreadPool(n)) {
      CountDownLatch start = new CountDownLatch(1);
      var jobs = new ArrayList<Future<?>>();
      for (int i = 0; i < n; i++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  return work.call();
                }));
      start.countDown();
      for (var job : jobs) job.get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void twoWorkersCannotReserveSameAgent() throws Exception {
    race(8, () -> safety.tick(campaign, "PROGRESSIVE"));
    assertEquals(1, db.count("select count(*) from calls"));
    assertEquals(1, db.count("select count(distinct agent_id) from calls"));
  }

  @Test
  void twoWorkersCannotReserveSameBorrower() throws Exception {
    addAgents(9);
    db.sql.update(
        "delete from campaign_borrowers where borrower_id<>(select borrower_id from campaign_borrowers limit 1)");
    race(8, () -> safety.tick(campaign, "PROGRESSIVE"));
    assertEquals(1, db.count("select count(*) from calls"));
  }

  @Test
  void fiftyAgentCapacityIsDeterministic() throws Exception {
    addAgents(49);
    race(6, () -> safety.tick(campaign, "PROGRESSIVE"));
    assertEquals(50, db.count("select count(*) from calls"));
    assertEquals(50, db.count("select count(distinct agent_id) from calls"));
  }

  @Test
  void predictiveCannotBypassSafetyAndConcurrentCapacity() throws Exception {
    addAgents(9);
    db.sql.update("update campaigns set max_active=7 where id=?", campaign);
    race(8, () -> safety.tick(campaign, "PREDICTIVE"));
    assertEquals(7, db.count("select count(*) from calls"));
    assertEquals(7, db.count("select coalesce(sum(approved),0) from safety_decisions"));
    assertTrue(db.count("select count(*) from safety_decisions where action='REJECT'") > 0);
  }

  @Test
  void pacingHasNoProviderDependency() {
    assertTrue(
        Arrays.stream(com.credresolve.smartdialer.dialing.PacingEngine.class.getDeclaredFields())
            .noneMatch(f -> f.getType().getPackageName().contains("provider")));
    assertTrue(
        Arrays.stream(SafetyController.class.getDeclaredFields())
            .noneMatch(f -> f.getType().getPackageName().contains("provider")));
  }

  @Test
  void duplicateAnswersAreIdempotent() {
    UUID id = allocate();
    events.process("A", "one", id, "ANSWERED");
    events.process("A", "one", id, "ANSWERED");
    events.process("A", "two", id, "ANSWERED");
    events.process("A", "three", id, "ANSWERED");
    assertEquals("CONNECTED", db.one("select state from calls where id=?", id).get("state"));
    assertEquals(1, db.count("select count(*) from call_events where outcome='APPLIED'"));
  }

  @Test
  void duplicateCompletionReleasesOnce() {
    UUID id = allocate();
    events.process("A", "answer", id, "ANSWERED");
    events.process("A", "complete", id, "COMPLETED");
    events.process("A", "complete", id, "COMPLETED");
    events.process("A", "complete2", id, "COMPLETED");
    assertEquals(1, db.count("select count(*) from reservations where released_at is not null"));
    assertEquals("WRAP_UP", db.one("select state from agents").get("state"));
  }

  @Test
  void outOfOrderCannotReopenTerminal() {
    UUID id = allocate();
    events.process("A", "complete", id, "COMPLETED");
    events.process("A", "answer", id, "ANSWERED");
    events.process("A", "ring", id, "RINGING");
    assertEquals("COMPLETED", db.one("select state from calls where id=?", id).get("state"));
    assertEquals(2, db.count("select count(*) from call_events where outcome='IGNORED'"));
  }

  @Test
  void workerCrashAfterProviderAcceptAvoidsDuplicateCall() {
    UUID id = allocate();
    var call = db.one("select * from calls where id=?", id);
    providers
        .get("A")
        .initiateCall(new TelecomProvider.CallRequest(id, call.get("idempotency_key").toString()));
    db.sql.update(
        "update provider_dispatches set attempts=1,status='UNCERTAIN' where call_id=?", id);
    assertTrue(worker.dispatchOne());
    assertEquals(1, db.count("select count(*) from mock_provider_calls"));
    assertEquals("INITIATED", db.one("select state from calls where id=?", id).get("state"));
    worker.dispatchOne();
    assertEquals(1, db.count("select count(*) from mock_provider_calls"));
  }

  @Test
  void crashAfterAnsweredCommitsDurablyThenCompletionWorks() {
    UUID id = allocate();
    events.process("A", "answer", id, "ANSWERED");
    worker.recoverAgents();
    events.process("A", "complete", id, "COMPLETED");
    assertEquals("COMPLETED", db.one("select state from calls where id=?", id).get("state"));
    assertEquals(0, db.count("select count(*) from agents where current_call_id is not null"));
  }

  @Test
  void providerOutageProtectsExistingAndStopsNew() {
    UUID id = allocate();
    db.sql.update("update provider_health set outage=true where provider='A'");
    assertEquals(0, safety.tick(campaign, "PROGRESSIVE").get("approvedCalls"));
    assertTrue(worker.dispatchOne());
    assertEquals("RESERVED", db.one("select state from calls where id=?", id).get("state"));
    assertEquals(0, db.count("select count(*) from mock_provider_calls"));
    db.sql.update("update provider_health set outage=false where provider='A'");
    db.sql.update("update outbox_events set lease_until=now()-interval '1 second'");
    worker.dispatchOne();
    assertEquals(1, db.count("select count(*) from mock_provider_calls"));
  }

  @Test
  void agentCollapseImmediatelyRestrictsNewCalls() {
    addAgents(99);
    db.sql.update(
        "update agents set last_heartbeat=now()-interval '60 seconds' where id in(select id from agents limit 40)");
    worker.recoverAgents();
    assertEquals(60, safety.tick(campaign, "PROGRESSIVE").get("approvedCalls"));
    assertEquals(40, db.count("select count(*) from agents where state='OFFLINE'"));
  }

  @Test
  void agentDisappearsBeforeDispatchDoesNotLeak() {
    UUID id = allocate();
    db.sql.update("update agents set last_heartbeat=now()-interval '60 seconds'");
    worker.recoverAgents();
    worker.dispatchOne();
    assertEquals("CANCELLED", db.one("select state from calls where id=?", id).get("state"));
    assertEquals(1, db.count("select count(*) from reservations where released_at is not null"));
    assertEquals(0, db.count("select count(*) from mock_provider_calls"));
  }

  @Test
  void knownProviderFailureReleasesReservations() {
    UUID id = allocate();
    events.process("A", "fail", id, "FAILED");
    assertEquals(
        1,
        db.count(
            "select count(*) from agents where state='AVAILABLE' and current_call_id is null"));
    assertEquals(1, db.count("select count(*) from reservations where released_at is not null"));
  }

  @Test
  void stoppedCampaignPreventsNewDispatch() {
    UUID id = allocate();
    db.sql.update("update campaigns set status='STOPPED' where id=?", campaign);
    assertEquals(0, safety.tick(campaign, "PROGRESSIVE").get("approvedCalls"));
    worker.dispatchOne();
    assertEquals(0, db.count("select count(*) from mock_provider_calls"));
    assertEquals("CANCELLED", db.one("select state from calls where id=?", id).get("state"));
  }

  @Test
  void simulatorReproducible() {
    var config = new Simulator.Config("D", 30, 600, 500, 0.05, 12, 42);
    assertEquals(
        simulator.simulate(config, "PREDICTIVE"), simulator.simulate(config, "PREDICTIVE"));
    var result =
        simulator.simulate(new Simulator.Config("A", 30, 600, 500, 0.05, 0, 42), "PROGRESSIVE");
    assertEquals(0, result.get("abandonedConnectedCalls"));
  }

  @Test
  void invalidTransitionsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> StateMachines.agent("CONNECTED", "AVAILABLE"));
    assertFalse(StateMachines.call("COMPLETED", "ANSWERED"));
  }

  @Test
  void apiValidationAndMetrics() throws Exception {
    api.perform(
            post("/api/agents")
                .contentType("application/json")
                .content("{\"name\":\"\",\"campaignId\":\"" + campaign + "\"}"))
        .andExpect(status().isBadRequest());
    api.perform(get("/api/dashboard/metrics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agents.available").value(1));
    api.perform(get("/api/calls/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void unboundExposureStillCountsOnFallback() {
    var decision =
        SafetyPolicy.decide("PREDICTIVE", 10, 10, 10, 12, 12, 100, 0.5, 0.01, true, true);
    assertEquals(0, decision.approved());
  }

  @Test
  void binomialBoundKnownExample() {
    assertEquals(0.25, SafetyPolicy.overflowProbability(2, 1, 0.5), 1e-9);
    assertEquals(0, SafetyPolicy.overflowProbability(5, 5, 0.9));
  }

  @AfterEach
  void evidence(TestInfo info) {
    if (Set.of(
            "workerCrashAfterProviderAcceptAvoidsDuplicateCall",
            "providerOutageProtectsExistingAndStopsNew",
            "agentCollapseImmediatelyRestrictsNewCalls",
            "duplicateAnswersAreIdempotent",
            "outOfOrderCannotReopenTerminal")
        .contains(info.getTestMethod().orElseThrow().getName())) {
      System.out.println(
          "FAILURE_SCENARIO_EVIDENCE "
              + info.getDisplayName()
              + " calls="
              + db.sql.queryForList("select state,count(*) count from calls group by state")
              + " events="
              + db.sql.queryForList(
                  "select outcome,count(*) count from call_events group by outcome")
              + " agents="
              + db.sql.queryForList("select state,count(*) count from agents group by state"));
    }
  }

  @Test
  void ambiguousTimeoutReconcilesWithoutBlindRetry() {
    db.sql.update("update campaigns set provider='B' where id=?", campaign);
    UUID id = allocate();
    String key =
        db.one("select idempotency_key from calls where id=?", id)
            .get("idempotency_key")
            .toString();
    long timeoutSeed = 0;
    for (; ; timeoutSeed++) {
      var random = new Random(timeoutSeed ^ key.hashCode());
      random.nextDouble();
      random.nextDouble();
      if (random.nextDouble() < 0.2) break;
    }
    db.sql.update("update provider_health set seed=? where provider='B'", timeoutSeed);
    assertThrows(
        MockProviders.AmbiguousTimeout.class,
        () -> providers.get("B").initiateCall(new TelecomProvider.CallRequest(id, key)));
    db.sql.update(
        "update provider_dispatches set attempts=1,status='UNCERTAIN' where call_id=?", id);
    worker.dispatchOne();
    assertEquals(1, db.count("select count(*) from mock_provider_calls"));
    assertEquals(1, db.count("select count(*) from calls"));
  }

  @Test
  void unavailableProviderTripsCircuitAndRetainsUnknownReservation() {
    allocate();
    db.sql.update("update provider_health set outage=true where provider='A'");
    for (int i = 0; i < 3; i++) {
      db.sql.update("update outbox_events set lease_until=now()-interval '1 second'");
      worker.dispatchOne();
    }
    assertEquals("OPEN", providers.get("A").getHealth().circuit());
    assertEquals(0, db.count("select count(*) from reservations where released_at is not null"));
  }

  @Test
  void offlineAgentCannotBeReusedWhileCallIsUncertain() throws Exception {
    UUID id = allocate();
    UUID agent = Database.uuid(db.one("select agent_id from calls where id=?", id).get("agent_id"));
    api.perform(
            patch("/api/agents/" + agent + "/status")
                .contentType("application/json")
                .content("{\"status\":\"OFFLINE\"}"))
        .andExpect(status().isOk());
    api.perform(
            patch("/api/agents/" + agent + "/status")
                .contentType("application/json")
                .content("{\"status\":\"AVAILABLE\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sameBorrowerAcrossCampaignsIsProtected() throws Exception {
    UUID other = UUID.randomUUID();
    db.sql.update(
        "insert into campaigns(id,name,mode,provider,status,max_active,risk_limit) values(?,'Other','PROGRESSIVE','A','RUNNING',100,0.01)",
        other);
    db.sql.update(
        "delete from campaign_borrowers where borrower_id<>(select borrower_id from campaign_borrowers limit 1)");
    db.sql.update(
        "insert into campaign_borrowers select ?,borrower_id,'ELIGIBLE' from campaign_borrowers where campaign_id=?",
        other,
        campaign);
    db.sql.update(
        "insert into agents(id,name,campaign_id,state) values(?,'Other',?,'AVAILABLE')",
        UUID.randomUUID(),
        other);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> safety.tick(campaign, "PROGRESSIVE"));
      var b = pool.submit(() -> safety.tick(other, "PROGRESSIVE"));
      a.get();
      b.get();
    }
    assertEquals(1, db.count("select count(*) from calls"));
  }

  @Test
  void providerBEmitsCompletionBeforeAnswerForDisorderedCalls() {
    db.sql.update("update campaigns set provider='B' where id=?", campaign);
    UUID id = allocate();
    String key =
        db.one("select idempotency_key from calls where id=?", id)
            .get("idempotency_key")
            .toString();
    try {
      providers.get("B").initiateCall(new TelecomProvider.CallRequest(id, key));
    } catch (MockProviders.AmbiguousTimeout expected) {
    }
    String external = "B-disorder";
    while (Math.floorMod(external.hashCode(), 5) != 0) external += "x";
    db.sql.update(
        "update mock_provider_calls set id=?,will_answer=true,fail=false,answer_at=now()-interval '10 seconds',finish_at=now()-interval '1 second' where call_id=?",
        external,
        id);
    worker.pollProvider();
    assertEquals("COMPLETED", db.one("select state from calls where id=?", id).get("state"));
    assertEquals(0, db.count("select count(*) from calls where answered_at is not null"));
    assertTrue(db.count("select count(*) from call_events where outcome='IGNORED'") >= 2);
  }
}
