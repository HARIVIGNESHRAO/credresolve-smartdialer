# Verification report

Executed on 26 October 2026 (Asia/Kolkata). Java 21.0.9, Spring Boot 3.5.15, local PostgreSQL, Node 20.20.0, Next.js 15.5.28. No results below are projections.

## Executed checks

- Maven wrapper `./mvnw -q spotless:apply verify spotless:check`: **25 tests, 0 failures, 0 errors, 0 skipped**; jar packaged and formatting checked. Database integration tests used a dedicated real local PostgreSQL database on port 55432, selected by `TEST_DATABASE_URL`.
- `npm run build`: production bundle compiled, TypeScript checks passed, static pages generated.
- `npm audit`: **0 vulnerabilities** after patching PostCSS tooling through explicit overrides.
- `docker compose config --quiet`: passed configuration validation.
- `python3 scripts/smoke.py`: all six read endpoints returned successful JSON; dashboard HTML served; Next.js `/api/dashboard/metrics` proxy retrieved the real backend payload.
- `python3 scripts/live-demo.py`: observed real scheduled dispatch, mock-provider callback, CONNECTED state and COMPLETED state. See `live-demo-results.json`.
- `python3 scripts/simulate.py`: all four seeded backend experiments completed and persisted; scenario results saved in `simulation-results.json`.
- `python3 scripts/load-test.py`: 100 HTTP admission requests / 10 workers; 50 calls approved by the probe, never more than its 50 agents.

## Actual simulator results

Configuration: 50 agents, 900 virtual seconds, seed 42, provider latency 500 ms, failure probability 0.03. D drops 20 agents at midpoint and includes a 15-second provider outage.

| Scenario | Mode | Initiated | Connected | Utilization | Abandoned | Fallback ticks |
|---|---|---:|---:|---:|---:|---:|
| A | progressive | 1912 | 348 | 87.26% | 0 | 0 |
| A | predictive | 1980 | 363 | 91.07% | 0 | 1 |
| B | progressive | 1035 | 488 | 93.12% | 0 | 0 |
| B | predictive | 1042 | 489 | 93.37% | 1 | 127 |
| C | progressive | 378 | 270 | 97.48% | 0 | 0 |
| C | predictive | 378 | 270 | 97.48% | 0 | 1 |
| D | progressive | 1187 | 260 | 89.74% | 2 | 0 |
| D | predictive | 1241 | 269 | 92.72% | 1 | 98 |

Predictive B had one abandonment and latched progressive fallback for the remainder of that run. Progressive D had two abandoned answers after reserved agents disappeared; predictive D had one. These results illustrate the limits of predictive probabilities and the difference between deterministic ownership and guaranteed human availability. C shows no measured utilization benefit under the conservative policy. Horizon-censored calls are reported in the raw JSON. These simulations model immediate answer bridging, not a real telecom platform.

## Local admission load probe

Elapsed 0.232s; 431.57 requests/s; p50 16.83ms; p95 62.44ms. This is a short local admission benchmark with mostly rejected requests after capacity fills. It is not sustained call throughput or a 10,000-agent capacity claim. See raw `load-results.json`.

## Verification limits

- Docker CLI is installed, but its daemon was stopped. Compose startup and the Testcontainers launch path were **not** executed successfully. The same tests ran against real local PostgreSQL instead; GitHub Actions is configured to exercise Testcontainers on an Ubuntu Docker runner, but remote CI has not been run.
- Browser runtime reported no available browser. No visual screenshot, responsive rendering inspection, or browser interaction test is claimed. The frontend production build, served HTML, and actual backend proxy passed.
- Port 8080 was occupied by another local service; no changes were made to that service. This project uses 8081.
- Crash tests inject the durable state left at a crash boundary; no OS process-kill chaos experiment was performed.
- Concurrency tests race real database transactions in one JVM using independent threads/connections; no multi-host deployment was benchmarked.
- Authentication, signed callbacks, provider cancel, production throttling, and distributed half-open circuit probes are outside this prototype.
