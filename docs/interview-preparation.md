# Interview preparation

1. **Where is the safety boundary?** `SafetyController.tick` holds the campaign lock, applies `SafetyPolicy`, and privately allocates calls in the same transaction. `PacingEngine` has no provider dependency.
2. **Why not an approval endpoint and a separate allocator endpoint?** A reusable approval could be spent twice or become stale. Here approval and capacity consumption commit together.
3. **Two workers pick one agent. What happens?** Campaign locking serializes same-campaign admissions, row locks protect selection, and a partial unique active-agent index enforces the invariant globally.
4. **What if two campaigns target the same borrower?** Allocation locks the global borrower row, and a partial unique borrower index disallows simultaneous active calls across campaigns.
5. **Why SKIP LOCKED?** Contending workers skip a row already owned by another transaction instead of blocking the whole work queue. It is not used as a substitute for authoritative capacity accounting.
6. **What is your isolation level?** PostgreSQL READ COMMITTED with explicit row locks. The campaign lock coordinates all state mutations influencing admission.
7. **What happens after committing reservations but before dispatch?** The transactional outbox survives. A worker claims it with a persisted lease.
8. **What if the provider accepts and the worker dies before acknowledgment?** Recovery looks up the same durable idempotency key and attaches the existing provider call; it does not generate another key or blindly retry.
9. **Does lease expiry mean a call is dead?** No. It means a worker's lease expired. External status must be reconciled before releasing physical call ownership.
10. **How are duplicate webhooks handled?** `(provider,event_id)` is unique; state transition guards also suppress logical duplicates carrying fresh IDs.
11. **COMPLETED before ANSWERED?** Complete terminally, release once, store late events as IGNORED, and avoid fabricating answer timestamps. Historical metrics may undercount until richer reconciliation exists.
12. **Crash immediately after ANSWERED?** Agent assignment and connection/failure commit with event processing. A crash rolls back the whole transaction or leaves a complete durable result.
13. **Explain 18 requested versus 11 approved.** With ten free agents and p=.5, pacing requests ceil(.9*10/.5)=18. Safety raises p to .65, bounds the probability of more than ten answers to 1%, and allows eleven after warmup; twelve exceeds the bound.
14. **Why use p+.15?** It is an explicit conservative modeling margin, not a calibrated confidence interval. It leaves headroom for recent answer-rate changes and needs empirical calibration in production.
15. **Does the binomial bound guarantee compliance?** No. It assumes independent answers and a trustworthy elevated probability. Correlation or agent dropout violates those assumptions.
16. **How does fallback handle already ringing predictive calls?** New progressive allocation is capped by both AVAILABLE agents and capacity minus existing exposure. It does not assume that changing mode erases outstanding risk.
17. **What happens when 40 of 100 agents disappear?** Fresh capacity excludes stale heartbeats or explicit OFFLINE agents; the next admission shrinks. Already initiated calls remain uncertain until provider evidence resolves them.
18. **What does the circuit breaker protect?** Three dispatch failures open a persisted timed breaker for fifteen seconds. New admission stops while open. Existing uncertain calls are retained. Single-probe distributed half-open behavior is future work.
19. **Why are provider records in the same PostgreSQL?** It makes the mock reproducible and restart-safe. Independent transactions reproduce the external acceptance/acknowledgment crash window without real telecom.
20. **Why explicit SQL alongside JPA?** JPA provides straightforward campaign reads. Critical writes use visible locking clauses and partial unique indexes rather than relying on ORM timing for a concurrency proof.
21. **What is the first scaling bottleneck?** The serial provider dispatcher and hot campaign row. Increasing server count does not remove either. Measure latency/lock waits and introduce bounded workers or partitioned capacity credits.
22. **How did you test the 50-agent requirement?** Six concurrent workers race a fifty-agent campaign against real PostgreSQL and assert exactly fifty distinct active agent-bound calls.
23. **What did you measure?** The checked-in JSON reports come from `scripts/simulate.py` and `scripts/load-test.py`. They distinguish deterministic simulation results from a bounded local admission benchmark.
24. **How is simulator utilization calculated?** Connected seconds for live agents divided by live capacity seconds over the configured horizon. Active calls at cutoff are censored; they are not invented as completed.
25. **Why can progressive simulation abandon after dropout?** It reserved an agent at initiation, but the agent disappeared before the borrower answered. Deterministic allocation cannot guarantee an external human remains reachable.
26. **Why no automatic retry of FAILED borrowers?** Automatic retry policy involves consent/contact windows, attempt limits, and cooldowns. This prototype marks campaign borrowers DONE and avoids accidental repeated contact.
27. **What does campaign stop guarantee?** No new admissions after the stop transaction and unsent work is cancelled. A previously authorized provider invocation may finish. Hard external cancellation needs a real provider contract.
28. **What would you do with another week?** Signed callbacks, authenticated agent sessions, real cancellation/status reconciliation, provider rate limits, a bounded parallel dispatcher, rolling-window calibration, correlated-answer experiments, and paginated monitoring APIs.
29. **What are you least confident about?** Predictive model calibration under correlation and the external provider's authoritative NOT_FOUND semantics. Both require stronger production contracts and measurement; they are not solved by local locks.
30. **Can the dashboard hide safety failures?** It displays measured backend counters and audit reasons. Empty charts remain empty until a simulation is run, and disconnected backend requests show an error rather than synthetic success.
