export type Campaign = {
  id: string;
  name: string;
  mode: string;
  status: string;
  provider: string;
  maxActive: number;
  riskLimit: number;
  answerRate: number;
};
export type Agent = {
  id: string;
  name: string;
  campaign_id: string;
  state: string;
  current_call_id: string | null;
  last_heartbeat: string;
};
export type Call = {
  id: string;
  borrower_name: string;
  agent_name: string | null;
  provider: string;
  state: string;
  duration_seconds: number;
  created_at: string;
};
export type Provider = {
  name: string;
  health: {
    healthy: boolean;
    latencyMs: number;
    failureRate: number;
    circuit: string;
  };
};
export type Decision = {
  id: string;
  action: string;
  approved: number;
  requested: number;
  capacity: number;
  exposure: number;
  risk: number;
  reason: string;
  mode: string;
  created_at: string;
};
export type Metrics = {
  agents: {
    total: number;
    available: number;
    reserved: number;
    connected: number;
  };
  calls: {
    total: number;
    active: number;
    completed: number;
    answered: number;
    abandoned: number;
  };
  answerRate: number;
  agentUtilization: number;
  abandonmentRate: number;
  providers: Provider[];
  safety: { approvals: number; rejections: number; fallbacks: number };
};
export type SimulationMetrics = {
  mode: string;
  callsInitiated: number;
  callsAnswered: number;
  callsConnected: number;
  callsFailed: number;
  agentUtilization: number;
  averageWaitingSeconds: number;
  abandonedConnectedCalls: number;
  abandonmentRate: number;
  safetyApprovals: number;
  safetyRejections: number;
  fallbackEvents: number;
  pacingChanges: number;
  censoredActiveCalls: number;
  timeline: {
    second: number;
    initiated: number;
    connected: number;
    active: number;
    capacity: number;
    approved: number;
    answerEstimate: number;
  }[];
  events: string[];
};
export type SimulationResult = {
  id: string;
  progressive: SimulationMetrics;
  predictive: SimulationMetrics;
};
