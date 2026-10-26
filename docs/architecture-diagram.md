# Architecture diagram

```mermaid
flowchart LR
 UI[Next.js operations UI] --> API[Spring REST API]
 API --> P[PacingEngine: proposal only]
 SCH[Admission scheduler] --> P
 P --> S[SafetyController: lock campaign]
 S --> A[Private allocation loop]
 A --> DB[(PostgreSQL: ownership + outbox + audit)]
 DB --> W[DispatchWorker: leased consumer]
 W --> T[TelecomProvider interface]
 T --> PA[Durable Mock A]
 T --> PB[Durable Mock B]
 PA --> E[EventService: dedup + state guard]
 PB --> E
 E --> DB
 R[Recovery + callback scheduler] --> T
 R --> E
 SIM[Virtual-clock Simulator] --> POLICY[Shared PacingEngine + SafetyPolicy]
 SIM --> DB
 API --> SIM
```

The provider adapter has no role in deciding capacity. Allocation cannot be invoked as a standalone unchecked service. The simulator shares policy code, but does not claim to benchmark the live worker's database/provider throughput.
