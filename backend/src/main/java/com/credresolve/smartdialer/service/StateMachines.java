package com.credresolve.smartdialer.service;

import java.util.*;

public final class StateMachines {
  private StateMachines() {}

  private static final Map<String, Set<String>> AGENT =
      Map.of(
          "OFFLINE",
          Set.of("AVAILABLE"),
          "AVAILABLE",
          Set.of("RESERVED", "PAUSED", "OFFLINE"),
          "RESERVED",
          Set.of("DIALING", "AVAILABLE", "OFFLINE"),
          "DIALING",
          Set.of("CONNECTED", "AVAILABLE", "OFFLINE"),
          "CONNECTED",
          Set.of("WRAP_UP", "OFFLINE"),
          "WRAP_UP",
          Set.of("AVAILABLE", "PAUSED", "OFFLINE"),
          "PAUSED",
          Set.of("AVAILABLE", "OFFLINE"));
  private static final Map<String, Set<String>> CALL =
      Map.of(
          "QUEUED",
          Set.of("RESERVED", "CANCELLED"),
          "RESERVED",
          Set.of(
              "INITIATED", "RINGING", "ANSWERED", "CONNECTED", "COMPLETED", "FAILED", "CANCELLED"),
          "INITIATED",
          Set.of("RINGING", "ANSWERED", "CONNECTED", "COMPLETED", "FAILED", "CANCELLED"),
          "RINGING",
          Set.of("ANSWERED", "CONNECTED", "COMPLETED", "FAILED", "CANCELLED"),
          "ANSWERED",
          Set.of("CONNECTED", "COMPLETED", "FAILED"),
          "CONNECTED",
          Set.of("COMPLETED", "FAILED"));

  public static void agent(String from, String to) {
    if (!AGENT.getOrDefault(from, Set.of()).contains(to))
      throw new IllegalArgumentException("Invalid agent transition: " + from + " → " + to);
  }

  public static boolean call(String from, String to) {
    return CALL.getOrDefault(from, Set.of()).contains(to);
  }
}
