import type { AgentRole, ToolName } from '@agentcraft-server/protocol';
import type { z } from 'zod';

export type Role = z.infer<typeof AgentRole>;
export type ModelTier = 'lead' | 'worker' | 'light';

/** Coordination tools of the internal MCP server (Phase 4). */
export const COORDINATION_TOOLS = [
  'send_message',
  'ask_user',
  'read_memory',
  'write_memory',
  'list_tasks',
  'update_task',
  'report_status',
] as const;
export const LEAD_ONLY_TOOLS = ['create_task', 'request_merge', 'request_deploy', 'request_build'] as const;

export interface RoleSpec {
  tier: ModelTier;
  /** works on code in its own git worktree */
  code: boolean;
  /** world tools it may ask the plugin for (the plugin still validates every call) */
  worldTools: readonly ToolName[];
  leadTools: boolean;
}

const READ_WORLD: readonly ToolName[] = ['world.inspect', 'server.logs', 'server.metrics', 'body.move', 'body.say'];

export const ROLES: Record<Role, RoleSpec> = {
  lead: { tier: 'lead', code: false, worldTools: READ_WORLD, leadTools: true },
  developer: { tier: 'worker', code: true, worldTools: ['body.move', 'body.say'], leadTools: false },
  qa: {
    tier: 'worker',
    code: true,
    worldTools: [...READ_WORLD, 'server.command', 'player.simulate'],
    leadTools: false,
  },
  architect: { tier: 'lead', code: false, worldTools: [...READ_WORLD, 'world.preview'], leadTools: false },
  builder: {
    tier: 'worker',
    code: false,
    worldTools: [...READ_WORLD, 'world.place', 'world.undo'],
    leadTools: false,
  },
  analyst: { tier: 'worker', code: false, worldTools: READ_WORLD, leadTools: false },
};

export function toolsFor(role: Role): string[] {
  const spec = ROLES[role];
  return [...COORDINATION_TOOLS, ...(spec.leadTools ? LEAD_ONLY_TOOLS : []), ...spec.worldTools];
}
