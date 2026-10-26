# Failure scenarios and executable evidence

Run `scripts/failure-demo.sh`. It executes five tests against an isolated Testcontainers PostgreSQL instance, or the explicitly selected disposable external PostgreSQL database. Each test prints `FAILURE_SCENARIO_EVIDENCE` followed by real call/event/agent state aggregates. Saved output from the completed full suite is in `failure-evidence.txt`; `test-results.txt` contains the full suite summary.

| Failure | Executable integration test | Detection and recovery |
|---|---|---|
| Worker crashes after provider acceptance | `workerCrashAfterProviderAcceptAvoidsDuplicateCall` | Durable provider acceptance exists while application dispatch remains uncertain. Next worker looks up the same idempotency key, records the existing ID, and does not create another provider call. |
| Provider outage | `providerOutageProtectsExistingAndStopsNew` | Mock health fails closed, new approval is zero; unknown existing dispatch retains its reservation. Restore provider and expire/reclaim dispatch lease; lookup then initiation uses the same key. |
| 100 agents lose 40 | `agentCollapseImmediatelyRestrictsNewCalls` | Heartbeat cutoff excludes forty agents before counting available capacity; recovery marks them OFFLINE. Next tick admits at most sixty. |
| Three ANSWERED events | `duplicateAnswersAreIdempotent` | Same event ID is deduplicated; fresh IDs representing an already applied answer are IGNORED. Only one CONNECTED transition/assignment. |
| COMPLETED → ANSWERED → RINGING | `outOfOrderCannotReopenTerminal` | Completion ends the call and releases ownership once. Later events persist as IGNORED; call remains COMPLETED. |

Additional tests cover completion duplicates, crash after answering, an agent disappearing before dispatch, circuit opening, no leaked reservation after a known failure, unavailable agents retaining unresolved calls, and campaign stop. These inject durable crash-boundary state rather than killing the Java process with a debugger. They validate recovery from the same persisted conditions.

## Unknown is not failed

An ambiguous timeout may follow provider acceptance. The worker never creates a fresh key. It queries by the same key first; UNKNOWN retains reservations. An authoritative NOT_FOUND permits a retry only when campaign/agent checks still allow it. A ten-second dispatch lease bounds worker ownership, not the physical call lifetime. Reservation expiry triggers lookup and does not authorize a second call. During an extended provider outage, reservations can remain held indefinitely. This sacrifices availability to avoid duplicate dialing; production needs operator reconciliation and provider contracts.

## Agent collapse limitations

The next approval reads live timestamps and therefore restricts new dialing immediately after the thirty-second heartbeat threshold or an explicit OFFLINE status update. Recovery's independent scheduled task runs every 500 ms; scheduling/database delays add detection latency. An explicit disconnect can be processed sooner than a missing heartbeat. Calls already ringing may answer after their reserved agent disappears. A reservation alone cannot guarantee an agent remains reachable. Such answers are marked FAILED with reason NO_AGENT and counted as abandoned. The D simulation intentionally shows this risk for both modes.

## Outage and circuit protection

Outage configuration closes admission immediately. Dispatch exceptions accumulate in durable provider health. After three failures, the circuit opens for fifteen seconds; after that a normal worker may probe by lookup. Existing calls are not locally declared failed because the provider is unavailable. This is a simple durable timed breaker, not a distributed single-probe half-open implementation. Restoring mock settings resets its failure counter/circuit.

## Visible virtual simulation

Scenario D in Simulation lab logs provider outage and midpoint dropout events alongside approvals, rejections, fallbacks, initiated/connected/failed counts, and abandonment. The three event-order/crash correctness cases are demonstrated by the real-database executable evidence above rather than pretending that an in-memory simulator tests PostgreSQL recovery.
