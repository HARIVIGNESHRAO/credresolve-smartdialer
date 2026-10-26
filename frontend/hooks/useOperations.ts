"use client";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Metrics, Campaign, Agent, Call, Decision } from "@/types";
export function useOperations() {
  const [data, setData] = useState<{
    metrics: Metrics;
    campaigns: Campaign[];
    agents: Agent[];
    calls: Call[];
    decisions: Decision[];
  } | null>(null);
  const [error, setError] = useState("");
  const [updated, setUpdated] = useState<Date | null>(null);
  const refresh = useCallback(async () => {
    try {
      const [metrics, campaigns, agents, calls, decisions] = await Promise.all([
        api<Metrics>("/dashboard/metrics"),
        api<Campaign[]>("/campaigns"),
        api<Agent[]>("/agents"),
        api<Call[]>("/calls"),
        api<Decision[]>("/safety/decisions"),
      ]);
      setData({ metrics, campaigns, agents, calls, decisions });
      setError("");
      setUpdated(new Date());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Backend unavailable");
    }
  }, []);
  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), 3000);
    return () => clearInterval(timer);
  }, [refresh]);
  return { data, error, updated, refresh };
}
