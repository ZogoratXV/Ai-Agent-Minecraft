import type { z } from 'zod';
import type { Agent, Decision, Task } from './entities.js';
import type { ForemanToPlugin, PluginToForeman } from './messages.js';

/**
 * One example per message type. They are written to protocol/examples/<type>.json and used as
 * contract fixtures by both the foreman tests and the plugin's Java tests.
 */

const T0 = 1_762_862_400_000; // 2025-11-11T12:00:00Z, fixed so the fixtures never change by themselves
const actor = { uuid: '2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b', name: 'Zogorat' };

const agent: z.infer<typeof Agent> = {
  id: 'briciola',
  name: 'Briciola',
  role: 'qa',
  title: 'Cacciatrice di bug',
  color: '#8fa98b',
  state: 'testing',
  activity: 'testing EconomyPlus',
  station: 'testbench',
  taskId: 't4',
  paused: false,
  active: true,
  model: 'Sonnet 5.5',
  costUsd: 0.42,
};

const task: z.infer<typeof Task> = {
  id: 't4',
  title: 'Test del comando /shop sell',
  kind: 'qa',
  status: 'doing',
  assignee: 'briciola',
  deps: ['t2'],
  repoId: 'castigo-ecosistema',
  goalId: 'g1',
  priority: 0,
  branch: 'agentcraft/briciola/t4-shop-sell-tests',
  module: 'modules/CastigoBanca-2.7.0-alpha.2',
  ci: 'running',
  createdBy: 'bussola',
  createdAt: T0,
  updatedAt: T0 + 60_000,
};

const decision: z.infer<typeof Decision> = {
  id: 'd3',
  agentId: 'bussola',
  kind: 'merge',
  question: 'Unire la correzione di /shop sell in main?',
  options: ['Merge', 'Request changes', 'Reject'],
  context: '2 file, +41 -7. Test: 18 passati.',
  status: 'open',
  taskId: 't2',
  createdAt: T0 + 120_000,
};

const repo = {
  id: 'castigo-ecosistema',
  name: 'castigo-ecosistema',
  path: '/srv/agentcraft/repos/castigo-ecosistema',
  branch: 'main',
  head: '12835d8',
  dirty: false,
  versioning: 'new-folder' as const,
};

export const EXAMPLES: Record<string, PluginToForeman | ForemanToPlugin> = {
  hello: {
    v: 1,
    type: 'hello',
    token: 'example-token-not-a-secret-0001',
    pluginVersion: '0.1.0',
    server: { id: 'staging', role: 'staging', mcVersion: '26.2', software: 'Purpur 26.2 build 2633' },
    capabilities: { citizens: true, worldguard: true, worldedit: true, luckperms: true, spark: false },
    limits: { blocksPerTick: 200, maxJobVolume: 200000, maxInspectVolume: 65536, toolCallsPerMinute: 60 },
  },
  'goal.submit': { v: 1, type: 'goal.submit', id: 'c1', actor, text: 'Trova e correggi i bug di EconomyPlus' },
  'user.message': { v: 1, type: 'user.message', id: 'c2', actor, to: 'briciola', text: 'Prova anche con 0 oggetti' },
  'decision.answer': { v: 1, type: 'decision.answer', id: 'c3', actor, decisionId: 'd3', option: 'Merge' },
  'task.action': { v: 1, type: 'task.action', id: 'c4', actor, taskId: 't4', action: 'reassign', agentId: 'lucciola' },
  'agent.action': { v: 1, type: 'agent.action', id: 'c5', actor, agentId: 'mattone', action: 'pause' },
  'diff.request': { v: 1, type: 'diff.request', id: 'c6', actor, taskId: 't2' },
  'repo.add': { v: 1, type: 'repo.add', id: 'c7', actor, path: '/srv/agentcraft/repos/castigo-ecosistema' },
  'repo.push': { v: 1, type: 'repo.push', id: 'c8', actor, repoId: 'castigo-ecosistema' },
  'tool.result': {
    v: 1,
    type: 'tool.result',
    re: 'f12',
    ok: false,
    error: { code: 'out_of_region', message: 'block 120,64,-30 is outside region "piazza"' },
  },
  'job.progress': { v: 1, type: 'job.progress', jobId: 'j7', state: 'running', done: 1400, total: 5200 },
  'world.event': {
    v: 1,
    type: 'world.event',
    kind: 'plugin_error',
    text: '[EconomyPlus] NullPointerException in ShopCommand',
    ts: T0,
  },

  snapshot: {
    v: 1,
    type: 'snapshot',
    foreman: {
      version: '0.1.0',
      backend: 'sim',
      auth: 'ok',
      paused: false,
      budget: { spentTodayUsd: 1.2, dailyUsd: 15, spentMonthUsd: 31.5, monthlyUsd: 300 },
    },
    agents: [agent],
    tasks: [task],
    decisions: [decision],
    goals: [
      {
        id: 'g1',
        text: 'Trova e correggi i bug di EconomyPlus',
        status: 'active',
        progress: 0.4,
        createdBy: 'user:Zogorat',
        createdAt: T0,
        updatedAt: T0,
      },
    ],
    repos: [repo],
    feed: [{ ts: T0, kind: 'goal', text: 'Nuovo obiettivo da Zogorat', to: 'all' }],
  },
  'repo.upsert': { v: 1, type: 'repo.upsert', repo: { ...repo, dirty: true } },
  'agent.upsert': { v: 1, type: 'agent.upsert', agent },
  'task.upsert': { v: 1, type: 'task.upsert', task },
  'decision.upsert': { v: 1, type: 'decision.upsert', decision },
  'goal.upsert': {
    v: 1,
    type: 'goal.upsert',
    goal: {
      id: 'g1',
      text: 'Trova e correggi i bug di EconomyPlus',
      status: 'active',
      progress: 0.5,
      createdBy: 'user:Zogorat',
      createdAt: T0,
      updatedAt: T0 + 300_000,
    },
  },
  'feed.add': {
    v: 1,
    type: 'feed.add',
    item: { ts: T0, kind: 'ci', text: 'Test di t2: 18 passati', agentId: 'lucciola' },
  },
  'foreman.status': {
    v: 1,
    type: 'foreman.status',
    status: { version: '0.1.0', backend: 'claude', auth: 'ok', paused: false },
  },
  notify: { v: 1, type: 'notify', level: 'need_user', text: 'Bussola chiede di unire t2', decisionId: 'd3' },
  diff: {
    v: 1,
    type: 'diff',
    taskId: 't2',
    summary: 'Corretto il calcolo del prezzo di vendita',
    files: 2,
    additions: 41,
    deletions: 7,
    path: '/srv/agentcraft/diffs/t2.diff',
  },
  'tool.call': {
    v: 1,
    type: 'tool.call',
    id: 'f12',
    agentId: 'mattone',
    taskId: 't9',
    requestedBy: 'user:Zogorat',
    deadlineMs: 30000,
    call: {
      tool: 'world.place',
      args: {
        jobId: 'j7',
        approvalId: 'd5',
        region: 'piazza',
        anchor: { world: 'world', x: 100, y: 64, z: -40 },
        rotation: 0,
        ops: [{ op: 'fill', from: [0, 0, 0], to: [8, 0, 8], block: 'minecraft:stone_bricks' }],
      },
    },
  },
  'job.control': { v: 1, type: 'job.control', jobId: 'j7', action: 'pause' },
  ack: { v: 1, type: 'ack', re: 'c1', ok: true, result: { goalId: 'g1' } },
  error: { v: 1, type: 'error', code: 'unauthorized', message: 'invalid token' },
};
