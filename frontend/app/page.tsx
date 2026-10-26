"use client";
import {
  Badge,
  CampaignTable,
  AgentTable,
  CallTable,
  DecisionTable,
} from "@/components/OperationsTables";
import { PerformanceChart } from "@/components/PerformanceChart";

import { useState, useEffect } from "react";
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  AudioLines,
  BarChart3,
  ChevronRight,
  Clock3,
  FlaskConical,
  Headphones,
  LayoutDashboard,
  Phone,
  Play,
  Plus,
  Radio,
  RefreshCw,
  ShieldCheck,
  Square,
  Users,
  Zap,
  Server,
  CheckCircle2,
  AlertTriangle,
} from "lucide-react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  BarChart,
  Bar,
  Legend,
} from "recharts";
import { useOperations } from "@/hooks/useOperations";
import { api } from "@/lib/api";
import type {
  SimulationResult,
  Campaign,
  Agent,
  Call,
  Decision,
} from "@/types";
const navigation = [
  ["Overview", LayoutDashboard],
  ["Campaigns", Radio],
  ["Agents", Users],
  ["Live calls", Phone],
  ["Simulation lab", FlaskConical],
  ["Safety monitor", ShieldCheck],
  ["Providers", Server],
] as const;
const percent = (n: number) => `${(n * 100).toFixed(1)}%`;
export default function Dashboard() {
  const { data, error, updated, refresh } = useOperations();
  const [tab, setTab] = useState("Overview");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [sim, setSim] = useState<SimulationResult | null>(null);
  const [simConfig, setSimConfig] = useState({
    scenario: "A",
    agents: 50,
    durationSeconds: 900,
    providerLatencyMs: 500,
    failureRate: 0.03,
    dropouts: 0,
    seed: 42,
  });
  async function action(path: string, method = "POST", body?: unknown) {
    setBusy(true);
    setNotice("");
    try {
      await api(path, method, body);
      await refresh();
      setNotice("Operation completed");
    } catch (e) {
      setNotice(e instanceof Error ? e.message : "Operation failed");
    } finally {
      setBusy(false);
    }
  }
  async function simulate() {
    setBusy(true);
    setNotice("");
    try {
      setSim(
        await api<SimulationResult>("/simulations/run", "POST", simConfig),
      );
      setNotice("Simulation completed with deterministic backend results");
    } catch (e) {
      setNotice(e instanceof Error ? e.message : "Simulation failed");
    } finally {
      setBusy(false);
    }
  }
  const [demoHeartbeat, setDemoHeartbeat] = useState(false);
  useEffect(() => {
    if (!demoHeartbeat) return;
    void api("/demo/activate", "POST")
      .then(() => refresh())
      .catch((e) => setNotice(e.message));
    const timer = setInterval(
      () =>
        void api("/demo/heartbeat", "POST").catch((e) => setNotice(e.message)),
      10000,
    );
    return () => clearInterval(timer);
  }, [demoHeartbeat, refresh]);
  const metrics = data?.metrics;
  const running =
    data?.campaigns.filter((c) => c.status === "RUNNING").length || 0;
  return (
    <div className="shell">
      <aside>
        <a className="brand" href="/" aria-label="SmartDialer home">
          <span className="brand-mark">
            <AudioLines size={24} />
          </span>
          <span>
            credresolve<span className="brand-sub">SMARTDIALER</span>
          </span>
        </a>
        <div className="workspace">
          <span className="workspace-icon">CR</span>
          <div>
            Operations workspace<small>Local prototype</small>
          </div>
          <ChevronRight size={14} />
        </div>
        <div className="nav-label">WORKSPACE</div>
        <nav>
          {navigation.map(([label, Icon]) => (
            <button
              key={label}
              className={tab === label ? "selected" : ""}
              onClick={() => setTab(label)}
            >
              <Icon size={18} />
              {label}
              {label === "Live calls" && (
                <span className="nav-count">
                  {metrics?.calls.active ?? "—"}
                </span>
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="safety-note">
            <ShieldCheck size={18} />
            <strong>Safety is always on</strong>
            <p>Every proposal passes through an independent controller.</p>
          </div>
          <div className="profile">
            <span className="avatar">OP</span>
            <div>
              Operations engineer<small>Demo environment</small>
            </div>
            <span className="live-dot" />
          </div>
        </div>
      </aside>
      <main>
        <header>
          <div className="breadcrumb">
            Workspace <ChevronRight size={13} /> <strong>{tab}</strong>
          </div>
          <div className="header-right">
            <span className="environment">
              <span className="live-dot" /> MOCK PROVIDERS ONLY
            </span>
            <span className="avatar small">OP</span>
          </div>
        </header>
        <div className="content">
          <div className="page-title">
            <div>
              <div className="eyebrow">
                SMARTER CONNECTIONS. SAFER OPERATIONS.
              </div>
              <h1>{tab === "Overview" ? "Your operations, in focus." : tab}</h1>
              <p>
                {tab === "Overview"
                  ? "A live view of your agents, campaigns, and calling performance."
                  : "Control and observe your SmartDialer workspace."}
              </p>
            </div>
            <button className="button secondary" onClick={() => void refresh()}>
              <RefreshCw size={15} />
              Refresh
            </button>
          </div>
          <div className="status-strip">
            <span>
              <span className={"live-dot " + (error ? "red" : "")} />
              {error ? "Backend disconnected" : "Live backend data"}
              <span className="separator">/</span>
              {updated
                ? `Updated ${updated.toLocaleTimeString()}`
                : "Connecting…"}
            </span>
            <button
              className="demo-toggle"
              onClick={() => setDemoHeartbeat(!demoHeartbeat)}
            >
              <span className={"live-dot " + (!demoHeartbeat ? "red" : "")} />
              {demoHeartbeat ? "Demo heartbeats on" : "Enable demo agents"}
            </button>
          </div>
          {error && (
            <div role="alert" className="error">
              <AlertTriangle size={18} />
              {error}. Start the backend on port 8081 and retry.
            </div>
          )}
          {notice && (
            <div role="status" className="notice">
              {notice}
              <button
                onClick={() => setNotice("")}
                aria-label="Dismiss notification"
              >
                ×
              </button>
            </div>
          )}
          {!data && !error && (
            <div className="empty">Loading live operations…</div>
          )}
          {data && tab === "Overview" && (
            <>
              <div className="stat-grid">
                {[
                  {
                    label: "Available agents",
                    value: data.metrics.agents.available,
                    sub: `${data.metrics.agents.total} agents in workspace`,
                    Icon: Users,
                  },
                  {
                    label: "Active calls",
                    value: data.metrics.calls.active,
                    sub: `${running} running campaigns`,
                    Icon: Phone,
                  },
                  {
                    label: "Calls completed",
                    value: data.metrics.calls.completed,
                    sub: "Since database initialization",
                    Icon: CheckCircle2,
                  },
                  {
                    label: "Agent utilization",
                    value: percent(data.metrics.agentUtilization),
                    sub: "Currently connected / all agents",
                    Icon: Activity,
                  },
                ].map(({ label, value, sub, Icon }) => (
                  <div className="stat" key={label}>
                    <div className="stat-top">
                      {label}
                      <Icon size={17} />
                    </div>
                    <strong>{value}</strong>
                    <span>{sub}</span>
                  </div>
                ))}
              </div>
              <div className="overview-grid">
                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Calling performance</h2>
                      <p>Measured results from your simulation laboratory</p>
                    </div>
                    <span className="pill">Seeded simulation</span>
                  </div>
                  {sim ? (
                    <PerformanceChart sim={sim} />
                  ) : (
                    <div className="chart-empty">
                      <BarChart3 size={36} />
                      <h3>Your first experiment starts here</h3>
                      <p>
                        Run a simulation to compare progressive and predictive
                        dialing
                        <br />
                        under identical operating conditions.
                      </p>
                      <button
                        className="button secondary"
                        onClick={() => setTab("Simulation lab")}
                      >
                        Open simulation lab <ArrowUpRight size={15} />
                      </button>
                    </div>
                  )}
                  <div className="panel-footer">
                    <span>
                      Answer rate{" "}
                      <strong>{percent(data.metrics.answerRate)}</strong>
                    </span>
                    <span>
                      Abandonment{" "}
                      <strong>{percent(data.metrics.abandonmentRate)}</strong>
                    </span>
                    <span>
                      Reserved agents{" "}
                      <strong>{data.metrics.agents.reserved}</strong>
                    </span>
                  </div>
                </section>
                <section className="panel safety-panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Safety pulse</h2>
                      <p>Independent. Atomic. Always enforced.</p>
                    </div>
                    <ShieldCheck size={21} />
                  </div>
                  <div className="safety-orbit">
                    <ShieldCheck size={44} />
                  </div>
                  <h3>Every call has a checkpoint.</h3>
                  <p>
                    Capacity limits and provider health are checked before
                    allocation.
                  </p>
                  <div className="safety-numbers">
                    <div>
                      <strong>{data.metrics.safety.approvals}</strong>
                      <span>Approved</span>
                    </div>
                    <div>
                      <strong>{data.metrics.safety.rejections}</strong>
                      <span>Rejected</span>
                    </div>
                    <div>
                      <strong>{data.metrics.safety.fallbacks}</strong>
                      <span>Fallbacks</span>
                    </div>
                  </div>
                  <button
                    className="text-button"
                    onClick={() => setTab("Safety monitor")}
                  >
                    Inspect safety decisions <ChevronRight size={14} />
                  </button>
                </section>
              </div>
              <div className="overview-grid lower">
                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Campaigns</h2>
                      <p>Keep your outreach moving.</p>
                    </div>
                    <button
                      className="text-button"
                      onClick={() => setTab("Campaigns")}
                    >
                      Manage campaigns <ArrowUpRight size={15} />
                    </button>
                  </div>
                  <CampaignTable
                    campaigns={data.campaigns}
                    action={action}
                    busy={busy}
                  />
                </section>
                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Provider health</h2>
                      <p>Deterministic telecom sandbox</p>
                    </div>
                    <Activity size={19} />
                  </div>
                  {data.metrics.providers.map((p) => (
                    <div className="provider-row" key={p.name}>
                      <span
                        className={
                          "provider-icon " + (p.name === "B" ? "purple" : "")
                        }
                      >
                        {p.name}
                      </span>
                      <div>
                        <strong>Provider {p.name}</strong>
                        <small>
                          {p.health.latencyMs} ms configured latency
                        </small>
                      </div>
                      <Badge value={p.health.healthy ? "CLOSED" : "OPEN"} />
                    </div>
                  ))}
                  <div className="provider-caption">
                    No real calls. No borrower personal data.
                  </div>
                </section>
              </div>
            </>
          )}
          {data && tab === "Campaigns" && (
            <>
              <section className="panel form-panel">
                <h2>Create a campaign</h2>
                <p>
                  New campaigns include 200 synthetic borrowers. Add agents to
                  begin.
                </p>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    void action("/campaigns", "POST", {
                      name: f.get("name"),
                      mode: f.get("mode"),
                      provider: f.get("provider"),
                      maxActive: Number(f.get("maxActive")),
                      riskLimit: Number(f.get("riskLimit")),
                    });
                  }}
                >
                  <label>
                    Name
                    <input
                      name="name"
                      placeholder="Early resolution"
                      required
                      maxLength={120}
                    />
                  </label>
                  <label>
                    Dialing mode
                    <select name="mode">
                      <option>PROGRESSIVE</option>
                      <option>PREDICTIVE</option>
                    </select>
                  </label>
                  <label>
                    Provider
                    <select name="provider">
                      <option>A</option>
                      <option>B</option>
                    </select>
                  </label>
                  <label>
                    Concurrent call limit
                    <input
                      name="maxActive"
                      type="number"
                      min="1"
                      max="10000"
                      defaultValue="50"
                      required
                    />
                  </label>
                  <label>
                    Risk threshold
                    <input
                      name="riskLimit"
                      type="number"
                      min="0"
                      max="0.1"
                      step="0.005"
                      defaultValue="0.01"
                      required
                    />
                  </label>
                  <button disabled={busy} className="button primary">
                    <Plus size={16} />
                    Create campaign
                  </button>
                </form>
              </section>
              <section className="panel">
                <CampaignTable
                  campaigns={data.campaigns}
                  action={action}
                  busy={busy}
                />
              </section>
            </>
          )}
          {data && tab === "Agents" && (
            <>
              <section className="panel form-panel">
                <div className="panel-heading">
                  <div>
                    <h2>Agent directory</h2>
                    <p>
                      Agents need a heartbeat every 30 seconds. Offline agents
                      must resolve active calls before returning.
                    </p>
                  </div>
                  <button
                    disabled={busy}
                    className="button secondary"
                    onClick={() => void action("/demo/heartbeat")}
                  >
                    Send demo heartbeats
                  </button>
                </div>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    void action("/agents", "POST", {
                      name: f.get("name"),
                      campaignId: f.get("campaign"),
                    });
                  }}
                >
                  <label>
                    Agent name
                    <input name="name" required placeholder="Demo agent" />
                  </label>
                  <label>
                    Campaign
                    <select name="campaign">
                      {data.campaigns.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button disabled={busy} className="button primary">
                    <Plus size={16} />
                    Add agent
                  </button>
                </form>
              </section>
              <AgentTable agents={data.agents} busy={busy} action={action} />
            </>
          )}
          {data && tab === "Live calls" && (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Live call monitor</h2>
                  <p>The latest 300 calls · refreshes every 3 seconds</p>
                </div>
                <span className="pill">{data.calls.length} calls</span>
              </div>
              <CallTable calls={data.calls} />
            </section>
          )}
          {tab === "Simulation lab" && (
            <>
              <section className="lab-banner">
                <div className="lab-icon">
                  <FlaskConical size={26} />
                </div>
                <div>
                  <h2>Test the assumptions. See the tradeoffs.</h2>
                  <p>
                    Both modes use the same seed and operating conditions.
                    Metrics are calculated by the backend.
                  </p>
                </div>
                <span className="pill">Virtual clock</span>
              </section>
              <section className="panel form-panel">
                <h2>Experiment configuration</h2>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    void simulate();
                  }}
                >
                  <label>
                    Scenario
                    <select
                      value={simConfig.scenario}
                      onChange={(e) =>
                        setSimConfig({ ...simConfig, scenario: e.target.value })
                      }
                    >
                      <option value="A">A · 20% answers / 120s talk</option>
                      <option value="B">B · 50% answers / 90s talk</option>
                      <option value="C">C · 70% answers / 180s talk</option>
                      <option value="D">D · Changing + outage</option>
                    </select>
                  </label>
                  {(
                    [
                      "agents",
                      "durationSeconds",
                      "providerLatencyMs",
                      "failureRate",
                      "dropouts",
                      "seed",
                    ] as const
                  ).map((key) => (
                    <label key={key}>
                      {
                        {
                          agents: "Agents",
                          durationSeconds: "Duration (seconds)",
                          providerLatencyMs: "Provider latency (ms)",
                          failureRate: "Failure probability",
                          dropouts: "Midpoint dropouts",
                          seed: "Random seed",
                        }[key]
                      }
                      <input
                        type="number"
                        value={simConfig[key]}
                        min={
                          key === "agents"
                            ? 1
                            : key === "durationSeconds"
                              ? 30
                              : 0
                        }
                        max={
                          key === "agents" || key === "dropouts"
                            ? 500
                            : key === "durationSeconds"
                              ? 7200
                              : key === "providerLatencyMs"
                                ? 30000
                                : key === "failureRate"
                                  ? 1
                                  : undefined
                        }
                        step={key === "failureRate" ? 0.01 : 1}
                        onChange={(e) =>
                          setSimConfig({
                            ...simConfig,
                            [key]: Number(e.target.value),
                          })
                        }
                        required
                      />
                    </label>
                  ))}
                  <button disabled={busy} className="button primary">
                    <Play size={15} />
                    {busy ? "Running…" : "Run comparison"}
                  </button>
                </form>
                <p className="fineprint">
                  Predictive safety is statistical. Correlated answers can
                  exceed the configured bound; progressive mode reserves an
                  agent for each call.
                </p>
              </section>
              {sim ? (
                <>
                  <div className="comparison-grid">
                    {[sim.progressive, sim.predictive].map((m) => (
                      <section className="panel comparison" key={m.mode}>
                        <div className="eyebrow">{m.mode}</div>
                        <strong>{percent(m.agentUtilization)}</strong>
                        <p>Agent utilization over the simulation window</p>
                        <dl>
                          {[
                            ["Initiated", m.callsInitiated],
                            ["Answered", m.callsAnswered],
                            ["Connected", m.callsConnected],
                            ["Failed", m.callsFailed],
                            ["Abandoned", m.abandonedConnectedCalls],
                            ["Abandonment rate", percent(m.abandonmentRate)],
                            [
                              "Safety approvals / rejections",
                              `${m.safetyApprovals} / ${m.safetyRejections}`,
                            ],
                            ["Fallback ticks", m.fallbackEvents],
                            ["Pacing changes", m.pacingChanges],
                            ["Active at cutoff", m.censoredActiveCalls],
                          ].map(([label, value]) => (
                            <div key={label}>
                              <dt>{label}</dt>
                              <dd>{value}</dd>
                            </div>
                          ))}
                        </dl>
                      </section>
                    ))}
                  </div>
                  <section className="panel">
                    <div className="panel-heading">
                      <h2>Connected calls over virtual time</h2>
                      <span className="pill">Actual simulation output</span>
                    </div>
                    <PerformanceChart sim={sim} />
                  </section>
                  {sim.predictive.events.length > 0 && (
                    <section className="panel form-panel">
                      <h2>Disturbance log</h2>
                      {sim.predictive.events.map((e) => (
                        <p key={e} className="event-log">
                          {e}
                        </p>
                      ))}
                    </section>
                  )}
                  <p className="fineprint">
                    Run ID: {sim.id}. Calls still active at cutoff are reported
                    separately; no synthetic completion is added.
                  </p>
                </>
              ) : (
                <div className="empty">
                  <FlaskConical size={28} />
                  <h3>Ready when you are.</h3>
                  <p>
                    Choose your conditions and run a reproducible experiment.
                  </p>
                </div>
              )}
            </>
          )}
          {data && tab === "Safety monitor" && (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Decision audit trail</h2>
                  <p>
                    Every proposal and its approved capacity are persisted
                    together.
                  </p>
                </div>
                <ShieldCheck size={21} />
              </div>
              <DecisionTable decisions={data.decisions} />
            </section>
          )}
          {data && tab === "Providers" && (
            <>
              <div className="comparison-grid">
                {data.metrics.providers.map((p) => (
                  <section className="panel form-panel" key={p.name}>
                    <div className="panel-heading">
                      <h2>Provider {p.name}</h2>
                      <Badge value={p.health.healthy ? "CLOSED" : "OPEN"} />
                    </div>
                    <p>
                      {p.name === "A"
                        ? "Fast responses and ordered callbacks."
                        : "Slower responses, ambiguous timeouts, duplicates, and out-of-order callbacks."}
                    </p>
                    <form
                      onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        void action(`/providers/${p.name}/mock`, "PATCH", {
                          outage: f.get("outage") === "true",
                          latencyMs: Number(f.get("latency")),
                          failureRate: Number(f.get("failure")),
                          seed: Number(f.get("seed")),
                        });
                      }}
                    >
                      <label>
                        Availability
                        <select
                          name="outage"
                          defaultValue={p.health.healthy ? "false" : "true"}
                        >
                          <option value="false">Online</option>
                          <option value="true">Simulate outage</option>
                        </select>
                      </label>
                      <label>
                        Latency (ms)
                        <input
                          name="latency"
                          type="number"
                          min="0"
                          max="30000"
                          defaultValue={p.health.latencyMs}
                        />
                      </label>
                      <label>
                        Failure probability
                        <input
                          name="failure"
                          type="number"
                          min="0"
                          max="1"
                          step="0.01"
                          defaultValue={p.health.failureRate}
                        />
                      </label>
                      <label>
                        Seed
                        <input name="seed" type="number" defaultValue="42" />
                      </label>
                      <button className="button primary" disabled={busy}>
                        Apply mock settings
                      </button>
                    </form>
                  </section>
                ))}
              </div>
              <section className="panel form-panel">
                <h2>Recovery control</h2>
                <p>
                  Reconcile provider status, expire stale agent availability,
                  and process one durable dispatch.
                </p>
                <button
                  className="button secondary"
                  disabled={busy}
                  onClick={() => void action("/operations/recover")}
                >
                  <RefreshCw size={15} />
                  Run recovery cycle
                </button>
              </section>
            </>
          )}
          <footer>
            <span>
              <AudioLines size={13} /> CredResolve SmartDialer
            </span>
            <span>PostgreSQL-backed operations · Synthetic data only</span>
          </footer>
        </div>
      </main>
    </div>
  );
}
