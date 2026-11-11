import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { Runner, TurnResult, TurnSpec } from '../src/agents/runner.js';
import { TeamBackend } from '../src/agents/team.js';
import { DEFAULT_CAST } from '../src/cast.js';
import { realClock } from '../src/clock.js';
import { ConfigSchema } from '../src/config.js';
import { buildTeam, Foreman } from '../src/foreman.js';
import { Memory } from '../src/memory.js';
import { RepoManager } from '../src/repos.js';
import type { DecisionT } from '../src/state.js';
import { Store } from '../src/store.js';
import { actor, newState } from './helpers.js';

const sh = (cwd: string, ...args: string[]) => execFileSync('git', args, { cwd, stdio: 'pipe' }).toString();

/** A small repository laid out like castigo-ecosistema: one folder per plugin version. */
function fixtureRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), 'ac-repo-'));
  sh(dir, 'init', '-q', '-b', 'main');
  sh(dir, 'config', 'user.name', 'Admin');
  sh(dir, 'config', 'user.email', 'admin@example.com');
  mkdirSync(join(dir, 'modules/DemoPlugin-1.0.0-alpha.1'), { recursive: true });
  mkdirSync(join(dir, 'modules/CastigoMagia-1.0.0'), { recursive: true });
  writeFileSync(join(dir, 'modules/DemoPlugin-1.0.0-alpha.1/hello.txt'), 'ciao\n');
  writeFileSync(join(dir, 'modules/CastigoMagia-1.0.0/x.txt'), 'escluso\n');
  writeFileSync(join(dir, 'AGENTS.md'), '# Regole\nUna cartella per versione.\n');
  // the "test suite": the newest DemoPlugin folder must say "ciao mondo"
  writeFileSync(
    join(dir, 'check.mjs'),
    `import { readdirSync, readFileSync } from 'node:fs';
const last = readdirSync('modules').filter((d) => d.startsWith('DemoPlugin-')).sort().pop();
const text = readFileSync('modules/' + last + '/hello.txt', 'utf8');
if (text.trim() !== 'ciao mondo') { console.error('FAIL: ' + last + ' says ' + text.trim()); process.exit(1); }
console.log('ok ' + last);\n`,
  );
  sh(dir, 'add', '-A');
  sh(dir, 'commit', '-q', '-m', 'init');
  return dir;
}

type Script = (
  spec: TurnSpec,
  call: (tool: string, args: Record<string, unknown>) => Promise<string>,
  n: number,
) => Promise<void>;

class ScriptedRunner implements Runner {
  readonly turns: Array<{ agentId: string; cwd: string }> = [];
  constructor(private readonly scripts: Record<string, Script>) {}
  async run(spec: TurnSpec): Promise<TurnResult> {
    const n = this.turns.filter((t) => t.agentId === spec.agentId).length;
    this.turns.push({ agentId: spec.agentId, cwd: spec.cwd });
    const call = async (tool: string, args: Record<string, unknown>) => {
      const t = spec.tools.find((x) => x.name === tool);
      if (!t) throw new Error(`no tool ${tool} for ${spec.agentId}`);
      return t.handler(args);
    };
    await this.scripts[spec.agentId]?.(spec, call, n);
    return { ok: true, sessionId: `s-${spec.agentId}-${n}`, costUsd: 0.01, text: 'fatto' };
  }
}

let cleanup: Array<() => void> = [];
afterEach(() => {
  cleanup.forEach((f) => f());
  cleanup = [];
});

function setup(scripts: Record<string, Script>, noAuth = false, login = false) {
  const repoPath = fixtureRepo();
  const home = mkdtempSync(join(tmpdir(), 'ac-home-'));
  const cfg = ConfigSchema.parse({ backend: 'claude', useClaudeLogin: login });
  const state = newState();
  for (const a of buildTeam(cfg)) state.agents.set(a.id, a);
  const repos = new RepoManager(join(home, 'worktrees'));
  state.reposView = () => [...repos.repos.values()].map((r) => RepoManager.view(r));
  const runner = new ScriptedRunner(scripts);
  const team = new TeamBackend({
    cfg,
    state,
    repos,
    memory: new Memory(join(home, 'memory')),
    runner,
    store: new Store(home),
    clock: realClock,
    cast: DEFAULT_CAST,
    home,
    baseEnv: { ...process.env, ANTHROPIC_API_KEY: noAuth ? '' : 'test-key', CLAUDE_CODE_OAUTH_TOKEN: 'personal-login' },
  });
  const foreman = new Foreman(state, realClock, team);
  cleanup.push(() => team.stop());
  const open = async (kind: string): Promise<DecisionT> => {
    let found: DecisionT | undefined;
    await expect
      .poll(() => (found = [...state.decisions.values()].find((d) => d.status === 'open' && d.kind === kind)), {
        timeout: 15_000,
        interval: 20,
      })
      .toBeTruthy();
    return found!;
  };
  return { repoPath, home, state, team, foreman, runner, open };
}

describe('team backend (scripted agents, real git)', () => {
  it('plans, works in a new version folder, retries after failing tests, reviews and merges after approval', async () => {
    const denied: string[] = [];
    const ctx = setup({
      bussola: async (spec, call, n) => {
        if (spec.prompt.includes('New goal')) {
          expect(spec.cwd).not.toContain('worktrees'); // the lead plans in the user's checkout
          expect(spec.systemPrompt).toContain('AGENTS.md');
          await call('write_memory', {
            scope: 'shared',
            title: 'Piano g1',
            body: 'Correggere il saluto in DemoPlugin.',
          });
          await call('create_task', {
            title: 'Saluto completo',
            kind: 'code',
            assignee: 'lucciola',
            deps: [],
            module: 'modules/DemoPlugin-1.0.0-alpha.1',
            paths: ['modules/DemoPlugin-1.0.0-alpha.1'],
          });
        } else if (spec.prompt.includes('Review task')) {
          expect(spec.prompt).toContain('ciao mondo');
          await call('request_merge', { taskId: 't1', notes: 'Corretto, test ok.' });
        }
        void n;
      },
      lucciola: async (spec, call, n) => {
        expect(spec.cwd).toContain('worktrees');
        expect(spec.env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
        expect(spec.env.CLAUDE_CONFIG_DIR).toContain('claude-config');
        expect(spec.env.GIT_ALLOW_PROTOCOL).toBe('agentcraft-none');
        expect(spec.systemPrompt).toContain('Never edit an existing version folder');
        const next = join(spec.cwd, 'modules/DemoPlugin-1.0.0-alpha.2');
        // forbidden and dangerous things are refused by the policy
        for (const [tool, input] of [
          ['Bash', { command: 'git push origin main' }],
          ['Write', { file_path: join(spec.cwd, 'modules/CastigoMagia-1.0.0/x.txt') }],
          ['Write', { file_path: join(spec.cwd, 'modules/DemoPlugin-1.0.0-alpha.1/hello.txt') }],
        ] as const) {
          const p = await spec.canUseTool(tool, input as Record<string, unknown>);
          if (!p.allow) denied.push(`${tool}`);
        }
        expect(
          (
            await spec.canUseTool('Bash', {
              command: `cp -r modules/DemoPlugin-1.0.0-alpha.1 modules/DemoPlugin-1.0.0-alpha.2`,
            })
          ).allow,
        ).toBe(true);
        if (!existsSync(next)) cpSync(join(spec.cwd, 'modules/DemoPlugin-1.0.0-alpha.1'), next, { recursive: true });
        expect((await spec.canUseTool('Write', { file_path: join(next, 'hello.txt') })).allow).toBe(true);
        // first attempt is wrong on purpose: the CI must send it back once
        writeFileSync(join(next, 'hello.txt'), n === 0 ? 'ciao\n' : 'ciao mondo\n');
        if (n === 1) expect(spec.prompt).toContain('tests failed');
        await call('update_task', {
          status: 'review',
          summary: 'Nuova versione alpha.2 con il saluto completo.',
          module: 'modules/DemoPlugin-1.0.0-alpha.2',
        });
      },
    });
    // the CI of this repo is our check script
    await ctx.team.init([
      { path: ctx.repoPath, ci: 'node check.mjs', forbidden: ['CastigoMagia', 'CastigoClassi', 'CastigoCombat'] },
    ]);
    expect(ctx.state.reposView()[0]).toMatchObject({ versioning: 'new-folder', branch: 'main' });
    ctx.team.start();

    const { goalId } = ctx.foreman.handle({
      v: 1,
      type: 'goal.submit',
      actor,
      text: 'Il saluto deve dire ciao mondo',
    }) as { goalId: string };
    const merge = await ctx.open('merge');
    expect(merge.taskId).toBe('t1');
    expect(ctx.state.tasks.get('t1')).toMatchObject({
      status: 'review',
      ci: 'pass',
      module: 'modules/DemoPlugin-1.0.0-alpha.2',
    });
    expect(denied).toEqual(['Bash', 'Write', 'Write', 'Bash', 'Write', 'Write']); // checked in both worker turns
    // nothing reached the user's checkout before the approval
    expect(existsSync(join(ctx.repoPath, 'modules/DemoPlugin-1.0.0-alpha.2'))).toBe(false);

    ctx.foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: merge.id, option: 'Merge' });
    await expect.poll(() => ctx.state.goals.get(goalId)?.status, { timeout: 15_000, interval: 20 }).toBe('done');
    expect(ctx.state.tasks.get('t1')?.status).toBe('done');
    expect(readFileSync(join(ctx.repoPath, 'modules/DemoPlugin-1.0.0-alpha.2/hello.txt'), 'utf8')).toBe('ciao mondo\n');
    expect(readFileSync(join(ctx.repoPath, 'modules/DemoPlugin-1.0.0-alpha.1/hello.txt'), 'utf8')).toBe('ciao\n');
    const log = sh(ctx.repoPath, 'log', '--format=%an|%s', '-3');
    expect(log).toContain('AgentCraft Lucciola|t1: Saluto completo');
    expect(log.split('\n')[0]).toContain('Admin|AgentCraft: Saluto completo (t1)');
    expect(ctx.runner.turns.map((t) => t.agentId)).toEqual(['bussola', 'lucciola', 'lucciola', 'bussola']);
    expect(existsSync(join(ctx.home, 'memory/shared/piano-g1.md'))).toBe(true);
    expect(ctx.state.status.budget?.spentTodayUsd).toBeCloseTo(0.04);
  }, 60_000);

  it('asks the admins in game for unknown commands and remembers "Always allow"', async () => {
    const results: boolean[] = [];
    const ctx = setup({
      bussola: async (spec, call) => {
        if (spec.prompt.includes('New goal'))
          await call('create_task', { title: 'Indagine', kind: 'code', assignee: 'lucciola', deps: [], paths: [] });
      },
      lucciola: async (spec, call) => {
        results.push((await spec.canUseTool('Bash', { command: 'curl https://example.com' })).allow);
        results.push((await spec.canUseTool('Bash', { command: 'curl https://example.org' })).allow); // granted now
        await call('update_task', { status: 'review', summary: 'Solo lettura.' });
      },
    });
    await ctx.team.init([{ path: ctx.repoPath, ci: 'node check.mjs' }]);
    ctx.team.start();
    ctx.foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'indaga' });
    const p = await ctx.open('permission');
    expect(p.options).toEqual(['Allow once', 'Always allow for this agent', 'Deny']);
    expect(p.context).toContain('Bash:net:curl');
    ctx.foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: p.id, option: '2' });
    await expect.poll(() => ctx.state.tasks.get('t1')?.status, { timeout: 15_000, interval: 20 }).toBe('done');
    expect(results).toEqual([true, true]);
    expect([...ctx.state.decisions.values()].filter((d) => d.kind === 'permission')).toHaveLength(1);
  }, 60_000);

  it('sends rejected work back and keeps the checkout untouched', async () => {
    const ctx = setup({
      bussola: async (spec, call) => {
        if (spec.prompt.includes('New goal'))
          await call('create_task', {
            title: 'Prova',
            kind: 'code',
            assignee: 'lucciola',
            deps: [],
            paths: ['modules/DemoPlugin-1.0.0-alpha.1'],
          });
        else await call('request_merge', { taskId: 't1', notes: 'ok' });
      },
      lucciola: async (spec, call) => {
        const next = join(spec.cwd, 'modules/DemoPlugin-1.0.0-alpha.2');
        cpSync(join(spec.cwd, 'modules/DemoPlugin-1.0.0-alpha.1'), next, { recursive: true });
        writeFileSync(join(next, 'hello.txt'), 'ciao mondo\n');
        await call('update_task', { status: 'review', summary: 'fatto' });
      },
    });
    await ctx.team.init([{ path: ctx.repoPath, ci: 'node check.mjs' }]);
    ctx.team.start();
    const { goalId } = ctx.foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'prova' }) as { goalId: string };
    const merge = await ctx.open('merge');
    ctx.foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: merge.id, option: 'Reject' });
    await expect.poll(() => ctx.state.goals.get(goalId)?.status, { timeout: 15_000, interval: 20 }).toBe('cancelled');
    expect(existsSync(join(ctx.repoPath, 'modules/DemoPlugin-1.0.0-alpha.2'))).toBe(false);
    expect(sh(ctx.repoPath, 'branch', '--list', 'agentcraft/*')).toContain('agentcraft/lucciola/t1-prova'); // branch kept
  }, 60_000);

  it('resumes unfinished work after a restart of the foreman', async () => {
    const first = setup({
      bussola: async (spec, call) => {
        if (spec.prompt.includes('New goal'))
          await call('create_task', {
            title: 'Dopo il riavvio',
            kind: 'code',
            assignee: 'lucciola',
            deps: [],
            paths: [],
          });
      },
    });
    await first.team.init([{ path: first.repoPath, ci: 'node -e 0' }]);
    first.team.start();
    // Lucciola is paused: the task is planned and waiting when the foreman "crashes"
    first.foreman.handle({ v: 1, type: 'agent.action', actor, agentId: 'lucciola', action: 'pause' });
    first.foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'riavvio' });
    await expect.poll(() => first.state.tasks.get('t1')?.status, { timeout: 15_000, interval: 20 }).toBe('doing');
    first.team.stop();
    const saved = first.state.exportData();

    // a new foreman process on the same home folder
    const state = newState();
    const cfg = ConfigSchema.parse({ backend: 'claude' });
    for (const a of buildTeam(cfg)) state.agents.set(a.id, a);
    state.importData(saved);
    const runner = new ScriptedRunner({
      lucciola: async (spec, call) => {
        writeFileSync(join(spec.cwd, 'ripreso.txt'), 'ok\n');
        await call('update_task', { status: 'review', summary: 'ripreso dopo il riavvio' });
      },
      bussola: async (_spec, call) => {
        await call('request_merge', { taskId: 't1', notes: 'ok' });
      },
    });
    const team = new TeamBackend({
      cfg,
      state,
      repos: new RepoManager(join(first.home, 'worktrees')),
      memory: new Memory(join(first.home, 'memory')),
      runner,
      store: new Store(first.home),
      clock: realClock,
      cast: DEFAULT_CAST,
      home: first.home,
      baseEnv: { ...process.env, ANTHROPIC_API_KEY: 'test-key' },
    });
    cleanup.push(() => team.stop());
    const foreman = new Foreman(state, realClock, team);
    await team.init([{ path: first.repoPath, ci: 'node -e 0' }]);
    team.start();
    foreman.handle({ v: 1, type: 'agent.action', actor, agentId: 'lucciola', action: 'resume' });
    await expect
      .poll(() => [...state.decisions.values()].find((d) => d.kind === 'merge' && d.status === 'open')?.taskId, {
        timeout: 15_000,
        interval: 20,
      })
      .toBe('t1');
    expect(runner.turns.map((t) => t.agentId)).toEqual(['lucciola', 'bussola']);
  }, 60_000);

  it('publishes the base branch only when a person asks (/agent push), never forced', async () => {
    const ctx = setup({});
    const bare = mkdtempSync(join(tmpdir(), 'ac-remote-'));
    sh(bare, 'init', '-q', '--bare', '-b', 'main');
    sh(ctx.repoPath, 'remote', 'add', 'origin', bare);
    await ctx.team.init([{ path: ctx.repoPath, push: { branch: 'agentcraft/lavori' } }]);
    ctx.team.start();
    const r = (await ctx.foreman.handle({ v: 1, type: 'repo.push', actor })) as { branch: string; sha: string };
    expect(r.branch).toBe('agentcraft/lavori');
    expect(sh(bare, 'rev-parse', '--short', 'agentcraft/lavori').trim()).toBe(r.sha);
    expect(ctx.state.feed.at(-1)?.text).toContain('ha pubblicato');
    // agents have no tool for it, and their git cannot push
    expect([...ctx.runner.turns]).toHaveLength(0);
  });

  it('subscription mode (opt-in): agents get the subscription token and never an API key', async () => {
    let seen: NodeJS.ProcessEnv | undefined;
    const ctx = setup(
      {
        bussola: async (spec) => {
          seen = spec.env;
        },
      },
      false,
      true,
    );
    await ctx.team.init([{ path: ctx.repoPath }]);
    ctx.team.start();
    expect(ctx.state.status.message).toContain('abbonamento');
    ctx.foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'prova' });
    await expect.poll(() => seen, { timeout: 10_000, interval: 20 }).toBeTruthy();
    expect(seen!.CLAUDE_CODE_OAUTH_TOKEN).toBe('personal-login');
    expect(seen!.ANTHROPIC_API_KEY).toBeUndefined();
    expect(seen!.CLAUDE_CONFIG_DIR).toContain('claude-config');
  });

  it('never runs an agent without API authentication (no fallback to a personal login)', async () => {
    const ctx = setup({ bussola: async () => {} }, true);
    await ctx.team.init([{ path: ctx.repoPath }]);
    ctx.team.start();
    ctx.foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'prova' });
    await new Promise((r) => setTimeout(r, 300));
    expect(ctx.runner.turns).toHaveLength(0);
    expect(ctx.state.status).toMatchObject({ auth: 'failed' });
    expect(ctx.state.status.message).toContain('ANTHROPIC_API_KEY');
  });

  it('refuses the merge while the admins have uncommitted changes in the checkout', async () => {
    const ctx = setup({
      bussola: async (spec, call) => {
        if (spec.prompt.includes('New goal'))
          await call('create_task', { title: 'Prova', kind: 'code', assignee: 'lucciola', deps: [], paths: [] });
        else await call('request_merge', { taskId: 't1', notes: 'ok' });
      },
      lucciola: async (spec, call) => {
        writeFileSync(join(spec.cwd, 'nuovo.txt'), 'x\n');
        await call('update_task', { status: 'review', summary: 'fatto' });
      },
    });
    await ctx.team.init([{ path: ctx.repoPath, ci: 'node -e 0' }]);
    ctx.team.start();
    ctx.foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'prova' });
    const merge = await ctx.open('merge');
    writeFileSync(join(ctx.repoPath, 'AGENTS.md'), 'modifica locale non salvata\n');
    ctx.foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: merge.id, option: 'Merge' });
    const again = await ctx.open('merge');
    expect(again.context).toContain('uncommitted');
    expect(existsSync(join(ctx.repoPath, 'nuovo.txt'))).toBe(false);
  }, 60_000);
});
