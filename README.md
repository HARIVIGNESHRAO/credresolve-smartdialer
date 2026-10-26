# CredResolve SmartDialer

A working, local-only outbound operations prototype for the **Backend / Full-stack Engineer** assignment. Java 21 / Spring Boot 3.5, PostgreSQL, Flyway, Next.js App Router, TypeScript, Tailwind, Recharts. All borrowers are synthetic; both telecom providers are durable mocks. No actual telephone numbers or outbound calls.

The supplied technical assignment and job description were read before implementation. The separate Data Analyst assignment is outside this project.

## Start locally

Prerequisites: JDK **21**, Docker with Compose, Node **20.9+**, npm. Maven wrapper included. Ports: database 5432, API **8081**, dashboard **3000**. Port 8081 avoids an existing local service encountered during verification.

```sh
cd credresolve-smartdialer
docker compose up -d --wait
cd backend
./mvnw spring-boot:run
```

In another terminal:

```sh
cd credresolve-smartdialer/frontend
npm ci
npm run build
npm start
```

Open **http://127.0.0.1:3000**. The frontend proxies `/api/*` to `http://127.0.0.1:8081`. For a different backend, set `BACKEND_URL` **when building** Next.js. To change the API port set `PORT`. Database settings are `DATABASE_URL` (JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`. `.env.example` documents values; Spring does not automatically read this file. Export variables in the shell if changing defaults. `POSTGRES_PASSWORD` configures Compose independently of Spring's `DATABASE_PASSWORD`.

### Demonstrate live operations

1. Click **Enable demo agents** at the top. This explicitly activates idle demo agents and sends a heartbeat every ten seconds while the page remains open. Closing the page stops heartbeats; the backend marks missing agents OFFLINE after thirty seconds.
2. In Campaigns, start the seeded campaign. It has 50 agents and 1,000 synthetic borrowers. Progressive calls never exceed current live agent capacity.
3. Inspect Live calls, Agents, Safety monitor, and Providers. Refresh polling is three seconds. Provider A answers/fails and completes calls using its durable virtual provider records.
4. Use Providers to put A into an outage. Existing calls remain allocated while outcomes are unknown; new approvals stop. Restore A to recover. Recovery runs automatically and can also be triggered manually.
5. Create a predictive campaign, add agents assigned to it, make them available, and start it. Its first twenty terminal observations use progressive fallback. New campaigns get 200 synthetic borrowers.
6. In Simulation lab compare scenarios A–D. Scenario D changes answer/talk behavior, introduces a provider outage, and supports midpoint agent disappearance. All plots use actual backend results, with empty states before a run.

Campaign limits, provider, and risk threshold are configured at creation. Start/stop are supported; editing an existing campaign's parameters is deliberately omitted. There are no decorative action buttons.

## Verify

```sh
cd backend
./mvnw test       # Starts PostgreSQL through Testcontainers; Docker required
./mvnw spotless:check
cd ../frontend
npm ci
npm run build
npm audit
cd ..
python3 scripts/smoke.py
python3 scripts/live-demo.py
python3 scripts/simulate.py
python3 scripts/load-test.py
scripts/failure-demo.sh
```

For an existing **dedicated, disposable PostgreSQL test database**:

```sh
TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/smartdialer_test ./mvnw test
```

The external test database must have username/password `smartdialer`. Tests truncate application tables; never point this variable at a valuable database. Without `TEST_DATABASE_URL`, the suite creates its own Testcontainers PostgreSQL 17 instance. H2 is not used. Set `DIALER_SCHEDULING=false` for manually controlled worker demonstrations.

`scripts/simulate.py` stores actual results in `docs/simulation-results.json`. The bounded load probe creates its own campaign and 50 agents; stops that campaign afterward. It is an admission benchmark, not a production capacity claim. See [verification](docs/verification.md) for commands actually executed in this environment, outcomes, and limits. [Failure evidence](docs/failure-scenarios.md) maps the five required scenarios to executable database tests and visible output.

## Repository map

```text
backend/       Spring Boot API, Flyway schema/seeds, safety, pacing, durable providers,
               callback processing, outbox dispatch/reconciliation, simulator, DB tests
frontend/      Next.js App Router dashboard, polling hook, API client, shared types
scripts/       API smoke check, seeded experiments, load probe, failure tests
.github/       Java/Testcontainers and Node production-build CI
docs/          architecture, diagrams, state machines, decisions, pacing, failures,
               scaling, measured reports, interview questions, final assignment answer
```

Use the module entry points in [architecture](docs/architecture.md) during the interview. SQL controls all critical write paths; JPA is used for campaign reads. This is an intentional refinement of a conventional entity-per-table design to make locking and constraints directly reviewable.

## Safety contract and limits

- **Progressive:** each initiated call has its own reserved agent. PostgreSQL locking plus a partial unique index prevents two active calls from owning one agent. Agent survival, provider cancellation, and delivery order are external uncertainties: reserving a live agent is not a guarantee that they remain online.
- **Predictive:** unbound calls are allowed only inside a bounded binomial tail estimate using an elevated answer probability. Cold start, recent abandonment, or a zero risk setting falls back to progressive allocation. Correlated answers can violate the independence assumption; zero abandonment is **not** guaranteed.
- Safety approval and allocation commit in the same transaction under a campaign lock. Dispatch consumes existing approvals and cannot create calls. Network latency is outside application transactions.
- An expired lease prompts reconciliation. It does **not** free a possibly live call or authorize another call. Mock provider idempotency survives application restarts. Unknown outcomes retain reservations until evidence is available.
- Stop closes admission and cancels unsent work. A provider invocation already authorized/in progress at the stop boundary may finish. Hard cancellation requires a provider cancel protocol, which this prototype does not implement.

This is a functional hiring prototype, **not a production deployment**. It binds to localhost, has no authentication/RBAC or signed webhook verification, and exposes mock controls. Do not publish it unchanged. There is no automatic redial campaign policy, real agent session service, production rate limiter, provider cancellation, distributed half-open circuit probe, or event retention policy. Provider samples use compressed real time; performance simulations use an independent deterministic second clock sharing the same policy classes. Simulation has immediate bridge/no answer queue, omits wrap-up time and models observed provider outage explicitly. Calls active at the horizon are censored and reported separately. Visual browser QA was unavailable in this session; the production build and HTTP proxy were tested. Docker/Testcontainers execution was unavailable because the daemon was stopped; the tests ran against real local PostgreSQL instead.

## Assignment's final question

[Read the concise answer](docs/final-question.md): preserve deterministic agent ownership and capacity enforcement, share an independent safety boundary, and treat every speculative call as statistical exposure. To retain a strict no-overdial contract, use predictive estimates for timing and borrower selection while reserving an agent before every initiation.

No remote is configured and nothing has been pushed to GitHub.
