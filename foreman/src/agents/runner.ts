import type { z } from 'zod';

/** A team tool exposed to the agent through the in-process MCP server "agentcraft". */
export interface ToolDef {
  name: string;
  description: string;
  shape: z.ZodRawShape;
  handler: (args: Record<string, unknown>) => Promise<string>;
}

export type Permission = { allow: true } | { allow: false; message: string };

export interface ActivityEvent {
  tool?: string;
  input?: Record<string, unknown>;
  text?: string;
}

export interface TurnSpec {
  agentId: string;
  prompt: string;
  systemPrompt: string;
  cwd: string;
  model: string;
  effort: 'low' | 'medium' | 'high' | 'xhigh' | 'max';
  maxTurns: number;
  maxBudgetUsd?: number;
  resume?: string;
  env: NodeJS.ProcessEnv;
  tools: ToolDef[];
  /** every non-team tool call goes through here (the policy, possibly an in-game decision) */
  canUseTool: (tool: string, input: Record<string, unknown>) => Promise<Permission>;
  signal: AbortSignal;
  onActivity: (ev: ActivityEvent) => void;
}

export interface TurnResult {
  ok: boolean;
  sessionId?: string | undefined;
  costUsd: number;
  text: string;
  error?: string;
}

/** Runs one agent turn. ClaudeRunner uses the Claude Agent SDK; tests use a scripted runner. */
export interface Runner {
  run(spec: TurnSpec): Promise<TurnResult>;
}

export const MCP_SERVER = 'agentcraft';
