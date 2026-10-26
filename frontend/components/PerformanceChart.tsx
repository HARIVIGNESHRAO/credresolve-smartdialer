"use client";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  Legend,
} from "recharts";
import type { SimulationResult } from "@/types";
export function PerformanceChart({ sim }: { sim: SimulationResult }) {
  const points = sim.progressive.timeline.map((p, i) => ({
    second: p.second,
    Progressive: p.connected,
    Predictive: sim.predictive.timeline[i]?.connected ?? 0,
  }));
  return (
    <div className="chart">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart
          data={points}
          margin={{ left: 0, right: 20, top: 18, bottom: 6 }}
        >
          <defs>
            <linearGradient id="green" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#0e8d70" stopOpacity={0.2} />
              <stop offset="100%" stopColor="#0e8d70" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid
            strokeDasharray="3 3"
            vertical={false}
            stroke="#edf0ef"
          />
          <XAxis
            dataKey="second"
            tick={{ fontSize: 11 }}
            axisLine={false}
            tickLine={false}
            tickFormatter={(n) => `${n}s`}
          />
          <YAxis tick={{ fontSize: 11 }} axisLine={false} tickLine={false} />
          <Tooltip />
          <Legend iconType="circle" />
          <Area
            type="monotone"
            dataKey="Progressive"
            stroke="#97a6b6"
            fill="transparent"
            strokeWidth={2}
          />
          <Area
            type="monotone"
            dataKey="Predictive"
            stroke="#0e8d70"
            fill="url(#green)"
            strokeWidth={2.5}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
