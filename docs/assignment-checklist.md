# Original PDF assignment review

Reviewed against `Tech Assignment - Hiring 2026.pdf` on 26 October 2026. The original PDF asks for a functional prototype, not a production telecom platform.

| PDF requirement | Implementation / evidence | Status |
|---|---|---|
| Progressive dialing; 50 available agents permit at most 50 bound calls | SafetyController, PostgreSQL locks and partial unique indexes; fiftyAgentCapacityIsDeterministic test | Implemented and tested |
| Predictive pacing considering capacity, exposure and historical behavior | PacingEngine; measured setup/talk statistics; EWMA; pacing-algorithm.md | Implemented; statistical assumptions documented |
| Independent Safety Controller with approve/reduce/reject/fallback | SafetyController + SafetyPolicy; proposal and allocation share a transaction | Implemented and tested |
| Agent lifecycle and concurrent reservation | StateMachines; campaign/agent locks; active-agent unique index | Implemented and tested |
| Call lifecycle, repeated callbacks and late events | EventService; unique provider event IDs; terminal guards | Implemented and tested |
| Two different mock telecom providers | MockProviders A/B; durable records, latency, failures, timeouts, duplicate/disordered callbacks | Implemented and tested |
| Multi-worker allocation, retries, jobs, crashes and idempotency | Transactional outbox, dispatch leases, provider idempotency lookup, global borrower locking | Implemented; thread/connection concurrency tested, no multi-host load claim |
| Worker crash scenario | Crash-boundary durable-state injection and reconciliation tests | Tested; no OS-level process-kill experiment |
| Provider outage scenario | Durable provider health and timed breaker; fail-closed admission | Tested and available in UI |
| 100 agents lose 40 | Heartbeat exclusion and agentCollapseImmediatelyRestrictsNewCalls test | Tested |
| Duplicate ANSWERED callbacks | duplicateAnswersAreIdempotent test | Tested |
| COMPLETED before ANSWERED/RINGING | outOfOrderCannotReopenTerminal and actual provider disorder test | Tested |
| Simulator scenarios A/B/C/D, latency/failure variation, pacing and safety metrics | Simulator; Simulation lab; simulation-results.json | Implemented and executed |
| Scaling discussion for 100 / 1,000 / 10,000 agents | scaling-strategy.md | Written; projections separated from measurements |
| Basic load test | scripts/load-test.py and load-results.json | Executed; bounded local admission benchmark |
| Working source and setup README | backend/, frontend/, docker-compose.yml, README.md, Maven wrapper | Present; Docker database and live API verified |
| Architecture diagram and state-machine diagrams | architecture-diagram.md, agent-state-machine.md, call-state-machine.md | Present |
| Architecture decisions | architecture-decisions.md | Present |
| Tests | CoreIntegrationTest; docs/test-results.txt | 25 tests passed both on local PostgreSQL and Docker/Testcontainers; docker-test-results.txt |
| Final written question | final-question.md | Answered |

## Limits to state during submission

Predictive dialing does not guarantee zero abandonment: the checked-in scenario B has one predictive abandonment, and D includes abandonment after agent disappearance. Progressive mode guarantees exclusive allocation to a live agent at admission, not that the human remains online forever. The simulator shares pacing/safety policy code but is not a database/provider throughput benchmark. Authentication, signed webhooks and real telecom integration are not implemented; the PDF permits mock providers. Visual browser QA, remote GitHub Actions execution, multi-host testing and an OS-kill crash experiment have not been verified. Nothing has been pushed to GitHub.
