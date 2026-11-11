import { EventEmitter } from 'node:events';
import type {
  Repo,
  Agent,
  Decision,
  FeedItem,
  ForemanStatus,
  ForemanToPlugin,
  Goal,
  Task,
} from '@agentcraft-server/protocol';
import type { z } from 'zod';
import type { Clock } from './clock.js';

export type AgentT = z.infer<typeof Agent>;
export type TaskT = z.infer<typeof Task>;
export type DecisionT = z.infer<typeof Decision>;
export type GoalT = z.infer<typeof Goal>;
export type FeedItemT = z.infer<typeof FeedItem>;
export type ForemanStatusT = z.infer<typeof ForemanStatus>;
type SnapshotMsg = Extract<ForemanToPlugin, { type: 'snapshot' }>;

const FEED_LIMIT = 200;

export interface TeamData {
  agents: AgentT[];
  tasks: TaskT[];
  decisions: DecisionT[];
  goals: GoalT[];
  feed: FeedItemT[];
  counters: { t: number; d: number; g: number };
}

/**
 * All team state. Every change emits the protocol message that describes it ('message' event),
 * so the bridge only has to broadcast. Persistence to disk arrives with the real agents (phase 4).
 */
export class TeamState extends EventEmitter<{ message: [ForemanToPlugin] }> {
  readonly agents = new Map<string, AgentT>();
  readonly tasks = new Map<string, TaskT>();
  readonly decisions = new Map<string, DecisionT>();
  readonly goals = new Map<string, GoalT>();
  readonly feed: FeedItemT[] = [];
  private counters = { t: 0, d: 0, g: 0 };
  /** repositories in the snapshot, provided by the backend */
  reposView: () => z.infer<typeof Repo>[] = () => [];

  constructor(
    private readonly clock: Clock,
    public status: ForemanStatusT,
  ) {
    super();
  }

  nextId(kind: 't' | 'd' | 'g'): string {
    this.counters[kind] += 1;
    return `${kind}${this.counters[kind]}`;
  }

  /** Everything needed to rebuild the state after a restart. */
  exportData(): TeamData {
    return {
      agents: [...this.agents.values()],
      tasks: [...this.tasks.values()],
      decisions: [...this.decisions.values()],
      goals: [...this.goals.values()],
      feed: this.feed.slice(-FEED_LIMIT),
      counters: { ...this.counters },
    };
  }

  importData(d: TeamData): void {
    for (const a of d.agents) {
      // keep the configured identity, restore what the agent was doing
      const base = this.agents.get(a.id);
      if (base)
        this.agents.set(a.id, {
          ...base,
          active: a.active,
          paused: a.paused,
          taskId: a.taskId,
          state: 'idle',
          activity: a.active ? 'in attesa di lavoro' : 'fuori turno',
          station: a.active ? 'base' : 'lounge',
        } as AgentT);
    }
    for (const t of d.tasks) this.tasks.set(t.id, t);
    for (const x of d.decisions) this.decisions.set(x.id, x);
    for (const g of d.goals) this.goals.set(g.id, g);
    this.feed.splice(0, this.feed.length, ...d.feed);
    this.counters = { ...this.counters, ...d.counters };
  }

  snapshot(): SnapshotMsg {
    return {
      v: 1,
      type: 'snapshot',
      foreman: this.status,
      agents: [...this.agents.values()],
      tasks: [...this.tasks.values()],
      decisions: [...this.decisions.values()],
      goals: [...this.goals.values()],
      repos: this.reposView(),
      feed: this.feed.slice(-FEED_LIMIT),
    };
  }

  putRepo(repo: z.infer<typeof Repo>): void {
    this.emit('message', { v: 1, type: 'repo.upsert', repo });
  }

  setStatus(patch: Partial<ForemanStatusT>): void {
    this.status = { ...this.status, ...patch };
    this.emit('message', { v: 1, type: 'foreman.status', status: this.status });
  }

  putAgent(agent: AgentT): AgentT {
    this.agents.set(agent.id, agent);
    this.emit('message', { v: 1, type: 'agent.upsert', agent });
    return agent;
  }

  patchAgent(id: string, patch: Partial<AgentT>): AgentT {
    const current = this.agents.get(id);
    if (!current) throw new Error(`unknown agent ${id}`);
    return this.putAgent(dropUndefined({ ...current, ...patch }) as AgentT);
  }

  putTask(task: TaskT): TaskT {
    this.tasks.set(task.id, task);
    this.emit('message', { v: 1, type: 'task.upsert', task });
    return task;
  }

  patchTask(id: string, patch: Partial<TaskT>): TaskT {
    const current = this.tasks.get(id);
    if (!current) throw new Error(`unknown task ${id}`);
    return this.putTask(dropUndefined({ ...current, ...patch, updatedAt: this.clock.now() }) as TaskT);
  }

  putDecision(decision: DecisionT): DecisionT {
    this.decisions.set(decision.id, decision);
    this.emit('message', { v: 1, type: 'decision.upsert', decision });
    return decision;
  }

  putGoal(goal: GoalT): GoalT {
    this.goals.set(goal.id, goal);
    this.emit('message', { v: 1, type: 'goal.upsert', goal });
    return goal;
  }

  patchGoal(id: string, patch: Partial<GoalT>): GoalT {
    const current = this.goals.get(id);
    if (!current) throw new Error(`unknown goal ${id}`);
    return this.putGoal({ ...current, ...patch, updatedAt: this.clock.now() });
  }

  addFeed(item: Omit<FeedItemT, 'ts'>): void {
    const full = dropUndefined({ ts: this.clock.now(), ...item }) as FeedItemT;
    this.feed.push(full);
    if (this.feed.length > FEED_LIMIT * 2) this.feed.splice(0, this.feed.length - FEED_LIMIT);
    this.emit('message', { v: 1, type: 'feed.add', item: full });
  }

  notify(level: 'info' | 'warn' | 'need_user', text: string, decisionId?: string): void {
    this.emit('message', {
      v: 1,
      type: 'notify',
      level,
      text,
      ...(decisionId ? { decisionId } : {}),
    });
  }
}

/** Optional fields are omitted, never undefined/null, on the wire. */
export function dropUndefined<T extends object>(o: T): T {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as T;
}
