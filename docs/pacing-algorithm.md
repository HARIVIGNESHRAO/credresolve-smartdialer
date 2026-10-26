# Pacing and safety algorithm

The policy is small and explainable. `PacingEngine` only proposes; `SafetyController` counts fresh database state and applies `SafetyPolicy` while holding the campaign lock.

## Proposal

Let `a` be live AVAILABLE agents, `r` live RESERVED/DIALING agents, `x` setup/ringing/answer exposure, and `b` connected calls. Clamp the EWMA answer estimate `p` to [0.05,0.95]. Live observations update the campaign estimate on terminal outcomes:

`p_next = 0.85 p_previous + 0.15 outcome`, with outcome 1 for observed answered/connected and 0 otherwise.

Measured setup time `s` and talk time `t` estimate imminent releases:

`soon = floor(b * min(0.15, s / max(30,t)))`.

Progressive proposal: `requested = a`.

Predictive proposal: `requested = max(0, ceil(0.9 * (a + soon) / p) - x)`, capped at 1,000 per tick.

The 0.9 coefficient leaves a proposal margin; it is not a safety proof. Recently changed answers influence the EWMA. Provider health is a hard admission check rather than a multiplier which could dial through an outage.

## Safety boundary

Fresh capacity `c = a + r`; connected calls occupy agents and do not add capacity. The controller counts all nonterminal calls, including uncertain dispatches. Hard bound: `approved <= maxActive - activeCalls`.

Progressive or fallback approval is at most `min(a, max(0,c-x))`. The second term matters if predictive unbound calls already exist when fallback begins: reserving all remaining free agents for fresh progressive calls could otherwise steal capacity from old exposure. Every approved progressive/fallback call reserves its own agent.

For predictive approval, use `p_upper = min(0.99, p + 0.15)` and choose the largest `n <= requested` satisfying:

- `x+n <= 2c` and `n <= 1,000`;
- `P(Binomial(x+n,p_upper) > c) <= configuredRiskLimit`;
- campaign remaining capacity and provider health checks.

This implementation deliberately excludes forecast `soon` from safety capacity. Predictions about an agent finishing do not create a guaranteed slot. The binomial upper tail is computed through the finite cumulative recurrence, clamped to [0,1]. Extreme underflow makes the policy reject more calls, not approve more.

Cold start (<20 terminal live observations), recent abandonment (five-minute live window), or zero risk threshold triggers progressive fallback. A zero approval is REJECT; a smaller approval is REDUCE. Provider outage/circuit-open and a stopped campaign fail closed. The virtual simulator uses the same policies; for simplicity, an abandonment latches fallback for the rest of that simulation run rather than expiring after five minutes.

## Worked example

Ten AVAILABLE agents, no exposure, `p=0.5`, zero connected calls: proposal `ceil(0.9*10/0.5)=18`. With `p_upper=0.65` and risk threshold 1%, the Safety Controller must reduce this proposal. For 11 calls, overflowing ten slots means all eleven answer: `0.65^11 ≈ 0.00875`, so 11 satisfies the model. For twelve, the tail for eleven or twelve answers is about 0.0424, so twelve fails. The permitted batch is **11**, provided warmup is complete and all other limits allow it. During cold start the batch is **10**, each with a reserved agent.

## Assumptions and tradeoffs

Answers are modeled as independent Bernoulli outcomes with a stable elevated probability. Real answer spikes are correlated and poorly calibrated forecasts invalidate the numerical risk claim. A risk threshold is a model estimate per admission horizon, **not** a per-day compliance guarantee. Observed abandonment immediately restricts subsequent proposals; it cannot undo an already answered call. Reserving one live agent per physical call retains deterministic ownership/capacity but cannot keep an agent from disappearing.

Safety decisions persist requested/approved count, capacity, exposure, estimated probability, model risk, action, and reason. API response includes expected connected calls, margin, and explanation. No caller can switch off this policy via a flag or initiate a provider request directly.
