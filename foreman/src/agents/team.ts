import { mkdirSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import type { Backend } from '../backend.js';
import type { CastMember } from '../cast.js';
import { detectBuilds, parseCommand, runCi } from '../ci.js';
import type { Clock } from '../clock.js';
import type { Config } from '../config.js';
import { agentEnv } from '../gitsafety.js';
import type { Memory } from '../memory.js';
import { decide } from '../policy.js';
import type { RepoManager } from '../repos.js';
import { type RepoRecord, type RepoSettings } from '../repos.js';
import { ROLES } from '../roles.js';
import type { AgentT, DecisionT, GoalT, TaskT, TeamState } from '../state.js';
import type { Store } from '../store.js';
import { detectApiAuth, NO_API_AUTH_MESSAGE, withApiAuthOnly } from './auth.js';
import { basePrompt, planPrompt, qaPrompt, repoPrompt, reviewPrompt, workPrompt } from './prompts.js';
import type { Permission, Runner, TurnResult } from './runner.js';
import { buildTools, type TeamApi } from './tools.js';

type Job =
  | { kind: 'plan'; goalId: string }
  | { kind: 'work'; taskId: string }
  | { kind: 'review'; taskId: string }
  | { kind: 'chat'; agentId: string };

interface TaskRuntime {
  repoId?: string;
  paths: string[];
  worktree?: string | undefined;
  branch?: string;
  sessions: Record<string, string>;
  ciFailures: number;
  nudged: boolean;
  reviewNudged: boolean;
  spentUsd: number;
  feedback?: string | undefined;
  ci?: string;
  /** existing version folders: read and copy only */
  frozen?: string[];
  /** set by request_merge / request_changes during a review turn */
  reviewOutcome?: 'merge' | 'changes' | undefined;
}

interface Budget {
  day: string;
  dayUsd: number;
  month: string;
  monthUsd: number;
}

interface Persisted {
  runtime: Record<string, TaskRuntime>;
  grants: Record<string, string[]>;
  leadSessions: Record<string, string>;
  budget: Budget;
  inbox: Record<string, string[]>;
  repos: RepoSettings[];
}

export interface TeamOptions {
  cfg: Config;
  state: TeamState;
  repos: RepoManager;
  memory: Memory;
  runner: Runner;
  store: Store;
  clock: Clock;
  cast: readonly CastMember[];
  home: string;
  serverName?: string;
  log?: (line: string) => void;
  /** environment the agents start from (default process.env) */
  baseEnv?: NodeJS.ProcessEnv;
}

const PERMISSION_OPTIONS = ['Allow once', 'Always allow for this agent', 'Deny'];
const MERGE_OPTIONS = ['Merge', 'Request changes', 'Reject'];
const STATION_MIN_MS = 15_000;

/**
 * The real team: Claude agents planning, coding, testing and reviewing in git worktrees, with
 * every risky step decided by the admins in game. Everything is persisted, so a restart resumes
 * where it stopped. Inspired by AgentCraft's agents/team.ts (MIT, see NOTICE).
 */
export class TeamBackend implements Backend, TeamApi {
  private readonly queue: Job[] = [];
  private readonly running = new Map<string, { job: Job; abort: AbortController }>();
  private readonly waiting = new Map<string, (d: DecisionT) => void>();
  private readonly lastStation = new Map<string, number>();
  private data: Persisted;
  private stopped = false;
  private readonly log: (line: string) => void;

  constructor(private readonly o: TeamOptions) {
    this.log = o.log ?? (() => {});
    this.data = {
      runtime: {},
      grants: {},
      leadSessions: {},
      budget: { day: '', dayUsd: 0, month: '', monthUsd: 0 },
      inbox: {},
      repos: [],
      ...(o.store.readJson<Persisted>('team.json') ?? {}),
    };
  }

  // ------------------------------------------------------------------ lifecycle

  async init(configured: RepoSettings[]): Promise<void> {
    const all = [...configured, ...this.data.repos.filter((r) => !configured.some((c) => c.path === r.path))];
    for (const settings of all) {
      try {
        await this.o.repos.add(settings);
      } catch (e) {
        this.log(`repository ${settings.path} not available: ${e instanceof Error ? e.message : String(e)}`);
      }
    }
    this.reconcile();
  }

  private apiAuth(): boolean {
    return detectApiAuth(this.o.baseEnv ?? process.env).ok;
  }

  start(): void {
    const auth = detectApiAuth(this.o.baseEnv ?? process.env);
    this.o.state.setStatus(
      auth.ok
        ? { backend: 'claude', auth: 'unknown', message: `agenti Claude (${auth.source})` }
        : { backend: 'claude', auth: 'failed', message: NO_API_AUTH_MESSAGE },
    );
    this.updateBudgetStatus();
    this.pump();
  }

  stop(): void {
    this.stopped = true;
    for (const r of this.running.values()) r.abort.abort();
    this.save();
    this.o.store.flush();
  }

  private save(): void {
    this.o.store.scheduleWrite('team.json', () => this.data);
  }

  /** After a restart: resume work, re-plan, cancel questions nobody waits for any more. */
  private reconcile(): void {
    const s = this.o.state;
    for (const d of s.decisions.values()) {
      if (d.status === 'open' && d.kind !== 'merge') s.putDecision({ ...d, status: 'cancelled' });
    }
    for (const g of s.goals.values()) {
      const tasks = [...s.tasks.values()].filter((t) => t.goalId === g.id);
      if (g.status === 'planning' && tasks.length === 0) this.enqueue({ kind: 'plan', goalId: g.id });
    }
    for (const t of s.tasks.values()) {
      if (t.status === 'doing') this.enqueue({ kind: 'work', taskId: t.id });
      if (t.status === 'review') {
        const open = [...s.decisions.values()].some(
          (d) => d.kind === 'merge' && d.taskId === t.id && d.status === 'open',
        );
        if (!open) void this.afterWork(t.id);
      }
    }
  }

  // ------------------------------------------------------------------ Backend

  onGoal(goal: GoalT): void {
    this.enqueue({ kind: 'plan', goalId: goal.id });
    this.pump();
  }

  onUserMessage(to: string, text: string, from: string): void {
    const targets = to === 'all' ? [...this.o.state.agents.values()].filter((a) => a.active).map((a) => a.id) : [to];
    for (const id of targets) {
      (this.data.inbox[id] ??= []).push(`Message from the admin ${from}: ${text}`);
      if (!this.running.has(id) && !this.queue.some((j) => this.jobAgent(j) === id))
        this.enqueue({ kind: 'chat', agentId: id });
    }
    this.save();
    this.pump();
  }

  onDecisionAnswered(d: DecisionT): void {
    const resolve = this.waiting.get(d.id);
    if (resolve) {
      this.waiting.delete(d.id);
      resolve(d);
      return;
    }
    if (d.kind === 'merge' && d.taskId) void this.onMergeAnswer(d);
  }

  onAgentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn'): void {
    if (action === 'pause' || action === 'stop') {
      const r = this.running.get(agentId);
      if (r) {
        r.abort.abort();
        if (action === 'pause') this.queue.unshift(r.job);
      }
      if (action === 'stop') {
        for (const t of this.o.state.tasks.values()) {
          if (t.assignee === agentId && t.status === 'doing') this.o.state.patchTask(t.id, { status: 'todo' });
        }
      }
    }
    this.pump();
  }

  onTaskAction(taskId: string, action: 'cancel' | 'retry' | 'reassign' | 'prioritize'): void {
    const running = [...this.running.entries()].find(([, r]) => 'taskId' in r.job && r.job.taskId === taskId);
    if (action === 'cancel' || action === 'reassign') running?.[1].abort.abort();
    if (action === 'cancel') void this.cleanup(taskId);
    if (action === 'retry') {
      const rt = this.rt(taskId);
      rt.ciFailures = 0;
      rt.nudged = false;
    }
    this.pump();
  }

  async onRepoAdd(path: string): Promise<unknown> {
    const r = await this.o.repos.add({ path });
    this.data.repos = [...this.data.repos.filter((x) => x.path !== r.path), { path: r.path, id: r.id }];
    this.save();
    this.o.state.addFeed({
      kind: 'system',
      text: `Repository ${r.name} registrato (${r.versioning}, base ${r.branch})`,
    });
    return { repoId: r.id, versioning: r.versioning, branch: r.branch };
  }

  async onRepoPush(repoId: string | undefined, by: string): Promise<unknown> {
    const id = repoId ?? this.defaultRepo()?.id;
    if (!id) throw new Error('no repository registered');
    const r = await this.o.repos.push(id);
    this.o.state.addFeed({ kind: 'merge', text: `${by} ha pubblicato ${id} su ${r.remote}/${r.branch} (${r.sha})` });
    this.o.store.appendJsonl('audit.jsonl', {
      ts: this.o.clock.now(),
      action: 'push',
      by,
      repo: id,
      remote: r.remote,
      branch: r.branch,
      sha: r.sha,
    });
    return r;
  }

  async onDiff(taskId: string): Promise<unknown> {
    const t = this.task(taskId);
    const rt = this.rt(taskId);
    if (!rt.repoId || !rt.branch) throw new Error(`task ${taskId} has no branch yet`);
    if (rt.worktree)
      await this.o.repos.commitAll(
        { path: rt.worktree, branch: rt.branch },
        this.identity(t.assignee ?? 'agent'),
        `wip ${taskId}`,
      );
    const d = await this.o.repos.diff(rt.repoId, rt.branch);
    const dir = join(this.o.home, 'diffs');
    mkdirSync(dir, { recursive: true });
    const path = join(dir, `${taskId}.diff`);
    writeFileSync(path, d.patch);
    return {
      taskId,
      summary: d.stat.split('\n').slice(-1)[0] ?? '',
      files: d.files,
      additions: d.additions,
      deletions: d.deletions,
      path,
    };
  }

  // ------------------------------------------------------------------ scheduling

  private enqueue(job: Job): void {
    const same = (j: Job) => j.kind === job.kind && JSON.stringify(j) === JSON.stringify(job);
    if (!this.queue.some(same) && ![...this.running.values()].some((r) => same(r.job))) this.queue.push(job);
  }

  private jobAgent(job: Job): string | undefined {
    if (job.kind === 'chat') return job.agentId;
    if (job.kind === 'work') return this.o.state.tasks.get(job.taskId)?.assignee;
    return this.lead()?.id;
  }

  private lead(): AgentT | undefined {
    return [...this.o.state.agents.values()].find((a) => a.role === 'lead');
  }

  private available(id: string | undefined): boolean {
    if (!id) return false;
    const a = this.o.state.agents.get(id);
    return !!a && a.active && !a.paused && !this.running.has(id);
  }

  private budgetLeft(): number {
    this.rollBudget();
    const b = this.data.budget;
    return Math.min(this.o.cfg.budget.dailyUsd - b.dayUsd, this.o.cfg.budget.monthlyUsd - b.monthUsd);
  }

  private pump(): void {
    if (this.stopped || this.o.state.status.paused) return;
    // never fall back to someone's claude.ai login: without API auth nobody works
    if (!this.apiAuth()) return;
    if (this.budgetLeft() <= 0.05) {
      this.o.state.setStatus({ paused: true, message: 'budget raggiunto: agenti in pausa' });
      this.o.state.notify(
        'warn',
        'Tetto di spesa raggiunto: gli agenti sono in pausa. Alza il budget o riprendi domani.',
      );
      return;
    }
    this.scheduleReadyTasks();
    const workers = () => [...this.running.values()].filter((r) => r.job.kind === 'work').length;
    for (const job of [...this.queue]) {
      const agent = this.jobAgent(job);
      if (!this.available(agent)) continue;
      if (job.kind === 'work' && workers() >= this.o.cfg.maxConcurrent) continue;
      this.queue.splice(this.queue.indexOf(job), 1);
      void this.runJob(job, agent!);
    }
  }

  /** Tasks whose dependencies are done get a worker (assigned, or the first free one of the right role). */
  private scheduleReadyTasks(): void {
    const s = this.o.state;
    const roleFor: Record<string, string> = {
      code: 'developer',
      qa: 'qa',
      analysis: 'analyst',
      generic: 'developer',
      build: 'builder',
    };
    const tasks = [...s.tasks.values()].filter((t) => t.status === 'todo').sort((a, b) => b.priority - a.priority);
    for (const t of tasks) {
      const deps = t.deps.map((d) => s.tasks.get(d));
      if (deps.some((d) => d?.status === 'cancelled')) {
        s.patchTask(t.id, { status: 'blocked', blockedReason: 'una dipendenza è stata annullata' });
        continue;
      }
      if (!deps.every((d) => d?.status === 'done')) continue;
      let assignee = t.assignee;
      if (!assignee || !s.agents.get(assignee)?.active) {
        const free = [...s.agents.values()].find(
          (a) => a.active && a.role === roleFor[t.kind] && !this.running.has(a.id),
        );
        if (!free) continue;
        assignee = free.id;
        s.patchTask(t.id, { assignee });
      }
      s.patchTask(t.id, { status: 'doing' });
      this.enqueue({ kind: 'work', taskId: t.id });
    }
  }

  private async runJob(job: Job, agentId: string): Promise<void> {
    const abort = new AbortController();
    this.running.set(agentId, { job, abort });
    try {
      if (job.kind === 'plan') await this.plan(job.goalId, agentId, abort.signal);
      else if (job.kind === 'work') await this.work(job.taskId, agentId, abort.signal);
      else if (job.kind === 'review') await this.review(job.taskId, agentId, abort.signal);
      else await this.chat(agentId, abort.signal);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this.log(`${job.kind} job of ${agentId} failed: ${msg}`);
      this.o.state.addFeed({ kind: 'error', text: `${agentId}: ${msg.slice(0, 300)}`, agentId });
      if ('taskId' in job) this.o.state.patchTask(job.taskId, { status: 'blocked', blockedReason: msg.slice(0, 400) });
      this.setAgent(agentId, 'error', 'base', 'errore: vedi /agent feed');
    } finally {
      this.running.delete(agentId);
      const a = this.o.state.agents.get(agentId);
      if (a && a.state !== 'error' && a.active) this.setAgent(agentId, 'idle', 'base', 'in attesa di lavoro');
      this.save();
      this.pump();
    }
  }

  // ------------------------------------------------------------------ jobs

  private defaultRepo(): RepoRecord | undefined {
    return [...this.o.repos.repos.values()][0];
  }

  private async plan(goalId: string, agentId: string, signal: AbortSignal): Promise<void> {
    const goal = this.o.state.goals.get(goalId);
    if (!goal) return;
    const repo = this.defaultRepo();
    this.setAgent(agentId, 'thinking', 'desk', `pianifica ${goalId}`, undefined, true);
    const res = await this.turn({
      agentId,
      cwd: repo?.path ?? this.scratch(goalId),
      writer: false,
      repo,
      prompt: planPrompt(goal, repo),
      resume: this.data.leadSessions[goalId],
      signal,
      ...(repo ? { repoId: repo.id } : {}),
    });
    if (res.sessionId) this.data.leadSessions[goalId] = res.sessionId;
    if (signal.aborted) return;
    const tasks = [...this.o.state.tasks.values()].filter((t) => t.goalId === goalId);
    if (!res.ok) {
      this.o.state.patchGoal(goalId, { status: 'failed' });
      this.o.state.addFeed({
        kind: 'error',
        text: `Piano di ${goalId} non riuscito: ${res.error ?? ''}`.slice(0, 300),
        agentId,
      });
      return;
    }
    if (tasks.length === 0) {
      this.o.state.patchGoal(goalId, { status: 'done', progress: 1 });
      this.o.state.addFeed({
        kind: 'plan',
        text: `${goalId}: nessun task necessario. ${res.text.slice(0, 200)}`,
        agentId,
      });
    } else {
      this.o.state.patchGoal(goalId, { status: 'active' });
      this.o.state.addFeed({ kind: 'plan', text: `Piano ${goalId}: ${tasks.map((t) => t.id).join(', ')}`, agentId });
    }
  }

  private async work(taskId: string, agentId: string, signal: AbortSignal): Promise<void> {
    const t = this.task(taskId);
    if (t.status === 'cancelled' || t.status === 'done') return;
    const rt = this.rt(taskId);
    if (!rt.repoId && t.repoId && this.o.repos.repos.has(t.repoId)) rt.repoId = t.repoId;
    const agent = this.o.state.agents.get(agentId)!;
    const repo = rt.repoId ? this.o.repos.get(rt.repoId) : undefined;
    if (repo && !rt.worktree) {
      this.setAgent(agentId, 'running', 'terminal', `prepara ${taskId}`, taskId);
      const wt = await this.o.repos.createWorktree(repo.id, agentId, taskId, t.title, [
        ...rt.paths,
        ...(t.module ? [t.module] : []),
      ]);
      rt.worktree = wt.path;
      rt.branch = wt.branch;
      rt.frozen = await this.o.repos.frozenFolders(repo.id);
      this.o.state.patchTask(taskId, { branch: wt.branch });
      this.save();
    }
    const cwd = rt.worktree ?? this.scratch(taskId);
    this.o.state.patchTask(taskId, { status: 'doing' });
    this.setAgent(agentId, 'thinking', 'desk', `lavora a ${taskId}`, taskId, true);
    const feedback = rt.feedback;
    rt.feedback = undefined;
    const prompt =
      agent.role === 'qa' ? qaPrompt(t, rt.branch ?? '-', feedback) : workPrompt(t, rt.branch ?? '-', feedback);
    const res = await this.turn({
      agentId,
      cwd,
      writer: ROLES[agent.role].code,
      repo,
      prompt,
      resume: rt.sessions[agentId],
      signal,
      taskId,
    });
    if (res.sessionId) rt.sessions[agentId] = res.sessionId;
    if (signal.aborted) return;
    const now = this.task(taskId);
    if (!res.ok) {
      this.o.state.patchTask(taskId, {
        status: 'blocked',
        blockedReason: `turno non riuscito: ${res.error ?? 'errore'}`.slice(0, 400),
      });
      this.setAgent(agentId, 'error', 'base', `${taskId} bloccato`);
      return;
    }
    if (now.status === 'review') {
      void this.afterWork(taskId);
      return;
    }
    if (now.status === 'blocked') return;
    if (!rt.nudged) {
      rt.nudged = true;
      rt.feedback =
        'You ended your turn without calling update_task. Finish the task and call update_task with status "review" (or "blocked" with the reason).';
      this.enqueue({ kind: 'work', taskId });
    } else {
      this.o.state.patchTask(taskId, { status: 'blocked', blockedReason: 'il lavoro si è fermato senza update_task' });
    }
  }

  /** Commit the worker's changes, run the tests, then review (or back to the worker once). */
  private async afterWork(taskId: string): Promise<void> {
    const t = this.task(taskId);
    const rt = this.rt(taskId);
    if (!rt.repoId || !rt.worktree || !rt.branch) {
      this.o.state.patchTask(taskId, { status: 'done' });
      this.goalProgress(t.goalId);
      return;
    }
    const wt = { path: rt.worktree, branch: rt.branch };
    await this.o.repos.commitAll(
      wt,
      this.identity(t.assignee ?? 'agent'),
      `${t.id}: ${t.title}\n\n${t.summary ?? ''}`.slice(0, 2000),
    );
    const d = await this.o.repos.diff(rt.repoId, rt.branch);
    if (d.files === 0) {
      this.o.state.patchTask(taskId, {
        status: 'done',
        ci: 'unknown',
        summary: `${t.summary ?? ''}\n(nessuna modifica ai file)`.trim(),
      });
      this.o.state.addFeed({
        kind: 'task',
        text: `${taskId} completato senza modifiche al codice`,
        ...(t.assignee ? { agentId: t.assignee } : {}),
      });
      await this.cleanup(taskId, false);
      this.goalProgress(t.goalId);
      return;
    }
    const repo = this.o.repos.get(rt.repoId);
    this.o.state.patchTask(taskId, { ci: 'running' });
    if (t.assignee) this.setAgent(t.assignee, 'testing', 'terminal', `test di ${taskId}`, taskId);
    const commands = repo.settings.ci
      ? [parseCommand(repo.settings.ci, t.module ?? '')]
      : detectBuilds(rt.worktree, d.changed, t.module);
    const ci = await runCi(
      rt.worktree,
      commands,
      agentEnv(this.o.baseEnv ?? process.env, rt.worktree, this.identity(t.assignee ?? 'agent')),
    );
    rt.ci = ci.skipped ? ci.summary : `${ci.ok ? 'ok' : 'FALLITO'}: ${ci.summary}${ci.ok ? '' : `\n${ci.output}`}`;
    this.o.state.patchTask(taskId, { ci: ci.skipped ? 'unknown' : ci.ok ? 'pass' : 'fail' });
    this.o.state.addFeed({
      kind: 'ci',
      text: `Test di ${taskId}: ${ci.skipped ? 'nessuna build' : ci.ok ? 'passati' : 'falliti'}`,
      ...(t.assignee ? { agentId: t.assignee } : {}),
    });
    if (!ci.ok && rt.ciFailures === 0) {
      rt.ciFailures = 1;
      rt.feedback = `The tests failed after your change:\n${ci.summary}\n${ci.output}\nFix it, run the tests again, then call update_task with status "review".`;
      this.o.state.patchTask(taskId, { status: 'doing' });
      this.enqueue({ kind: 'work', taskId });
    } else {
      rt.reviewOutcome = undefined;
      rt.reviewNudged = false;
      this.enqueue({ kind: 'review', taskId });
    }
    this.save();
    this.pump();
  }

  private async review(taskId: string, agentId: string, signal: AbortSignal): Promise<void> {
    const t = this.task(taskId);
    const rt = this.rt(taskId);
    if (t.status !== 'review' || !rt.repoId || !rt.branch || !rt.worktree) return;
    const d = await this.o.repos.diff(rt.repoId, rt.branch);
    this.setAgent(agentId, 'reading', 'library', `revisiona ${taskId}`, taskId, true);
    rt.reviewOutcome = undefined;
    const res = await this.turn({
      agentId,
      cwd: rt.worktree,
      writer: false,
      repo: this.o.repos.get(rt.repoId),
      prompt: reviewPrompt(t, d.stat, d.patch, rt.ci ?? 'non eseguiti'),
      resume: this.data.leadSessions[`review-${taskId}`],
      signal,
      taskId,
    });
    if (res.sessionId) this.data.leadSessions[`review-${taskId}`] = res.sessionId;
    if (signal.aborted) return;
    if (rt.reviewOutcome) return;
    if (!rt.reviewNudged && res.ok) {
      rt.reviewNudged = true;
      this.enqueue({ kind: 'review', taskId });
      (this.data.inbox[agentId] ??= []).push(`Finish the review of ${taskId}: call request_merge or request_changes.`);
      return;
    }
    this.createMergeDecision(agentId, taskId, 'Revisione del caposquadra non completata: decidete voi.');
  }

  private async chat(agentId: string, signal: AbortSignal): Promise<void> {
    const inbox = this.drainInbox(agentId);
    if (!inbox) return;
    const agent = this.o.state.agents.get(agentId)!;
    const repo = this.defaultRepo();
    this.setAgent(agentId, 'thinking', agent.station, 'legge un messaggio');
    await this.turn({
      agentId,
      cwd: repo?.path ?? this.scratch(`chat-${agentId}`),
      writer: false,
      repo,
      prompt: `${inbox}\n\nAnswer the admins with send_message (to "user", Italian, short). ${agent.role === 'lead' ? 'If they ask for work, create tasks with create_task.' : 'If they ask you to change your current task, say so in your answer; the work continues in your next task turn.'}`,
      signal,
    });
  }

  // ------------------------------------------------------------------ one agent turn

  private async turn(t: {
    agentId: string;
    cwd: string;
    writer: boolean;
    repo: RepoRecord | undefined;
    prompt: string;
    resume?: string | undefined;
    signal: AbortSignal;
    taskId?: string;
    repoId?: string;
  }): Promise<TurnResult> {
    const agent = this.o.state.agents.get(t.agentId)!;
    const spec = ROLES[agent.role];
    const cast = this.o.cast.find((c) => c.id === agent.id);
    const team = [...this.o.state.agents.values()];
    const system = [
      basePrompt(agent, { personality: cast?.personality ?? '' }, team, this.o.serverName ?? 'Minecraft'),
      t.repo ? repoPrompt(t.repo, t.writer) : '',
    ]
      .filter(Boolean)
      .join('\n\n');
    const inbox = this.drainInbox(t.agentId);
    const rt = t.taskId ? this.rt(t.taskId) : undefined;
    const taskLeft = rt ? this.o.cfg.budget.perTaskUsd - rt.spentUsd : Infinity;
    const cap = Math.max(0.05, Math.min(taskLeft, this.budgetLeft()));
    if (rt && taskLeft <= 0.05) {
      return { ok: false, costUsd: 0, text: '', error: `budget del task esaurito (${this.o.cfg.budget.perTaskUsd} $)` };
    }
    const forbidden = t.repo?.settings.forbidden ?? [];
    const res = await this.o.runner.run({
      agentId: t.agentId,
      prompt: inbox ? `${t.prompt}\n\n${inbox}` : t.prompt,
      systemPrompt: system,
      cwd: t.cwd,
      model: this.o.cfg.models[spec.tier],
      effort: this.o.cfg.effort[spec.tier],
      maxTurns: agent.role === 'lead' ? this.o.cfg.maxTurns.lead : this.o.cfg.maxTurns.worker,
      maxBudgetUsd: Number(cap.toFixed(2)),
      ...(t.resume ? { resume: t.resume } : {}),
      env: withApiAuthOnly(
        agentEnv(this.o.baseEnv ?? process.env, t.cwd, this.identity(t.agentId)),
        join(this.o.home, 'claude-config'),
      ),
      tools: buildTools(t.agentId, agent.role === 'lead', this),
      canUseTool: (tool, input) =>
        this.permission(
          t.agentId,
          tool,
          input,
          { root: t.cwd, writer: t.writer, forbidden, frozen: rt?.frozen ?? [] },
          t.taskId,
        ),
      signal: t.signal,
      onActivity: (ev) => this.activity(t.agentId, ev.tool, ev.input, t.taskId),
    });
    this.addCost(res.costUsd, t.taskId);
    if (this.o.state.status.auth !== 'ok' && res.ok) this.o.state.setStatus({ auth: 'ok' });
    if (!res.ok && /auth|api key|401|credential/i.test(res.error ?? '')) {
      this.o.state.setStatus({ auth: 'failed', message: 'chiave API mancante o non valida' });
    }
    return res;
  }

  private async permission(
    agentId: string,
    tool: string,
    input: Record<string, unknown>,
    ctx: { root: string; writer: boolean; forbidden: string[]; frozen: string[] },
    taskId?: string,
  ): Promise<Permission> {
    const v = decide(tool, input, { ...ctx, granted: new Set(this.data.grants[agentId] ?? []) });
    if (v.kind === 'allow') return { allow: true };
    if (v.kind === 'deny') return { allow: false, message: `Not allowed by the AgentCraft policy: ${v.reason}` };
    const detail = tool === 'Bash' ? String(input.command ?? '') : JSON.stringify(input).slice(0, 500);
    const d = await this.decision(
      agentId,
      'permission',
      `Posso eseguire ${tool}? ${v.reason}`.slice(0, 500),
      PERMISSION_OPTIONS,
      `${detail}\ncartella: ${ctx.root}\n"Always allow for this agent" vale per: ${v.key}`.slice(0, 4000),
      taskId,
    );
    const option = d.answer?.option;
    if (d.status === 'cancelled') return { allow: false, message: 'the request was withdrawn' };
    if (option === 'Always allow for this agent') {
      (this.data.grants[agentId] ??= []).push(v.key);
      this.save();
      return { allow: true };
    }
    if (option === 'Allow once') return { allow: true };
    return {
      allow: false,
      message: `Denied by ${d.answer?.by ?? 'the admins'}${d.answer?.text ? `: ${d.answer.text}` : ''}`,
    };
  }

  private decision(
    agentId: string,
    kind: DecisionT['kind'],
    question: string,
    options: string[],
    context?: string,
    taskId?: string,
  ): Promise<DecisionT> {
    const s = this.o.state;
    const before = s.agents.get(agentId);
    const d = s.putDecision({
      id: s.nextId('d'),
      agentId,
      kind,
      question,
      options,
      ...(context ? { context } : {}),
      status: 'open',
      ...(taskId ? { taskId } : {}),
      createdAt: this.o.clock.now(),
    });
    this.setAgent(agentId, 'waiting_user', 'podium', `attende risposta (${d.id})`, taskId, true);
    s.notify('need_user', `${before?.name ?? agentId}: ${question}`, d.id);
    return new Promise((resolve) => {
      this.waiting.set(d.id, (answered) => {
        if (before)
          this.setAgent(
            agentId,
            before.state === 'waiting_user' ? 'thinking' : before.state,
            before.station,
            before.activity,
            taskId,
            true,
          );
        resolve(answered);
      });
    });
  }

  private createMergeDecision(agentId: string, taskId: string, notes: string): void {
    const t = this.task(taskId);
    const rt = this.rt(taskId);
    const s = this.o.state;
    s.putDecision({
      id: s.nextId('d'),
      agentId,
      kind: 'merge',
      question: `Unire ${taskId} (${t.title})?`.slice(0, 500),
      options: MERGE_OPTIONS,
      context: [
        notes,
        t.summary ? `Lavoro: ${t.summary}` : '',
        `Branch: ${rt.branch ?? '-'}`,
        `Test: ${rt.ci ?? '-'}`,
        `Diff completo: /agent diff ${taskId}`,
      ]
        .filter(Boolean)
        .join('\n')
        .slice(0, 4000),
      status: 'open',
      taskId,
      createdAt: this.o.clock.now(),
    });
    s.notify('need_user', `Merge di ${taskId} da approvare`);
  }

  private async onMergeAnswer(d: DecisionT): Promise<void> {
    const taskId = d.taskId!;
    const t = this.task(taskId);
    const rt = this.rt(taskId);
    const option = d.answer?.option;
    if (option === 'Merge' && rt.repoId && rt.branch) {
      const r = await this.o.repos.merge(
        rt.repoId,
        rt.branch,
        `AgentCraft: ${t.title} (${taskId})`,
        d.answer?.by ?? 'admin',
      );
      if (r.ok) {
        this.o.state.patchTask(taskId, {
          status: 'done',
          summary: `${t.summary ?? ''}\nUnito in ${this.o.repos.get(rt.repoId).branch} (${r.sha}).`.trim(),
        });
        this.o.state.addFeed({
          kind: 'merge',
          text: `${taskId} unito (${r.sha}) da ${d.answer?.by ?? 'admin'}. Il push lo fate voi.`,
          agentId: d.agentId,
        });
        await this.cleanup(taskId, false);
        this.goalProgress(t.goalId);
      } else if (r.conflict) {
        rt.feedback = `Your branch conflicts with ${this.o.repos.get(rt.repoId).branch}. Run \`git merge ${this.o.repos.get(rt.repoId).branch}\` in your worktree, resolve the conflicts, run the tests, then call update_task with status "review".`;
        this.o.state.patchTask(taskId, { status: 'doing' });
        this.o.state.addFeed({ kind: 'merge', text: `${taskId}: conflitto, torna a ${t.assignee}` });
        this.enqueue({ kind: 'work', taskId });
      } else {
        this.o.state.notify('warn', `Merge di ${taskId} non eseguito: ${r.reason}`);
        this.createMergeDecision(d.agentId, taskId, `Merge non eseguito: ${r.reason}. Sistemate e riprovate.`);
      }
    } else if (option === 'Request changes') {
      rt.feedback = `The admins asked for changes${d.answer?.by ? ` (${d.answer.by})` : ''}: ${d.answer?.text ?? '(no details: ask them with ask_user)'}`;
      this.o.state.patchTask(taskId, { status: 'doing' });
      this.enqueue({ kind: 'work', taskId });
    } else if (option === 'Reject') {
      this.o.state.patchTask(taskId, {
        status: 'cancelled',
        summary: `${t.summary ?? ''}\nRifiutato da ${d.answer?.by ?? 'admin'}.`.trim(),
      });
      await this.cleanup(taskId, false);
      this.goalProgress(t.goalId);
    }
    this.save();
    this.pump();
  }

  private async cleanup(taskId: string, cancel = true): Promise<void> {
    const rt = this.rt(taskId);
    if (cancel) this.o.state.patchTask(taskId, { status: 'cancelled' });
    if (rt.repoId && rt.worktree) {
      try {
        await this.o.repos.removeWorktree(rt.repoId, rt.worktree);
      } catch (e) {
        this.log(`could not remove worktree ${rt.worktree}: ${e instanceof Error ? e.message : String(e)}`);
      }
      rt.worktree = undefined;
    }
    const t = this.o.state.tasks.get(taskId);
    if (cancel && t) this.goalProgress(t.goalId);
    this.save();
  }

  private goalProgress(goalId: string | undefined): void {
    if (!goalId) return;
    const tasks = [...this.o.state.tasks.values()].filter((t) => t.goalId === goalId && t.status !== 'cancelled');
    const done = tasks.filter((t) => t.status === 'done').length;
    const all = [...this.o.state.tasks.values()].filter((t) => t.goalId === goalId);
    if (all.length > 0 && tasks.length === 0) this.o.state.patchGoal(goalId, { status: 'cancelled', progress: 1 });
    else if (tasks.length > 0 && done === tasks.length) {
      this.o.state.patchGoal(goalId, { status: 'done', progress: 1 });
      this.o.state.notify('info', `Obiettivo ${goalId} completato.`);
    } else this.o.state.patchGoal(goalId, { progress: tasks.length ? done / tasks.length : 0 });
  }

  // ------------------------------------------------------------------ TeamApi (team tools)

  sendMessage(from: string, to: string, text: string): string {
    if (to === 'user') {
      this.o.state.addFeed({ kind: 'message', text, agentId: from, to: 'user' });
      this.o.state.notify('info', `${this.o.state.agents.get(from)?.name ?? from}: ${text}`.slice(0, 500));
      return 'Sent to the admins.';
    }
    const targets =
      to === 'all' ? [...this.o.state.agents.values()].filter((a) => a.active && a.id !== from).map((a) => a.id) : [to];
    for (const id of targets) {
      if (!this.o.state.agents.has(id)) throw new Error(`unknown agent '${id}'`);
      (this.data.inbox[id] ??= []).push(`Message from ${from}: ${text}`);
    }
    this.o.state.addFeed({ kind: 'message', text, agentId: from, to });
    this.save();
    return `Sent to ${targets.join(', ')}.`;
  }

  async askUser(from: string, question: string, options: string[], context?: string): Promise<string> {
    const taskId = this.o.state.agents.get(from)?.taskId;
    const d = await this.decision(from, 'question', question, options.slice(0, 6), context, taskId);
    if (d.status === 'cancelled') return 'The question was withdrawn. Continue with your best judgement.';
    return `Answer from ${d.answer?.by ?? 'the admins'}: ${[d.answer?.option, d.answer?.text].filter(Boolean).join(' — ')}`;
  }

  readMemory(agentId: string, scope: string, name?: string): string {
    const sc = scope === 'mine' ? agentId : 'shared';
    if (!name) {
      const list = this.o.memory.list(sc);
      return list.length ? `Notes (${scope}): ${list.join(', ')}` : `No notes in ${scope}.`;
    }
    return this.o.memory.read(sc, name) ?? `No note '${name}' in ${scope}.`;
  }

  writeMemory(agentId: string, scope: string, title: string, body: string): string {
    const id = this.o.memory.write(scope === 'mine' ? agentId : 'shared', title, body);
    this.o.state.addFeed({ kind: 'plan', text: `Nota: ${title}`, agentId });
    return `Saved as ${id}.`;
  }

  listTasks(): string {
    const lines = [...this.o.state.tasks.values()]
      .filter((t) => t.status !== 'cancelled')
      .map(
        (t) =>
          `${t.id} [${t.status}] ${t.title}${t.assignee ? ` @${t.assignee}` : ''}${t.deps.length ? ` deps:${t.deps.join(',')}` : ''}${t.module ? ` module:${t.module}` : ''}`,
      );
    return lines.length ? lines.join('\n') : 'The board is empty.';
  }

  updateTask(agentId: string, status: 'review' | 'blocked', summary: string, module?: string): string {
    const t = [...this.o.state.tasks.values()].find((x) => x.assignee === agentId && x.status === 'doing');
    if (!t) throw new Error('you have no task in progress');
    this.o.state.patchTask(t.id, {
      status,
      summary,
      ...(module ? { module } : {}),
      ...(status === 'blocked' ? { blockedReason: summary.slice(0, 500) } : {}),
    });
    this.o.state.addFeed({
      kind: 'task',
      text: `${t.id} → ${status === 'review' ? 'in revisione' : 'bloccato'}`,
      agentId,
    });
    return status === 'review'
      ? `Task ${t.id} handed in: the foreman commits and runs the tests, then the lead reviews. End your turn now.`
      : `Task ${t.id} marked blocked. End your turn.`;
  }

  reportStatus(agentId: string, activity: string): string {
    this.o.state.patchAgent(agentId, { activity: activity.slice(0, 48) });
    return 'Status updated.';
  }

  createTask(
    from: string,
    a: {
      title: string;
      description?: string;
      kind: string;
      assignee?: string;
      deps: string[];
      module?: string;
      paths: string[];
      priority?: number;
    },
  ): string {
    const s = this.o.state;
    const goal = [...s.goals.values()].filter((g) => g.status === 'planning' || g.status === 'active').pop();
    for (const d of a.deps) if (!s.tasks.has(d)) throw new Error(`unknown dependency '${d}'`);
    if (a.assignee && !s.agents.has(a.assignee)) throw new Error(`unknown agent '${a.assignee}'`);
    const kind = (['code', 'qa', 'analysis', 'generic'].includes(a.kind) ? a.kind : 'generic') as TaskT['kind'];
    const now = this.o.clock.now();
    const t = s.putTask({
      id: s.nextId('t'),
      title: a.title,
      ...(a.description ? { description: a.description } : {}),
      kind,
      status: 'todo',
      ...(a.assignee ? { assignee: a.assignee } : {}),
      deps: a.deps,
      ...(goal ? { goalId: goal.id } : {}),
      ...(this.defaultRepo() ? { repoId: this.defaultRepo()!.id } : {}),
      priority: a.priority ?? 0,
      ...(a.module ? { module: a.module } : {}),
      ci: 'unknown',
      createdBy: from,
      createdAt: now,
      updatedAt: now,
    });
    const rt = this.rt(t.id);
    rt.paths = a.paths.filter((p) => !p.includes('..'));
    const repo = this.defaultRepo();
    if (repo) rt.repoId = repo.id;
    this.save();
    this.pump();
    return `Created ${t.id}.`;
  }

  requestMerge(from: string, taskId: string, notes: string): string {
    const t = this.task(taskId);
    if (t.status !== 'review') throw new Error(`task ${taskId} is ${t.status}, not in review`);
    this.rt(taskId).reviewOutcome = 'merge';
    this.createMergeDecision(from, taskId, notes);
    return 'Merge requested: the admins decide in game. End your turn.';
  }

  requestChanges(from: string, taskId: string, feedback: string): string {
    const t = this.task(taskId);
    if (t.status !== 'review') throw new Error(`task ${taskId} is ${t.status}, not in review`);
    const rt = this.rt(taskId);
    rt.reviewOutcome = 'changes';
    rt.feedback = `The lead (${from}) asked for changes: ${feedback}`;
    this.o.state.patchTask(taskId, { status: 'doing' });
    this.enqueue({ kind: 'work', taskId });
    return `Sent back to ${t.assignee}. End your turn.`;
  }

  drainInbox(agentId: string): string {
    const msgs = this.data.inbox[agentId];
    if (!msgs?.length) return '';
    this.data.inbox[agentId] = [];
    this.save();
    return `Unread messages:\n${msgs.map((m) => `- ${m}`).join('\n')}`;
  }

  // ------------------------------------------------------------------ helpers

  private task(id: string): TaskT {
    const t = this.o.state.tasks.get(id);
    if (!t) throw new Error(`unknown task '${id}'`);
    return t;
  }

  private rt(taskId: string): TaskRuntime {
    return (this.data.runtime[taskId] ??= {
      paths: [],
      sessions: {},
      ciFailures: 0,
      nudged: false,
      reviewNudged: false,
      spentUsd: 0,
    });
  }

  private scratch(name: string): string {
    const d = join(this.o.home, 'scratch', name);
    mkdirSync(d, { recursive: true });
    return d;
  }

  private identity(agentId: string): { name: string; email: string } {
    const name = this.o.state.agents.get(agentId)?.name ?? agentId;
    return { name: `AgentCraft ${name}`, email: `${agentId}@agentcraft.local` };
  }

  private setAgent(
    id: string,
    state: AgentT['state'],
    station: AgentT['station'],
    activity: string,
    taskId?: string,
    forceStation = false,
  ): void {
    const a = this.o.state.agents.get(id);
    if (!a) return;
    const now = this.o.clock.now();
    const last = this.lastStation.get(id) ?? 0;
    const move = station !== a.station && (forceStation || now - last >= STATION_MIN_MS);
    if (move) this.lastStation.set(id, now);
    this.o.state.patchAgent(id, {
      state,
      activity: activity.slice(0, 64),
      station: move ? station : a.station,
      taskId,
    });
  }

  private activity(
    agentId: string,
    tool: string | undefined,
    input: Record<string, unknown> | undefined,
    taskId?: string,
  ): void {
    if (!tool) return;
    const file = (input?.file_path ?? input?.path ?? input?.pattern) as string | undefined;
    const short = file ? basename(String(file)) : '';
    if (['Read', 'Grep', 'Glob', 'LS'].includes(tool))
      this.setAgent(agentId, 'reading', 'library', `legge ${short}`.trim(), taskId);
    else if (['Edit', 'Write', 'MultiEdit', 'NotebookEdit'].includes(tool))
      this.setAgent(agentId, 'editing', 'desk', `modifica ${short}`.trim(), taskId);
    else if (tool === 'Bash') {
      const cmd = String(input?.command ?? '');
      if (/\b(mvn|gradle|gradlew|npm test|pytest|junit)\b/.test(cmd))
        this.setAgent(agentId, 'testing', 'testbench', 'esegue build e test', taskId);
      else this.setAgent(agentId, 'running', 'terminal', cmd.split(/\s+/)[0] ?? 'terminale', taskId);
    }
  }

  private rollBudget(): void {
    const iso = new Date(this.o.clock.now()).toISOString();
    const day = iso.slice(0, 10);
    const month = iso.slice(0, 7);
    const b = this.data.budget;
    if (b.day !== day) Object.assign(b, { day, dayUsd: 0 });
    if (b.month !== month) Object.assign(b, { month, monthUsd: 0 });
  }

  private addCost(usd: number, taskId?: string): void {
    if (!usd) return;
    this.rollBudget();
    this.data.budget.dayUsd += usd;
    this.data.budget.monthUsd += usd;
    if (taskId) this.rt(taskId).spentUsd += usd;
    this.updateBudgetStatus();
    this.save();
  }

  private updateBudgetStatus(): void {
    this.rollBudget();
    const b = this.data.budget;
    this.o.state.setStatus({
      budget: {
        spentTodayUsd: Number(b.dayUsd.toFixed(4)),
        dailyUsd: this.o.cfg.budget.dailyUsd,
        spentMonthUsd: Number(b.monthUsd.toFixed(4)),
        monthlyUsd: this.o.cfg.budget.monthlyUsd,
      },
    });
  }
}
