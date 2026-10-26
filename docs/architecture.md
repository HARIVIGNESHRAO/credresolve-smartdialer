# Architecture

## Main path

`ApiController` / `DialerScheduler` → `PacingEngine` proposal → `SafetyController.tick` → private allocation loop → durable outbox → `DispatchWorker` → `TelecomProvider`.

`SafetyController` is the only application class that inserts `calls`, `reservations`, `provider_dispatches`, and `outbox_events`. Allocation is inside its approval transaction, not a separately injectable public allocator with unchecked input. The pacing engine has no provider/dispatcher dependency and cannot send calls. The dispatcher can only claim an already-approved durable call. There is no API for initiating a provider call directly. This boundary protects ordinary service paths; arbitrary trusted code or a database administrator can of course change data.

## Transaction and lock boundaries

Use PostgreSQL READ COMMITTED. A safety tick locks its campaign using `FOR UPDATE`. Counts, approval, allocations, and the audit record run within that transaction. Other workers for the same campaign wait, then count the committed exposure. Agent rows and campaign borrower memberships are selected with `FOR UPDATE SKIP LOCKED`. Global borrower rows are also locked because the same synthetic borrower may belong to multiple campaigns. A partial unique index is the final defense for each active `agent_id` and `borrower_id` across all campaigns.

All application paths changing campaign capacity (status changes, callbacks, recovery) lock the campaign before changing calls/agents. Lock order is campaign → call/membership/agent → dispatch records. Heartbeat writes only refresh a timestamp and do not make an agent available. A refreshed heartbeat cannot unconditionally turn an OFFLINE agent into AVAILABLE. An OFFLINE agent retaining an uncertain call cannot come back until that call resolves.

The campaign lock intentionally serializes per-campaign admissions; it favors a clear proof over maximum throughput. Different campaigns can progress concurrently. A locked or globally busy first eligible borrower may reduce a batch to zero rather than scan all alternatives. This is safe but can reduce throughput.

## Dispatch and uncertainty

An outbox row and dispatch record are inserted with the call. Claiming sets a ten-second database lease and marks the dispatch UNCERTAIN before leaving the transaction. The worker queries the provider by stable idempotency key. An existing record is reconciled. `UNKNOWN` retains the lease/reservation. Only an authoritative `NOT_FOUND` permits an initiation using the **same** key, and only if campaign and agent checks still permit it. An accepted provider call is persisted in a separate transaction before simulating response latency/timeouts.

The provider records live in PostgreSQL in this mock adapter. They deliberately do not cascade with application call deletion. A crash after acceptance and before acknowledgment is reproduced in an integration test by leaving the durable records in precisely that state. This is a fault-injection test, not an OS-level kill test. A restarted worker's lookup finds the same provider ID. A real adapter must support equivalent lookup/idempotency or conservatively keep the call uncertain for operator reconciliation.

Claim leases can expire while an invocation is slow. A second worker may call with the same key, but the provider's durable unique key prevents a second physical mock call. Expired application leases do not discard external ownership. Separate scheduled tasks process approvals, callbacks/recovery, and dispatch so slow providers do not block callback ingestion. The dispatch loop is intentionally bounded/serial; provider throughput is a known scaling limit.

## Events

`EventService.process` takes the campaign lock, then the call lock. Inserting `(provider,event_id)` with `ON CONFLICT DO NOTHING` makes transport retries idempotent. State guards make **different** event IDs describing the same logical event idempotent too. Terminal calls only record ignored events and never reopen. ANSWERED and its agent assignment commit together; a crash rolls both back or preserves both. There is no durable ANSWERED/agent-unassigned gap in ordinary processing.

COMPLETED is authoritative terminal evidence even before ANSWERED. It releases ownership but does not invent answer/connection timestamps. Late events are retained as IGNORED. Future provider detail reconciliation could recover historical answer metrics; this prototype prioritizes consistent ownership over guessing missing timestamps.

## Data and UI

Flyway creates the thirteen requested application tables plus durable mock provider calls and provider health. Timestamps use `timestamptz`. Partial indexes enforce active ownership and supporting indexes cover campaign state/allocation. `CampaignRepository` uses Spring Data JPA for campaign reads; explicit JDBC SQL and Spring transactions implement concurrency-sensitive paths using the same datasource and transaction manager.

The Next.js server proxies same-origin API requests. The client polls resources every three seconds, never manufactures operational metrics, and retains the last successful view while showing errors. Dashboard utilization is an instantaneous connected/all-agent ratio; simulator utilization is connected live-agent seconds / live capacity seconds. They have different denominators and are labeled accordingly.

## Source references

- [PostgreSQL SELECT / row locking](https://www.postgresql.org/docs/current/sql-select.html): SKIP LOCKED is appropriate for competing queue consumers, not a consistent analytical snapshot.
- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
- [Next.js App Router installation](https://nextjs.org/docs/app/getting-started/installation).
