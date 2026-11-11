import { Blueprint, type PluginToForeman } from '@agentcraft-server/protocol';
import type { ToolPort } from './backend.js';
import type { DecisionT, TeamState } from './state.js';

type JobProgress = Extract<PluginToForeman, { type: 'job.progress' }>;
type BlueprintT = Blueprint;

export const BUILD_OPTIONS = ['Costruisci', 'Modifica', 'Rifiuta'];

export interface BuildRequest {
  taskId: string;
  architect: string;
  blueprint: BlueprintT;
  region: string;
  anchor?: [number, number, number];
  rotation?: 0 | 90 | 180 | 270;
  requestedBy: string;
}

interface Pending extends BuildRequest {
  decisionId: string;
}

/**
 * The building flow shared by the sim and the real team: preview the blueprint as ghost blocks for
 * the admins, ask them (Costruisci / Modifica / Rifiuta), then have the builders place it module
 * by module, each a job the plugin runs and reports with job.progress.
 */
export class BuildCoordinator {
  private readonly pending = new Map<string, Pending>();
  private readonly jobWaiters = new Map<string, (p: JobProgress) => void>();

  constructor(
    private readonly state: TeamState,
    private readonly tools: ToolPort,
    private readonly hooks: {
      /** the admins asked for changes: the architect should design again */
      onChangesRequested: (taskId: string, feedback: string) => void;
      onFinished?: (taskId: string, ok: boolean) => void;
    },
  ) {}

  /** Validates, shows the preview and opens the build decision. Returns an error text, or undefined. */
  async propose(req: BuildRequest): Promise<string | undefined> {
    const parsed = Blueprint.safeParse(req.blueprint);
    if (!parsed.success)
      return `the blueprint is not valid: ${parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ')}`;
    const preview = await this.tools.callTool(
      req.architect,
      {
        tool: 'world.preview',
        args: {
          key: req.taskId,
          blueprint: parsed.data as unknown as Record<string, unknown>,
          region: req.region,
          ...(req.anchor ? { anchor: req.anchor } : {}),
          rotation: req.rotation ?? 0,
          viewers: [],
          ttlSeconds: 1800,
        },
      },
      { requestedBy: req.requestedBy, taskId: req.taskId, deadlineMs: 60_000 },
    );
    if (!preview.ok) return `the plugin refused the blueprint (${preview.error?.code}): ${preview.error?.message}`;
    const r = (preview.result ?? {}) as {
      blocks?: number;
      size?: string;
      materials?: Record<string, number>;
      shownTo?: number;
    };
    const materials = Object.entries(r.materials ?? {})
      .slice(0, 6)
      .map(([k, v]) => `${k.replace('minecraft:', '')} ${v}`)
      .join(', ');
    const s = this.state;
    const d = s.putDecision({
      id: s.nextId('d'),
      agentId: req.architect,
      kind: 'build',
      question: `Costruire "${parsed.data.name}" nella regione ${req.region}?`.slice(0, 500),
      options: BUILD_OPTIONS,
      context: [
        `${r.blocks ?? '?'} blocchi, ${r.size ?? '?'}, ${parsed.data.modules.length} moduli: ${parsed.data.modules.map((m) => m.name ?? m.id).join(', ')}`,
        materials ? `Materiali: ${materials}` : '',
        r.shownTo
          ? `Anteprima visibile ora a ${r.shownTo} admin (blocchi fantasma, solo per voi).`
          : 'Nessun admin online vicino: anteprima non mostrata.',
        'Modifica: scrivete cosa cambiare con /agent answer <id> 2 <testo>.',
      ]
        .filter(Boolean)
        .join('\n'),
      status: 'open',
      taskId: req.taskId,
      createdAt: Date.now(),
    });
    this.pending.set(d.id, { ...req, blueprint: parsed.data, decisionId: d.id });
    s.patchAgent(req.architect, {
      state: 'waiting_user',
      station: 'podium',
      activity: `attende ok al progetto (${d.id})`,
    });
    s.notify(
      'need_user',
      `${s.agents.get(req.architect)?.name ?? req.architect}: progetto pronto, guardate l'anteprima`,
      d.id,
    );
    return undefined;
  }

  /** True when this decision belongs to a build. */
  handles(d: DecisionT): boolean {
    return d.kind === 'build' && this.pending.has(d.id);
  }

  async onDecision(d: DecisionT): Promise<void> {
    const p = this.pending.get(d.id);
    if (!p) return;
    this.pending.delete(d.id);
    const option = d.answer?.option;
    if (option === 'Costruisci') {
      await this.build(p, d);
    } else if (option === 'Modifica') {
      this.hooks.onChangesRequested(
        p.taskId,
        d.answer?.text ?? 'Nessun dettaglio: chiedete con ask_user cosa cambiare.',
      );
    } else {
      await this.clearPreview(p);
      this.state.patchTask(p.taskId, {
        status: 'cancelled',
        summary: `Progetto rifiutato da ${d.answer?.by ?? 'admin'}.`,
      });
      this.state.addFeed({ kind: 'build', text: `${p.taskId}: progetto rifiutato`, agentId: p.architect });
      this.hooks.onFinished?.(p.taskId, false);
    }
  }

  onJobProgress(msg: JobProgress): void {
    const builder = [...this.state.agents.values()].find(
      (a) => a.taskId && msg.jobId.startsWith(`${a.taskId}-`) && a.role === 'builder',
    );
    if (builder && (msg.state === 'running' || msg.state === 'paused')) {
      const pct = msg.total ? Math.floor((100 * msg.done) / msg.total) : 0;
      this.state.patchAgent(builder.id, {
        activity: `costruisce ${msg.jobId.split('-').slice(1).join('-')} ${pct}%`.slice(0, 64),
      });
    }
    if (['done', 'failed', 'cancelled', 'undone'].includes(msg.state)) {
      this.jobWaiters.get(msg.jobId)?.(msg);
      this.jobWaiters.delete(msg.jobId);
    }
  }

  private waitJob(jobId: string): Promise<JobProgress> {
    return new Promise((resolve) => this.jobWaiters.set(jobId, resolve));
  }

  private async build(p: Pending, d: DecisionT): Promise<void> {
    const s = this.state;
    await this.clearPreview(p);
    const builders = [...s.agents.values()].filter((a) => a.role === 'builder' && a.active);
    if (builders.length === 0) {
      s.patchTask(p.taskId, { status: 'blocked', blockedReason: 'nessun costruttore in turno: /agent spawn mattone' });
      s.notify('warn', 'Nessun costruttore in turno: usate /agent spawn mattone');
      this.hooks.onFinished?.(p.taskId, false);
      return;
    }
    s.patchTask(p.taskId, { status: 'doing' });
    s.patchAgent(p.architect, { state: 'idle', station: 'base', activity: 'progetto approvato' });
    s.addFeed({
      kind: 'build',
      text: `${d.answer?.by ?? 'admin'} approva "${p.blueprint.name}": si costruisce`,
      agentId: p.architect,
    });
    const modules = order(p.blueprint);
    let i = 0;
    for (const m of modules) {
      const b = builders[i++ % builders.length]!;
      const jobId = `${p.taskId}-${m.id}`;
      s.patchAgent(b.id, {
        state: 'building',
        station: 'worksite',
        activity: `costruisce ${m.name ?? m.id}`.slice(0, 64),
        taskId: p.taskId,
      });
      const waiting = this.waitJob(jobId);
      const r = await this.tools.callTool(
        b.id,
        {
          tool: 'world.place',
          args: {
            jobId,
            approvalId: d.id,
            region: p.region,
            ...(p.anchor ? { anchor: p.anchor } : {}),
            rotation: p.rotation ?? 0,
            blueprint: p.blueprint as unknown as Record<string, unknown>,
            module: m.id,
          },
        },
        { requestedBy: p.requestedBy, taskId: p.taskId, deadlineMs: 60_000 },
      );
      if (!r.ok) {
        this.jobWaiters.delete(jobId);
        return this.fail(p, b.id, `${m.name ?? m.id}: ${r.error?.message ?? 'rifiutato'}`);
      }
      const end = await waiting;
      if (end.state !== 'done') return this.fail(p, b.id, `${m.name ?? m.id}: lavoro ${end.state}`);
      s.addFeed({ kind: 'build', text: `${m.name ?? m.id} finito (${end.total} blocchi)`, agentId: b.id });
    }
    for (const b of builders)
      s.patchAgent(b.id, {
        state: 'done',
        station: 'base',
        activity: `"${p.blueprint.name}" costruita`.slice(0, 64),
        taskId: undefined,
      });
    s.patchTask(p.taskId, {
      status: 'done',
      summary: `"${p.blueprint.name}" costruita nella regione ${p.region}. Annullabile con /agent undo ${p.taskId}.`,
    });
    s.addFeed({ kind: 'build', text: `"${p.blueprint.name}" costruita! (/agent undo ${p.taskId} per annullare)` });
    s.notify('info', `"${p.blueprint.name}" costruita. Per annullarla: /agent undo ${p.taskId}`);
    this.hooks.onFinished?.(p.taskId, true);
  }

  private fail(p: Pending, builder: string, why: string): void {
    this.state.patchTask(p.taskId, {
      status: 'blocked',
      blockedReason: `costruzione interrotta: ${why}`.slice(0, 500),
    });
    this.state.patchAgent(builder, { state: 'error', station: 'base', activity: 'costruzione interrotta' });
    this.state.notify(
      'warn',
      `Costruzione di ${p.taskId} interrotta: ${why}. /agent undo ${p.taskId} per tornare indietro.`,
    );
    this.hooks.onFinished?.(p.taskId, false);
  }

  private async clearPreview(p: Pending): Promise<void> {
    await this.tools.callTool(
      p.architect,
      {
        tool: 'world.preview',
        args: { key: p.taskId, blueprint: {}, region: p.region, rotation: 0, viewers: [], ttlSeconds: 0 },
      },
      { requestedBy: p.requestedBy, taskId: p.taskId, deadlineMs: 10_000 },
    );
  }
}

function order(bp: BlueprintT): BlueprintT['modules'] {
  const byId = new Map(bp.modules.map((m) => [m.id, m]));
  const out: BlueprintT['modules'] = [];
  const seen = new Set<string>();
  const visit = (id: string) => {
    if (seen.has(id)) return;
    seen.add(id);
    for (const d of byId.get(id)?.dependsOn ?? []) visit(d);
    const m = byId.get(id);
    if (m) out.push(m);
  };
  for (const m of bp.modules) visit(m.id);
  return out;
}
