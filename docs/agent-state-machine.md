# Agent state machine

```mermaid
stateDiagram-v2
 [*] --> OFFLINE
 OFFLINE --> AVAILABLE: explicit availability + no unresolved call
 AVAILABLE --> RESERVED: atomic progressive/fallback allocation
 AVAILABLE --> CONNECTED: predictive answer allocation
 AVAILABLE --> PAUSED
 AVAILABLE --> OFFLINE
 RESERVED --> DIALING: provider acknowledgment
 RESERVED --> AVAILABLE: failure/cancellation
 RESERVED --> CONNECTED: answer beats acknowledgment
 RESERVED --> OFFLINE: agent disappears
 DIALING --> CONNECTED: answer
 DIALING --> AVAILABLE: failure/cancellation
 DIALING --> OFFLINE
 CONNECTED --> WRAP_UP: terminal callback
 CONNECTED --> OFFLINE
 WRAP_UP --> AVAILABLE: three seconds elapsed
 WRAP_UP --> PAUSED
 WRAP_UP --> OFFLINE
 PAUSED --> AVAILABLE
 PAUSED --> OFFLINE
```

`StateMachines.agent` guards manual transitions. RESERVED, DIALING, CONNECTED, and WRAP_UP are service-owned; REST status requests accept only AVAILABLE/PAUSED/OFFLINE. Event processing handles external callbacks that skip intermediate observed states: an ANSWERED callback can bridge directly from RESERVED, and predictive calls acquire an AVAILABLE agent at answer time. These internal shortcuts are intentional and do not expose manual bypasses.

A disappearing agent becomes OFFLINE but keeps its call pointer while the outcome is uncertain. Release removes that pointer; an OFFLINE agent stays OFFLINE. A partial unique index on active call `agent_id` prevents a second simultaneous active bound call even if application logic regresses. Seeded agents require real or explicitly enabled demo heartbeats; availability is not silently refreshed by the backend.
