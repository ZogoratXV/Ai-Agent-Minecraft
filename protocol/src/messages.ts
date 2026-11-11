import { z } from 'zod';
import { Id, message, Ts, Uuid } from './common.js';
import {
  Actor,
  Agent,
  AgentRole,
  Capabilities,
  Decision,
  FeedItem,
  ForemanStatus,
  Goal,
  Limits,
  Repo,
  ServerInfo,
  Task,
} from './entities.js';
import { JobState, ToolError, ToolInvocation } from './tools.js';

// ---------------------------------------------------------------- plugin -> foreman

export const Hello = message('hello', 'First message after connecting. The foreman answers with `snapshot`.', {
  token: z.string().min(16).max(256).describe('shared secret; also sent as `Authorization: Bearer`'),
  pluginVersion: z.string().max(32),
  server: ServerInfo,
  capabilities: Capabilities,
  limits: Limits,
});

export const GoalSubmit = message('goal.submit', 'New goal for the lead (`/agent goal <text>`).', {
  actor: Actor,
  text: z.string().min(1).max(2000),
});

export const UserMessage = message('user.message', 'Message to one agent or to everyone (`/agent @<name> ...`).', {
  actor: Actor,
  to: z.string().min(1).max(64).describe('agent id or "all"'),
  text: z.string().min(1).max(2000),
});

export const DecisionAnswerMsg = message(
  'decision.answer',
  'Answer to an open decision (`/agent answer`, approve, reject).',
  {
    actor: Actor,
    decisionId: Id,
    option: z.string().max(100).optional(),
    text: z.string().max(2000).optional(),
  },
);

export const TaskAction = message('task.action', 'Steer a task (`/agent task <id> cancel|retry|reassign <name>`).', {
  actor: Actor,
  taskId: Id,
  action: z.enum(['cancel', 'retry', 'reassign', 'prioritize']),
  agentId: Id.optional().describe('reassign target'),
  priority: z.number().int().optional(),
});

export const AgentAction = message('agent.action', 'Steer an agent (`/agent pause|resume|stop|spawn`).', {
  actor: Actor,
  agentId: Id.describe('agent id, or "all" for pause/resume/stop'),
  action: z.enum(['pause', 'resume', 'stop', 'spawn']),
  role: AgentRole.optional().describe('spawn: role of a new agent'),
});

export const DiffRequest = message('diff.request', 'Ask for the diff of a task (`/agent diff <task>`).', {
  actor: Actor,
  taskId: Id,
});

export const RepoAdd = message('repo.add', 'Register a plugin repository (`/agent repo add <path>`).', {
  actor: Actor,
  path: z.string().min(1).max(500),
});

export const RepoPush = message(
  'repo.push',
  'Publish the base branch of a repository to its remote (`/agent push`). Only people can push: agents have no git network access.',
  {
    actor: Actor,
    repoId: Id.optional().describe('default: the first registered repository'),
  },
);

export const ToolResult = message('tool.result', 'Answer to `tool.call`.', {
  re: Id.describe('id of the tool.call'),
  ok: z.boolean(),
  result: z.unknown().optional().describe('tool-specific result when ok'),
  error: ToolError.optional(),
});

export const JobProgress = message('job.progress', 'Progress of a long world job (world.place, world.undo).', {
  jobId: Id,
  state: JobState,
  done: z.number().int().nonnegative(),
  total: z.number().int().nonnegative(),
  message: z.string().max(500).optional(),
});

export const WorldEvent = message('world.event', 'Something agents may care about (already filtered by the plugin).', {
  kind: z.enum(['console_error', 'plugin_error', 'player_join', 'player_quit', 'region_enter', 'tps_low']),
  text: z.string().max(1000),
  ts: Ts,
  player: Uuid.optional(),
});

export const PluginToForeman = z.discriminatedUnion('type', [
  Hello,
  GoalSubmit,
  UserMessage,
  DecisionAnswerMsg,
  TaskAction,
  AgentAction,
  DiffRequest,
  RepoAdd,
  RepoPush,
  ToolResult,
  JobProgress,
  WorldEvent,
]);
export type PluginToForeman = z.infer<typeof PluginToForeman>;

// ---------------------------------------------------------------- foreman -> plugin

export const Snapshot = message(
  'snapshot',
  'Full state, sent after a valid `hello`. Replaces everything the plugin knows.',
  {
    foreman: ForemanStatus,
    agents: z.array(Agent),
    tasks: z.array(Task),
    decisions: z.array(Decision),
    goals: z.array(Goal),
    repos: z.array(Repo).optional(),
    feed: z.array(FeedItem).max(200).describe('most recent last'),
  },
);

export const AgentUpsert = message('agent.upsert', 'Replace one agent by id.', { agent: Agent });
export const TaskUpsert = message('task.upsert', 'Replace one task by id.', { task: Task });
export const DecisionUpsert = message('decision.upsert', 'Replace one decision by id.', { decision: Decision });
export const GoalUpsert = message('goal.upsert', 'Replace one goal by id.', { goal: Goal });
export const RepoUpsert = message('repo.upsert', 'Replace one repository by id.', { repo: Repo });
export const FeedAdd = message('feed.add', 'Append to the activity feed.', { item: FeedItem });
export const ForemanStatusMsg = message('foreman.status', 'Backend, auth, pause and budget status.', {
  status: ForemanStatus,
});

export const Notify = message('notify', 'Tell admins something (clickable chat message, optional Discord).', {
  level: z.enum(['info', 'warn', 'need_user']),
  text: z.string().max(500),
  decisionId: Id.optional().describe('makes the chat message clickable'),
});

export const Diff = message('diff', 'Answer to `diff.request`: short summary for chat plus where the full diff is.', {
  taskId: Id,
  summary: z.string().max(2000),
  files: z.number().int().nonnegative(),
  additions: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
  path: z.string().max(500).optional().describe('path of the full .diff file on the foreman machine'),
});

export const ToolCall = message(
  'tool.call',
  'Ask the plugin to run a world tool. Always answered with `tool.result`.',
  {
    id: Id,
    agentId: Id,
    taskId: Id.optional(),
    requestedBy: z.string().max(64).describe('who started the work: "user:<name>" or an agent id'),
    deadlineMs: z.number().int().min(100).max(600000),
    call: ToolInvocation,
  },
);

export const JobControl = message('job.control', 'Pause, resume or cancel a running world job.', {
  jobId: Id,
  action: z.enum(['pause', 'resume', 'cancel']),
});

export const Ack = message('ack', 'Answer to any message that carried an `id`.', {
  re: Id,
  ok: z.boolean(),
  error: z.string().max(1000).optional(),
  result: z.unknown().optional(),
});

export const ErrorMsg = message('error', 'Protocol error (invalid message, bad token, version mismatch).', {
  code: z.enum(['invalid_message', 'unauthorized', 'version_mismatch', 'internal']),
  message: z.string().max(1000),
});

export const ForemanToPlugin = z.discriminatedUnion('type', [
  Snapshot,
  AgentUpsert,
  TaskUpsert,
  DecisionUpsert,
  GoalUpsert,
  RepoUpsert,
  FeedAdd,
  ForemanStatusMsg,
  Notify,
  Diff,
  ToolCall,
  JobControl,
  Ack,
  ErrorMsg,
]);
export type ForemanToPlugin = z.infer<typeof ForemanToPlugin>;

export const PLUGIN_TO_FOREMAN_TYPES = PluginToForeman.options.map((o) => o.shape.type.value);
export const FOREMAN_TO_PLUGIN_TYPES = ForemanToPlugin.options.map((o) => o.shape.type.value);
