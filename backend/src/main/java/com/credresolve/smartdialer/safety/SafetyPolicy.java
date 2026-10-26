package com.credresolve.smartdialer.safety;

/** Pure policy shared by the live safety boundary and deterministic simulator. */
public final class SafetyPolicy {
  private SafetyPolicy() {}

  public record Limit(int approved, String action, double risk, String reason) {}

  public static double overflowProbability(int n, int capacity, double p) {
    if (n <= capacity) return 0;
    if (capacity < 0) return 1;
    if (p >= 1) return 1;
    double mass = Math.pow(1 - p, n), cdf = mass;
    for (int k = 1; k <= capacity; k++) {
      mass *= ((double) (n - k + 1) / k) * (p / (1 - p));
      cdf += mass;
    }
    return Math.clamp(1 - cdf, 0, 1);
  }

  public static Limit decide(
      String mode,
      int requested,
      int available,
      int capacity,
      int exposure,
      int active,
      int maxActive,
      double p,
      double riskLimit,
      boolean healthy,
      boolean fallback) {
    if (!healthy || available < 0)
      return new Limit(0, "REJECT", 0, "Provider unhealthy: fail closed");
    if (available == 0 && capacity == 0) return new Limit(0, "REJECT", 0, "No live agent capacity");
    boolean progressive = mode.equals("PROGRESSIVE") || fallback || riskLimit == 0;
    int bound =
        progressive
            ? Math.min(available, Math.max(0, capacity - exposure))
            : Math.max(0, Math.min(2 * capacity - exposure, 1000));
    int n = Math.max(0, Math.min(requested, Math.min(bound, maxActive - active)));
    double risk = 0;
    if (!progressive) { // Use an upper answer probability; independence remains an explicit
      // assumption.
      double upper = Math.min(0.99, p + 0.15);
      while (n > 0 && overflowProbability(exposure + n, capacity, upper) > riskLimit) n--;
      risk = overflowProbability(exposure + n, capacity, upper);
    }
    return new Limit(
        n,
        n == 0
            ? "REJECT"
            : mode.equals("PREDICTIVE") && (fallback || riskLimit == 0)
                ? "FALLBACK_TO_PROGRESSIVE"
                : n < requested ? "REDUCE" : "APPROVE",
        risk,
        progressive
            ? "One reserved agent per new call"
            : "Binomial tail bounded using p + 0.15; maximum 2x capacity; correlated answers remain a risk");
  }
}
