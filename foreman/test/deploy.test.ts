import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { ToolInvocation } from '@agentcraft-server/protocol';
import { afterEach, describe, expect, it } from 'vitest';
import type { Runner, TurnResult, TurnSpec } from '../src/agents/runner.js';
import { TeamBackend } from '../src/agents/team.js';
import type { ToolPort } from '../src/backend.js';
import type { ToolOutcome } from '../src/bridge.js';
import { DEFAULT_CAST } from '../src/cast.js';
import { realClock } from '../src/clock.js';
import { ConfigSchema } from '../src/config.js';
import { pickJar, readPluginInfo } from '../src/deploy.js';
import { buildTeam, Foreman } from '../src/foreman.js';
import { Memory } from '../src/memory.js';
import { RepoManager } from '../src/repos.js';
import type { DecisionT } from '../src/state.js';
import { Store } from '../src/store.js';
import { actor, newState } from './helpers.js';

const sh = (cwd: string, ...args: string[]) => execFileSync('git', args, { cwd, stdio: 'pipe' }).toString();

const PLUGIN_YML = `name: DemoPlugin
version: '\${project.version}'
main: demo.Main
commands:
  demo:
    description: demo
  demoadmin:
    permission: demo.admin
`;

function pluginRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), 'ac-plugin-'));
  sh(dir, 'init', '-q', '-b', 'main');
  sh(dir, 'config', 'user.name', 'Admin');
  sh(dir, 'config', 'user.email', 'admin@example.com');
  mkdirSync(join(dir, 'DemoPlugin/src/main/resources'), { recursive: true });
  writeFileSync(
    join(dir, 'DemoPlugin/pom.xml'),
    '<project><parent><version>9</version></parent><artifactId>demo</artifactId><version>1.2.0</version></project>\n',
  );
  writeFileSync(join(dir, 'DemoPlugin/src/main/resources/plugin.yml'), PLUGIN_YML);
  writeFileSync(join(dir, 'DemoPlugin/README.md'), 'demo\n');
  sh(dir, 'add', '-A');
  sh(dir, 'commit', '-q', '-m', 'init');
  return dir;
}

class FakeServer implements ToolPort {
  readonly calls: Array<{ agentId: string; call: ToolInvocation }> = [];
  async callTool(agentId: string, call: ToolInvocation): Promise<ToolOutcome> {
    this.calls.push({ agentId, call });
    if (call.tool === 'server.deploy') return { ok: true, result: { installedAs: 'update/DemoPlugin.jar' } };
    if (call.tool === 'server.metrics') return { ok: true, result: { tps1m5m15m: [20, 20, 20], plugins: [] } };
    if (call.tool === 'player.simulate') return { ok: true, result: { results: [{ kind: 'command', ok: true }] } };
    return { ok: true, result: {} };
  }
}

let stops: Array<() => void> = [];
afterEach(() => {
  stops.forEach((f) => f());
  stops = [];
});

describe('deploy to staging and in-game QA', () => {
  it('reads plugin.yml and picks the shaded jar', () => {
    const repo = pluginRepo();
    expect(readPluginInfo(repo, 'DemoPlugin')).toEqual({
      name: 'DemoPlugin',
      version: '1.2.0',
      commands: ['demo', 'demoadmin'],
    });
    const target = join(repo, 'DemoPlugin/target');
    mkdirSync(target);
    writeFileSync(join(target, 'demo-1.2.0.jar'), 'x'.repeat(100));
    writeFileSync(join(target, 'original-demo-1.2.0.jar'), 'x'.repeat(10));
    writeFileSync(join(target, 'demo-1.2.0-sources.jar'), 'x'.repeat(1000));
    expect(pickJar(target)).toBe(join(target, 'demo-1.2.0.jar'));
  });

  it('merge → build jar → install and restart after approval → Briciola tests in game → bug fix task', async () => {
    const repoPath = pluginRepo();
    const home = mkdtempSync(join(tmpdir(), 'ac-home-'));
    const cfg = ConfigSchema.parse({ backend: 'claude' });
    const state = newState();
    for (const a of buildTeam(cfg)) state.agents.set(a.id, a);
    const server = new FakeServer();
    let qaTools: string[] = [];
    const runner: Runner = {
      async run(spec: TurnSpec): Promise<TurnResult> {
        const call = (name: string, args: Record<string, unknown>) => {
          const t = spec.tools.find((x) => x.name === name);
          if (!t) throw new Error(`no tool ${name} for ${spec.agentId}`);
          return t.handler(args);
        };
        if (spec.agentId === 'bussola' && spec.prompt.includes('New goal')) {
          await call('create_task', {
            title: 'Messaggio di benvenuto',
            kind: 'code',
            assignee: 'lucciola',
            deps: [],
            module: 'DemoPlugin',
            paths: ['DemoPlugin'],
          });
        } else if (spec.agentId === 'bussola' && spec.prompt.includes('Review task')) {
          await call('request_merge', { taskId: 't1', notes: 'ok' });
        } else if (spec.agentId === 'lucciola') {
          writeFileSync(join(spec.cwd, 'DemoPlugin/README.md'), 'demo con benvenuto\n');
          await call('update_task', { status: 'review', summary: 'Aggiunto il benvenuto', module: 'DemoPlugin' });
        } else if (spec.agentId === 'briciola' && spec.prompt.includes('In-game test')) {
          qaTools = spec.tools.map((t) => t.name);
          expect(spec.prompt).toContain('/demo, /demoadmin');
          expect(await call('server_metrics', {})).toContain('tps');
          expect(await call('player_simulate', { actions: [{ kind: 'command', command: 'demo' }] })).toContain('ok');
          await call('qa_report', {
            passed: false,
            summary: 'Il benvenuto non compare',
            bugs: [
              { title: 'Nessun benvenuto', steps: '/demo', expected: 'messaggio', actual: 'niente', severity: 'high' },
            ],
          });
        }
        return { ok: true, costUsd: 0.01, text: 'ok', sessionId: `s-${spec.agentId}` };
      },
    };
    const team = new TeamBackend({
      cfg,
      state,
      repos: new RepoManager(join(home, 'worktrees')),
      memory: new Memory(join(home, 'memory')),
      runner,
      store: new Store(home),
      clock: realClock,
      cast: DEFAULT_CAST,
      home,
      baseEnv: { ...process.env, ANTHROPIC_API_KEY: 'k' },
      tools: server,
      ingameDelayMs: 10,
      packager: async (worktree) => {
        const jar = join(worktree, 'DemoPlugin/target/demo-1.2.0.jar');
        mkdirSync(join(worktree, 'DemoPlugin/target'), { recursive: true });
        writeFileSync(jar, 'PK fake jar');
        return { jar };
      },
    });
    stops.push(() => team.stop());
    const foreman = new Foreman(state, realClock, team);
    await team.init([{ path: repoPath, ci: 'node -e 0' }]);
    team.start();
    const open = async (kind: string, question?: string): Promise<DecisionT> => {
      let found: DecisionT | undefined;
      await expect
        .poll(
          () =>
            (found = [...state.decisions.values()].find(
              (d) => d.status === 'open' && d.kind === kind && (!question || d.question.includes(question)),
            )),
          { timeout: 15_000, interval: 20 },
        )
        .toBeTruthy();
      return found!;
    };
    const answer = (d: DecisionT, option: string) =>
      foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: d.id, option });

    foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'benvenuto in DemoPlugin' });
    answer(await open('merge'), '1');
    const deploy = await open('deploy');
    expect(deploy.question).toBe('Installare DemoPlugin 1.2.0 sul server di prova?');
    expect(deploy.options).toEqual(['Installa e riavvia', 'Installa, riavvio io', 'No']);
    answer(deploy, '1');
    await expect.poll(() => server.calls.map((c) => c.call.tool), { timeout: 5000 }).toContain('server.restart');
    const dep = server.calls.find((c) => c.call.tool === 'server.deploy')!.call as Extract<
      ToolInvocation,
      { tool: 'server.deploy' }
    >;
    expect(dep.args).toMatchObject({ approvalId: deploy.id });
    expect(dep.args.file).toContain(join(home, 'deploy'));

    // the server comes back: Briciola tests the plugin in game
    team.onSessionsChanged([{ server: { id: 'staging', role: 'staging' } }]);
    const fix = await open('question', 'bug trovati');
    expect(qaTools).toEqual(expect.arrayContaining(['server_metrics', 'server_logs', 'player_simulate', 'qa_report']));
    expect(qaTools).not.toContain('update_task');
    const qaTask = [...state.tasks.values()].find((t) => t.title.startsWith('Collaudo in gioco'))!;
    expect(qaTask).toMatchObject({ status: 'done', assignee: 'briciola' });
    answer(fix, '1');
    await expect
      .poll(() => [...state.tasks.values()].find((t) => t.title.startsWith('Correggere i bug'))?.assignee, {
        timeout: 5000,
      })
      .toBe('lucciola');
  });
});
