package com.credresolve.smartdialer.dialing;

import org.springframework.stereotype.Component;

@Component
public class PacingEngine {
  public record Proposal(String mode, int requested, double probability, String explanation) {}

  public Proposal propose(
      String mode,
      int free,
      int exposure,
      int connected,
      double probability,
      double setupSeconds,
      double talkSeconds) {
    double p = Math.clamp(probability, 0.05, 0.95);
    int soon =
        (int) Math.floor(connected * Math.min(0.15, setupSeconds / Math.max(30, talkSeconds)));
    int n =
        mode.equals("PROGRESSIVE")
            ? free
            : Math.max(0, (int) Math.ceil((free + soon) * 0.9 / p) - exposure);
    return new Proposal(
        mode,
        Math.min(1000, n),
        p,
        "EWMA answer rate; 10% capacity margin; estimated releases="
            + soon
            + "; setup="
            + setupSeconds
            + "s, talk="
            + talkSeconds
            + "s. Proposal requires independent safety approval.");
  }
}
