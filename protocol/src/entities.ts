import { z } from 'zod';
import { Color, Id, Ts, Uuid } from './common.js';

export const AgentRole = z
  .enum(['lead', 'developer', 'qa', 'architect', 'builder', 'analyst'])
  .describe('what the agent is for; decides its tools and limits');

export const AgentState = z
  .enum([
    'idle',
    'thinking',
    'reading',
    'editing',
    'running',
    'testing',
    'building',
    'inspecting',
    'walking',
    'waiting_user',
    'blocked',
    'done',
    'error',
  ])
  .describe('what the agent is doing right now; drives the status line and animation');

export const Station = z
  .enum(['base', 'desk', 'library', 'terminal', 'testbench', 'podium', 'worksite', 'lounge', 'user'])
  .describe('where the NPC should be. `podium` = waiting for a decision; `worksite` = at a build job');

export const Skin = z
  .object({
    name: z.string().min(1).max(16).optional().describe('player name whose skin is used'),
    texture: z.string().max(4096).optional().describe('base64 textures property value'),
    signature: z.string().max(4096).optional().describe('signature of the textures property'),
  })
  .describe('NPC skin; empty = the default Mannequin skin');

export const Agent = z.object({
  id: Id,
  name: z.string().min(1).max(32),
  role: AgentRole,
  title: z.string().max(48).optional().describe('short descriptive title, e.g. "Cacciatrice di bug"'),
  color: Color,
  skin: Skin.optional(),
  state: AgentState,
  activity: z.string().max(64).describe('one short line for the status above the head'),
  station: Station,
  taskId: Id.optional(),
  paused: z.boolean(),
  active: z.boolean().describe('false = off shift'),
  model: z.string().max(48).optional().describe('model it runs, for display'),
  costUsd: z.number().nonnegative().optional().describe('spend of this agent so far'),
});

export const TaskStatus = z.enum(['todo', 'doing', 'review', 'done', 'blocked', 'cancelled']);
export const TaskKind = z.enum(['code', 'qa', 'build', 'analysis', 'generic']);
export const CiStatus = z.enum(['unknown', 'running', 'pass', 'fail']);

export const Task = z.object({
  id: Id,
  title: z.string().min(1).max(120),
  description: z.string().max(4000).optional(),
  kind: TaskKind,
  status: TaskStatus,
  assignee: Id.optional().describe('agent id'),
  deps: z.array(Id).describe('task ids that must be done first'),
  repoId: Id.optional(),
  goalId: Id.optional(),
  priority: z.number().int().describe('higher = sooner; default 0'),
  branch: z.string().max(200).optional().describe('e.g. "agentcraft/lucciola/t2-fix-shop"'),
  module: z
    .string()
    .max(300)
    .optional()
    .describe('folder inside the repo the task works on, e.g. "modules/CastigoBanca-2.7.0-alpha.2"'),
  ci: CiStatus,
  blockedReason: z.string().max(500).optional(),
  summary: z.string().max(4000).optional(),
  createdBy: z.string().max(64).describe('agent id or "user:<name>"'),
  createdAt: Ts,
  updatedAt: Ts,
});

export const DecisionKind = z
  .enum(['question', 'permission', 'merge', 'deploy', 'build', 'config'])
  .describe('merge/deploy/build/config always need a human with the matching permission');
export const DecisionStatus = z.enum(['open', 'answered', 'cancelled']);

export const DecisionAnswer = z.object({
  option: z.string().max(100).optional(),
  text: z.string().max(2000).optional(),
  by: z.string().max(64).describe('player name that answered'),
  ts: Ts,
});

export const Decision = z.object({
  id: Id,
  agentId: Id,
  kind: DecisionKind,
  question: z.string().min(1).max(500),
  options: z.array(z.string().min(1).max(100)).max(8).describe('labels; the first is the recommended one'),
  context: z.string().max(4000).optional(),
  status: DecisionStatus,
  answer: DecisionAnswer.optional(),
  taskId: Id.optional(),
  createdAt: Ts,
});

export const Repo = z.object({
  id: Id,
  name: z.string().max(64),
  path: z.string().max(500).describe('checkout on the foreman machine'),
  branch: z.string().max(200).describe('base branch agents start from and merge into'),
  head: z.string().max(40).optional().describe('short sha of the base branch'),
  dirty: z.boolean().describe('the checkout has uncommitted changes: merges are refused'),
  versioning: z.enum(['in-place', 'new-folder']).describe('new-folder: every change goes into a new version folder'),
});

export const GoalStatus = z.enum(['planning', 'active', 'done', 'failed', 'cancelled']);

export const Goal = z.object({
  id: Id,
  text: z.string().min(1).max(2000),
  status: GoalStatus,
  progress: z.number().min(0).max(1),
  createdBy: z.string().max(64),
  createdAt: Ts,
  updatedAt: Ts,
});

export const FeedKind = z.enum([
  'goal',
  'plan',
  'task',
  'message',
  'decision',
  'merge',
  'ci',
  'build',
  'system',
  'error',
]);

export const FeedItem = z.object({
  ts: Ts,
  kind: FeedKind,
  text: z.string().max(1000),
  agentId: Id.optional(),
  to: z.string().max(64).optional().describe('agent id, "user" or "all"'),
});

export const BackendName = z.enum(['sim', 'claude']);
export const AuthStatus = z.enum(['ok', 'failed', 'unknown', 'checking']);

export const Budget = z.object({
  spentTodayUsd: z.number().nonnegative(),
  dailyUsd: z.number().positive().optional(),
  spentMonthUsd: z.number().nonnegative(),
  monthlyUsd: z.number().positive().optional(),
});

export const ForemanStatus = z.object({
  version: z.string().max(32),
  backend: BackendName,
  auth: AuthStatus,
  paused: z.boolean().describe('every agent paused by an admin'),
  message: z.string().max(200).optional(),
  budget: Budget.optional(),
});

export const Actor = z
  .object({
    uuid: Uuid,
    name: z.string().min(1).max(16),
  })
  .describe('the player who asked; the plugin has already checked their permission, the foreman logs it');

export const ServerRole = z
  .enum(['production', 'staging'])
  .describe('decided only by the plugin config; staging-only tools are refused on production');

export const ServerInfo = z.object({
  id: Id.describe('configured server id, e.g. "survival" or "staging"'),
  role: ServerRole,
  mcVersion: z.string().max(32),
  software: z.string().max(64).describe('e.g. "Purpur 26.2 build 2633"'),
});

export const Capabilities = z.object({
  citizens: z.boolean(),
  worldguard: z.boolean(),
  worldedit: z.boolean(),
  luckperms: z.boolean(),
  spark: z.boolean(),
});

export const Limits = z.object({
  blocksPerTick: z.number().int().positive(),
  maxJobVolume: z.number().int().positive().describe('max blocks in one world.place job'),
  maxInspectVolume: z.number().int().positive(),
  toolCallsPerMinute: z.number().int().positive().describe('per agent'),
});
