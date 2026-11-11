import type { ToolInvocation } from '@agentcraft-server/protocol';
import type { ToolOutcome } from './bridge.js';
import type { DecisionT, GoalT } from './state.js';

/** How agents reach the world. The bridge implements it; tests use a fake. */
export interface ToolPort {
  callTool(
    agentId: string,
    call: ToolInvocation,
    opts?: { requestedBy: string; taskId?: string; deadlineMs?: number },
  ): Promise<ToolOutcome>;
}

/** The "brain": the scripted sim now, Claude agents in phase 4. */
export interface Backend {
  start(): void;
  stop(): void;
  onGoal(goal: GoalT): void;
  onUserMessage(to: string, text: string, from: string): void;
  onDecisionAnswered(decision: DecisionT): void;
  /** after the Foreman applied an agent action (pause, resume, stop, spawn) */
  onAgentAction?(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn'): void;
  /** after the Foreman applied a task action */
  onTaskAction?(taskId: string, action: 'cancel' | 'retry' | 'reassign' | 'prioritize'): void;
  onRepoAdd?(path: string): Promise<unknown>;
  onDiff?(taskId: string): Promise<unknown>;
}
