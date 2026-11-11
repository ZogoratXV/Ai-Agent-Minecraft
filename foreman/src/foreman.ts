import type { PluginToForeman } from '@agentcraft-server/protocol';
import type { Backend } from './backend.js';
import type { CastMember } from './cast.js';
import { DEFAULT_CAST } from './cast.js';
import type { Clock } from './clock.js';
import type { Config } from './config.js';
import { ROLES } from './roles.js';
import type { AgentT, DecisionT, TeamState } from './state.js';

type Intent = Exclude<PluginToForeman, { type: 'hello' | 'tool.result' }>;

const MODEL_LABELS: Record<string, string> = {
  'claude-opus-5-5': 'Opus 5.5',
  'claude-sonnet-5-5': 'Sonnet 5.5',
  'claude-haiku-5-5': 'Haiku 5.5',
};

/** The team from the default cast with the overrides from config.json. */
export function buildTeam(cfg: Config): AgentT[] {
  return DEFAULT_CAST.map((base): AgentT => {
    const o = cfg.cast.find((c) => c.id === base.id);
    const m: CastMember = { ...base, ...(o ?? {}) } as CastMember;
    const model =
      cfg.backend === 'sim' ? 'sim' : (MODEL_LABELS[cfg.models[ROLES[m.role].tier]] ?? cfg.models[ROLES[m.role].tier]);
    return {
      id: m.id,
      name: m.name,
      role: m.role,
      title: m.title,
      color: m.color,
      ...(o?.skin ? { skin: o.skin } : {}),
      state: 'idle',
      activity: m.onShift ? 'in attesa di lavoro' : 'fuori turno',
      station: m.onShift ? 'base' : 'lounge',
      paused: false,
      active: m.onShift,
      model,
    };
  });
}

/** Resolves "2" or "merge" to the exact option label. */
export function resolveOption(decision: DecisionT, option: string | undefined): string | undefined {
  if (option === undefined) return undefined;
  if (decision.options.length === 0) return option;
  const n = Number(option);
  if (Number.isInteger(n) && n >= 1 && n <= decision.options.length) return decision.options[n - 1];
  const match = decision.options.find((o) => o.toLowerCase() === option.toLowerCase());
  if (!match) throw new Error(`unknown option '${option}'; choose one of: ${decision.options.join(', ')}`);
  return match;
}

/** Applies admin intents to the state and tells the backend. The plugin already checked permissions. */
export class Foreman {
  constructor(
    private readonly state: TeamState,
    private readonly clock: Clock,
    private backend?: Backend,
  ) {}

  setBackend(backend: Backend): void {
    this.backend = backend;
  }

  handle(intent: Intent): unknown {
    switch (intent.type) {
      case 'goal.submit': {
        const now = this.clock.now();
        const goal = this.state.putGoal({
          id: this.state.nextId('g'),
          text: intent.text,
          status: 'planning',
          progress: 0,
          createdBy: `user:${intent.actor.name}`,
          createdAt: now,
          updatedAt: now,
        });
        this.state.addFeed({ kind: 'goal', text: `${intent.actor.name}: ${intent.text}`, to: 'all' });
        this.backend?.onGoal(goal);
        return { goalId: goal.id };
      }
      case 'user.message': {
        if (intent.to !== 'all' && !this.state.agents.has(intent.to)) throw new Error(`unknown agent '${intent.to}'`);
        this.state.addFeed({ kind: 'message', text: `${intent.actor.name}: ${intent.text}`, to: intent.to });
        this.backend?.onUserMessage(intent.to, intent.text, intent.actor.name);
        return undefined;
      }
      case 'decision.answer': {
        const d = this.state.decisions.get(intent.decisionId);
        if (!d) throw new Error(`unknown decision '${intent.decisionId}'`);
        if (d.status !== 'open') throw new Error(`decision ${d.id} is already ${d.status}`);
        const option = resolveOption(d, intent.option);
        if (d.options.length > 0 && option === undefined && !intent.text) throw new Error('choose an option');
        const answered = this.state.putDecision({
          ...d,
          status: 'answered',
          answer: {
            ...(option !== undefined ? { option } : {}),
            ...(intent.text ? { text: intent.text } : {}),
            by: intent.actor.name,
            ts: this.clock.now(),
          },
        });
        this.state.addFeed({
          kind: 'decision',
          text: `${intent.actor.name} ha risposto a ${d.id}: ${option ?? intent.text ?? ''}`,
          agentId: d.agentId,
        });
        this.backend?.onDecisionAnswered(answered);
        return { option };
      }
      case 'task.action': {
        const t = this.state.tasks.get(intent.taskId);
        if (!t) throw new Error(`unknown task '${intent.taskId}'`);
        switch (intent.action) {
          case 'cancel':
            this.state.patchTask(t.id, { status: 'cancelled' });
            break;
          case 'retry':
            if (t.status !== 'blocked' && t.status !== 'cancelled') throw new Error(`task ${t.id} is ${t.status}`);
            this.state.patchTask(t.id, { status: 'todo', blockedReason: undefined });
            break;
          case 'reassign': {
            const a = intent.agentId ? this.state.agents.get(intent.agentId) : undefined;
            if (!a) throw new Error(`unknown agent '${intent.agentId ?? ''}'`);
            this.state.patchTask(t.id, { assignee: a.id });
            break;
          }
          case 'prioritize':
            this.state.patchTask(t.id, { priority: intent.priority ?? t.priority + 1 });
            break;
        }
        this.state.addFeed({ kind: 'task', text: `${intent.actor.name}: ${intent.action} ${t.id}` });
        this.backend?.onTaskAction?.(t.id, intent.action);
        return undefined;
      }
      case 'agent.action': {
        const targets =
          intent.agentId === 'all'
            ? [...this.state.agents.values()]
            : [this.state.agents.get(intent.agentId)].filter(Boolean);
        if (targets.length === 0) throw new Error(`unknown agent '${intent.agentId}'`);
        for (const a of targets as AgentT[]) {
          switch (intent.action) {
            case 'pause':
              this.state.patchAgent(a.id, { paused: true, activity: 'in pausa' });
              break;
            case 'resume':
              this.state.patchAgent(a.id, { paused: false, activity: a.active ? 'riprende il lavoro' : a.activity });
              break;
            case 'stop':
              this.state.patchAgent(a.id, {
                active: false,
                paused: false,
                state: 'idle',
                station: 'lounge',
                activity: 'fuori turno',
              });
              break;
            case 'spawn':
              this.state.patchAgent(a.id, {
                active: true,
                paused: false,
                station: 'base',
                activity: 'in attesa di lavoro',
              });
              break;
          }
        }
        for (const a of targets as AgentT[]) this.backend?.onAgentAction?.(a.id, intent.action);
        if (intent.agentId === 'all' && (intent.action === 'pause' || intent.action === 'resume')) {
          this.state.setStatus({ paused: intent.action === 'pause' });
        }
        this.state.addFeed({ kind: 'system', text: `${intent.actor.name}: ${intent.action} ${intent.agentId}` });
        return undefined;
      }
      case 'diff.request':
        if (!this.backend?.onDiff) throw new Error('diffs are available with the real agents (backend claude)');
        return this.backend.onDiff(intent.taskId);
      case 'repo.add':
        if (!this.backend?.onRepoAdd) throw new Error('repositories are used by the real agents (backend claude)');
        this.state.addFeed({ kind: 'system', text: `${intent.actor.name}: aggiunge il repository ${intent.path}` });
        return this.backend.onRepoAdd(intent.path);
      case 'repo.push':
        if (!this.backend?.onRepoPush) throw new Error('push is available with the real agents (backend claude)');
        return this.backend.onRepoPush(intent.repoId, intent.actor.name);
      case 'job.progress':
        return undefined;
      case 'world.event':
        this.state.addFeed({ kind: intent.kind.endsWith('error') ? 'error' : 'system', text: intent.text });
        return undefined;
    }
  }
}
