# Scaling strategy

Measured evidence is in `load-results.json`. The probe sends 100 HTTP admission requests with ten workers against one 50-agent campaign, verifies the summed approval count never exceeds fifty, and stops its own campaign. It measures admission and PostgreSQL contention on this development machine. It excludes telecom throughput, sustained workloads, multiple hosts, and production SLO claims. Testcontainers/Linux CI and this session's local PostgreSQL may have different versions and performance.

## 100 agents

The monolith and indexed PostgreSQL are sufficient for a prototype. The first operational limit is the deliberately serial provider dispatcher: configured latency directly bounds requests/second. Keep callbacks on their independent scheduled task, use a bounded dispatch executor with leases, and apply provider-specific throughput quotas before increasing work. Polling dashboards scan current tables; modest volumes are acceptable.

## 1,000 agents

A busy single campaign serializes all admissions and state changes on one row. Batch size, lock wait, callback transaction latency, and connection pool saturation matter more than adding application instances. Measure pg_stat_statements, lock waits, outbox age, and callback lag. Introduce campaign shards with a fixed allocation of safety capacity credits, never an eventually consistent global counter. Each shard must consume credits and allocations atomically. Return credits only after terminal reconciliation. Partition agent/borrower work by stable key while retaining global active-ownership uniqueness or a dedicated global claim table.

Replace dashboard full-table aggregate queries with indexed time-bounded summaries or a transactionally maintained read model. Bound audit/event retention and paginate historical endpoints (current demo lists are capped at 1,000 agents, 300 calls, 100 decisions). Do not enlarge those caps indefinitely.

## 10,000 agents

Provider throughput, campaign lock contention, event ingestion, and hot ownership rows are likely bottlenecks. Separate admission batches from callback partitions with a rigorously specified capacity-credit protocol. Scale a bounded provider worker pool using SKIP LOCKED claims. Maintain idempotency keys across retries and across provider migration; never fail over an uncertain call to another provider without proving absence/cancellation at the original provider. Index callback lookup keys, partition old audit data by time, and archive terminal records. A message broker may help fan-out only after outbox lag proves it necessary; it does not replace database ownership or external idempotency.

## 100,000 agents / future work

Shard tenants/campaigns and provision a capacity ledger with clear consistency semantics. Enforce borrower exclusion across campaigns/tenants where the business requires it. Add authenticated heartbeat sessions, provider-signed callbacks, observability, a real cancel/reconcile workflow, per-provider rate limits, and fault-injection/load tests with correlated answers. A model can optimize recommendations, but safety remains a separate authoritative transaction boundary. These are projections, not measured results.
