import type { z } from 'zod';
import type { Station, AgentState } from '@agentcraft-server/protocol';
import type { Backend, ToolPort } from '../backend.js';
import type { Clock } from '../clock.js';
import type { DecisionT, GoalT, TaskT, TeamState } from '../state.js';

type StationT = z.infer<typeof Station>;
type StateT = z.infer<typeof AgentState>;

export interface SimOptions {
  /** pause between two scripted steps */
  stepMs: number;
}

/**
 * Scripted, deterministic team (no API usage). Exercises everything the plugin has to show:
 * planning, tasks with dependencies, NPCs walking between stations, speech, a question to the
 * admins and a merge decision (Merge / Request changes / Reject). Same input, same run.
 */
export class SimBackend implements Backend {
  private readonly waiting = new Map<string, (d: DecisionT) => void>();
  private readonly abort = new AbortController();

  constructor(
    private readonly state: TeamState,
    private readonly tools: ToolPort,
    private readonly clock: Clock,
    private readonly opts: SimOptions,
  ) {}

  start(): void {
    this.state.setStatus({ backend: 'sim', auth: 'ok', message: 'squadra simulata, nessun uso delle API' });
  }

  stop(): void {
    this.abort.abort(new Error('sim stopped'));
  }

  onGoal(goal: GoalT): void {
    this.runGoal(goal).catch((e: unknown) => {
      if (this.abort.signal.aborted) return;
      this.state.patchGoal(goal.id, { status: 'failed' });
      this.state.addFeed({ kind: 'error', text: `sim: ${e instanceof Error ? e.message : String(e)}` });
    });
  }

  onUserMessage(to: string, text: string, from: string): void {
    const target = to === 'all' ? 'bussola' : to;
    if (!this.state.agents.has(target)) return;
    void this.say(target, `Ricevuto, ${from}: "${text.slice(0, 80)}"`);
  }

  onDecisionAnswered(d: DecisionT): void {
    this.waiting.get(d.id)?.(d);
    this.waiting.delete(d.id);
  }

  // ------------------------------------------------------------------ script

  private async runGoal(goal: GoalT): Promise<void> {
    const lead = 'bussola';
    await this.act(lead, 'thinking', 'desk', `pianifica ${goal.id}`);
    await this.say(lead, 'Ricevuto! Divido il lavoro in compiti.');
    await this.step(lead);

    const t1 = this.createTask(goal, {
      title: 'Riprodurre il problema in staging',
      kind: 'qa',
      assignee: 'briciola',
      deps: [],
    });
    const t2 = this.createTask(goal, {
      title: 'Correggere e aggiungere i test',
      kind: 'code',
      assignee: 'lucciola',
      deps: [t1.id],
    });
    this.state.addFeed({ kind: 'plan', text: `Piano per ${goal.id}: ${t1.id} (qa) → ${t2.id} (code)`, agentId: lead });
    this.state.patchGoal(goal.id, { status: 'active' });
    await this.act(lead, 'idle', 'base', 'segue la squadra');

    await this.runQa(t1);
    this.updateProgress(goal.id);
    if (this.alive(t2.id)) await this.runCode(t2, lead);
    this.updateProgress(goal.id);

    const tasks = [t1, t2].map((t) => this.state.tasks.get(t.id)!);
    const done = tasks.some((t) => t.status === 'done');
    this.state.patchGoal(goal.id, { status: done ? 'done' : 'cancelled', progress: 1 });
    await this.act(lead, 'done', 'base', `${goal.id} completato`);
    await this.say(lead, done ? 'Obiettivo completato. Grazie!' : 'Obiettivo chiuso senza modifiche.');
    for (const id of ['briciola', 'lucciola', lead]) await this.act(id, 'idle', 'base', 'in attesa di lavoro');
  }

  private async runQa(t: TaskT): Promise<void> {
    const a = t.assignee!;
    this.state.patchTask(t.id, { status: 'doing' });
    await this.act(a, 'walking', 'testbench', `va al banco di prova`, t.id);
    await this.step(a);
    await this.act(a, 'testing', 'testbench', `testing ${t.id}`, t.id);
    await this.say(a, 'Provo i comandi sul server di staging.');
    await this.step(a);
    if (!this.alive(t.id)) return;

    const q = await this.ask(
      a,
      'question',
      'Ho trovato due casi limite. Li copro entrambi?',
      ['Sì, entrambi', 'Solo il primo'],
      t.id,
    );
    const both = q.answer?.option !== 'Solo il primo';
    await this.act(a, 'testing', 'testbench', both ? 'due scenari' : 'uno scenario', t.id);
    await this.step(a);
    if (!this.alive(t.id)) return;
    this.state.patchTask(t.id, {
      status: 'done',
      ci: 'pass',
      summary: both ? 'Riprodotti 2 casi.' : 'Riprodotto 1 caso.',
    });
    this.state.addFeed({ kind: 'task', text: `${t.id} completato`, agentId: a });
    await this.act(a, 'done', 'base', `${t.id} fatto`, undefined);
  }

  private async runCode(t: TaskT, lead: string): Promise<void> {
    const a = t.assignee!;
    this.state.patchTask(t.id, { status: 'doing', branch: `agentcraft/${a}/${t.id}-fix` });
    for (let round = 1; round <= 3; round++) {
      await this.act(a, 'editing', 'desk', `editing ${t.id}`, t.id);
      await this.say(a, round === 1 ? 'Mi metto al lavoro sul codice.' : 'Sistemo come richiesto.');
      await this.step(a);
      if (!this.alive(t.id)) return;
      await this.act(a, 'testing', 'terminal', `test di ${t.id}`, t.id);
      this.state.patchTask(t.id, { ci: 'running' });
      await this.step(a);
      this.state.patchTask(t.id, { ci: 'pass', status: 'review' });
      this.state.addFeed({ kind: 'ci', text: `Test di ${t.id}: passati`, agentId: a });
      await this.act(a, 'idle', 'base', `${t.id} in revisione`);

      await this.act(lead, 'reading', 'library', `revisiona ${t.id}`, t.id);
      await this.step(lead);
      if (!this.alive(t.id)) return;
      const d = await this.ask(
        lead,
        'merge',
        `Unire ${t.id} (${t.title})?`,
        ['Merge', 'Request changes', 'Reject'],
        t.id,
        '2 file, +41 -7 (simulato)',
      );
      const choice = d.answer?.option;
      if (choice === 'Merge') {
        this.state.patchTask(t.id, { status: 'done', summary: 'Unito (simulato).' });
        this.state.addFeed({ kind: 'merge', text: `${t.id} unito`, agentId: lead });
        await this.act(lead, 'idle', 'base', 'segue la squadra');
        return;
      }
      if (choice === 'Reject') {
        this.state.patchTask(t.id, { status: 'cancelled', summary: 'Rifiutato.' });
        await this.act(lead, 'idle', 'base', 'segue la squadra');
        return;
      }
      this.state.patchTask(t.id, { status: 'doing' });
      await this.act(lead, 'idle', 'base', 'segue la squadra');
    }
    this.state.patchTask(t.id, { status: 'blocked', blockedReason: 'troppe richieste di modifica' });
  }

  // ------------------------------------------------------------------ helpers

  private createTask(goal: GoalT, t: Pick<TaskT, 'title' | 'kind' | 'assignee' | 'deps'>): TaskT {
    const now = this.clock.now();
    return this.state.putTask({
      id: this.state.nextId('t'),
      ...t,
      status: 'todo',
      goalId: goal.id,
      priority: 0,
      ci: 'unknown',
      createdBy: 'bussola',
      createdAt: now,
      updatedAt: now,
    });
  }

  private alive(taskId: string): boolean {
    const t = this.state.tasks.get(taskId);
    return !!t && t.status !== 'cancelled';
  }

  private updateProgress(goalId: string): void {
    const tasks = [...this.state.tasks.values()].filter((t) => t.goalId === goalId && t.status !== 'cancelled');
    const done = tasks.filter((t) => t.status === 'done').length;
    this.state.patchGoal(goalId, { progress: tasks.length ? done / tasks.length : 1 });
  }

  /** Waits one step, then for as long as the agent is paused or off shift. */
  private async step(agentId: string): Promise<void> {
    await this.clock.sleep(this.opts.stepMs, this.abort.signal);
    for (;;) {
      const a = this.state.agents.get(agentId);
      if (!a || (a.active && !a.paused)) return;
      await this.clock.sleep(Math.max(this.opts.stepMs, 50), this.abort.signal);
    }
  }

  private async act(
    agentId: string,
    state: StateT,
    station: StationT,
    activity: string,
    taskId?: string,
  ): Promise<void> {
    const before = this.state.agents.get(agentId);
    if (!before) return;
    this.state.patchAgent(agentId, { state, station, activity, taskId });
    if (before.station !== station) {
      void this.tools.callTool(agentId, { tool: 'body.move', args: { to: { station } } }, { requestedBy: 'sim' });
    }
  }

  private async say(agentId: string, text: string): Promise<void> {
    this.state.addFeed({ kind: 'message', text, agentId, to: 'all' });
    void this.tools.callTool(agentId, { tool: 'body.say', args: { text } }, { requestedBy: 'sim' });
  }

  private ask(
    agentId: string,
    kind: DecisionT['kind'],
    question: string,
    options: string[],
    taskId: string,
    context?: string,
  ): Promise<DecisionT> {
    const d = this.state.putDecision({
      id: this.state.nextId('d'),
      agentId,
      kind,
      question,
      options,
      ...(context ? { context } : {}),
      status: 'open',
      taskId,
      createdAt: this.clock.now(),
    });
    this.state.patchAgent(agentId, {
      state: 'waiting_user',
      station: 'podium',
      activity: `attende risposta (${d.id})`,
    });
    void this.tools.callTool(
      agentId,
      { tool: 'body.move', args: { to: { station: 'podium' } } },
      { requestedBy: 'sim' },
    );
    this.state.notify('need_user', `${this.state.agents.get(agentId)?.name ?? agentId}: ${question}`, d.id);
    return new Promise((resolve, reject) => {
      this.waiting.set(d.id, resolve);
      this.abort.signal.addEventListener('abort', () => reject(this.abort.signal.reason), { once: true });
    });
  }
}
