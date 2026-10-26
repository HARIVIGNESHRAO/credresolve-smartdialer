"use client";
import { ArrowDownRight, Zap, Square, Play, Phone } from "lucide-react";
import type { Campaign, Agent, Call, Decision } from "@/types";
const percent = (n: number) => `${(n * 100).toFixed(1)}%`;
export function Badge({ value }: { value: string }) {
  return (
    <span
      className={
        "badge " +
        ([
          "AVAILABLE",
          "RUNNING",
          "CONNECTED",
          "APPROVE",
          "CLOSED",
          "COMPLETED",
        ].includes(value)
          ? "good"
          : ["FAILED", "REJECT", "OFFLINE", "OPEN"].includes(value)
            ? "muted"
            : "amber")
      }
    >
      {value.replaceAll("_", " ")}
    </span>
  );
}
export function CampaignTable({
  campaigns,
  action,
  busy,
}: {
  campaigns: Campaign[];
  action: (p: string, m?: string, b?: unknown) => Promise<void>;
  busy: boolean;
}) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Campaign</th>
            <th>Mode</th>
            <th>Status</th>
            <th>Limit</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {campaigns.map((c) => (
            <tr key={c.id}>
              <td>
                <strong>{c.name}</strong>
                <small>
                  Provider {c.provider} · Risk {percent(c.riskLimit)}
                </small>
              </td>
              <td>
                {c.mode === "PROGRESSIVE" ? (
                  <span className="mode-label">
                    <ArrowDownRight size={13} />
                    Progressive
                  </span>
                ) : (
                  <span className="mode-label">
                    <Zap size={13} />
                    Predictive
                  </span>
                )}
              </td>
              <td>
                <Badge value={c.status} />
              </td>
              <td>{c.maxActive}</td>
              <td>
                <button
                  className="icon-button"
                  disabled={busy}
                  onClick={() =>
                    void action(
                      `/campaigns/${c.id}/${c.status === "RUNNING" ? "stop" : "start"}`,
                    )
                  }
                  aria-label={`${c.status === "RUNNING" ? "Stop" : "Start"} ${c.name}`}
                >
                  {c.status === "RUNNING" ? (
                    <Square size={14} />
                  ) : (
                    <Play size={14} />
                  )}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!campaigns.length && (
        <div className="empty">
          No campaigns yet. Create one to get started.
        </div>
      )}
    </div>
  );
}
export function AgentTable({
  agents,
  action,
  busy,
}: {
  agents: Agent[];
  action: (p: string, m?: string, b?: unknown) => Promise<void>;
  busy: boolean;
}) {
  return (
    <section className="panel table-scroll">
      <table>
        <thead>
          <tr>
            <th>Agent</th>
            <th>State</th>
            <th>Assigned call</th>
            <th>Heartbeat</th>
            <th>Availability</th>
          </tr>
        </thead>
        <tbody>
          {agents.map((a) => (
            <tr key={a.id}>
              <td>
                <span className="agent-label">
                  <span className="avatar">
                    {a.name.slice(0, 2).toUpperCase()}
                  </span>
                  {a.name}
                </span>
              </td>
              <td>
                <Badge value={a.state} />
              </td>
              <td>
                <code>{a.current_call_id?.slice(0, 8) || "—"}</code>
              </td>
              <td>{new Date(a.last_heartbeat).toLocaleTimeString()}</td>
              <td>
                <select
                  aria-label={`Availability for ${a.name}`}
                  value=""
                  disabled={busy}
                  onChange={(e) =>
                    void action(`/agents/${a.id}/status`, "PATCH", {
                      status: e.target.value,
                    })
                  }
                >
                  <option value="" disabled>
                    Change state
                  </option>
                  <option>AVAILABLE</option>
                  <option>PAUSED</option>
                  <option>OFFLINE</option>
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!agents.length && (
        <div className="empty">Add your first agent above.</div>
      )}
    </section>
  );
}
export function CallTable({ calls }: { calls: Call[] }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Call ID</th>
            <th>Borrower</th>
            <th>Agent</th>
            <th>Provider</th>
            <th>State</th>
            <th>Duration</th>
          </tr>
        </thead>
        <tbody>
          {calls.map((c) => (
            <tr key={c.id}>
              <td>
                <code>{c.id.slice(0, 8)}</code>
              </td>
              <td>{c.borrower_name}</td>
              <td>{c.agent_name || "Awaiting allocation"}</td>
              <td>{c.provider}</td>
              <td>
                <Badge value={c.state} />
              </td>
              <td>{Math.floor(c.duration_seconds)}s</td>
            </tr>
          ))}
        </tbody>
      </table>
      {!calls.length && (
        <div className="empty">
          <Phone size={27} />
          <h3>No calls yet</h3>
          <p>
            Make agents available, send demo heartbeats, then start a campaign.
          </p>
        </div>
      )}
    </div>
  );
}
export function DecisionTable({ decisions }: { decisions: Decision[] }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Decision</th>
            <th>Requested → approved</th>
            <th>Capacity / exposure</th>
            <th>Estimated risk</th>
            <th>Reason</th>
          </tr>
        </thead>
        <tbody>
          {decisions.map((d) => (
            <tr key={d.id}>
              <td>{new Date(d.created_at).toLocaleTimeString()}</td>
              <td>
                <Badge value={d.action} />
              </td>
              <td>
                {d.requested} → {d.approved}
              </td>
              <td>
                {d.capacity} / {d.exposure}
              </td>
              <td>{percent(d.risk)}</td>
              <td className="reason">{d.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {!decisions.length && (
        <div className="empty">
          No decisions yet. Start a campaign to see its safety audit.
        </div>
      )}
    </div>
  );
}
