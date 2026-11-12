import type { ToolInvocation } from '@agentcraft-server/protocol';
import { describe, expect, it } from 'vitest';
import type { ToolPort } from '../src/backend.js';
import type { ToolOutcome } from '../src/bridge.js';
import { realClock } from '../src/clock.js';
import { ConfigSchema } from '../src/config.js';
import { buildTeam, Foreman } from '../src/foreman.js';
import { SimBackend } from '../src/sim/scenario.js';
import { actor, newState } from './helpers.js';

/** A plugin stand-in: one region, previews accepted, every place job finishes right away. */
class FakeWorld implements ToolPort {
  readonly calls: Array<{ agentId: string; call: ToolInvocation }> = [];
  foreman?: Foreman;
  regions: Array<{ name: string; size: string }> = [{ name: 'piazza', size: '20x15x20' }];
  async callTool(agentId: string, call: ToolInvocation): Promise<ToolOutcome> {
    this.calls.push({ agentId, call });
    switch (call.tool) {
      case 'world.regions':
        return { ok: true, result: { regions: this.regions } };
      case 'world.preview':
        return {
          ok: true,
          result: { blocks: 400, size: '9x9x9', materials: { 'minecraft:oak_planks': 120 }, shownTo: 1 },
        };
      case 'world.place': {
        const jobId = call.args.jobId;
        setTimeout(
          () => this.foreman?.handle({ v: 1, type: 'job.progress', jobId, state: 'done', done: 10, total: 10 }),
          5,
        );
        return { ok: true, result: { jobId, total: 10 } };
      }
      default:
        return { ok: true };
    }
  }
}

function setup() {
  const state = newState();
  for (const a of buildTeam(ConfigSchema.parse({}))) state.agents.set(a.id, a);
  state.agents.set('cazzuola', { ...state.agents.get('cazzuola')!, active: true });
  const world = new FakeWorld();
  const sim = new SimBackend(state, world, realClock, { stepMs: 1 });
  const foreman = new Foreman(state, realClock, sim);
  world.foreman = foreman;
  sim.start();
  return { state, world, sim, foreman };
}

const open = async (state: ReturnType<typeof newState>, kind: string) => {
  let d;
  await expect
    .poll(() => (d = [...state.decisions.values()].find((x) => x.kind === kind && x.status === 'open')), {
      timeout: 5000,
      interval: 5,
    })
    .toBeTruthy();
  return d!;
};

describe('building (sim)', () => {
  it('designs, previews, waits for approval, then builders place every module in order', async () => {
    const { state, world, sim, foreman } = setup();
    const { goalId } = foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'costruisci una casa medievale' }) as {
      goalId: string;
    };

    const first = await open(state, 'build');
    expect(first.options).toEqual(['Costruisci', 'Modifica', 'Rifiuta']);
    expect(first.question).toContain('Casa medievale');
    expect(world.calls.some((c) => c.call.tool === 'world.place')).toBe(false); // nothing placed before approval

    foreman.handle({
      v: 1,
      type: 'decision.answer',
      actor,
      decisionId: first.id,
      option: '2',
      text: 'girala verso la strada',
    });
    const second = await open(state, 'build');
    const previews = world.calls.filter((c) => c.call.tool === 'world.preview' && c.call.args.ttlSeconds > 0);
    expect(previews.map((p) => (p.call.args as { rotation: number }).rotation)).toEqual([0, 90]);

    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: second.id, option: 'Costruisci' });
    await expect.poll(() => state.goals.get(goalId)?.status, { timeout: 5000, interval: 5 }).toBe('done');
    const places = world.calls.filter((c) => c.call.tool === 'world.place');
    expect(places.map((p) => (p.call.args as { module: string }).module)).toEqual([
      'fondamenta',
      'pavimento',
      'muri',
      'tetto',
      'interni',
    ]);
    expect(new Set(places.map((p) => p.agentId))).toEqual(new Set(['mattone', 'cazzuola'])); // shared between builders
    expect(places.every((p) => (p.call.args as { approvalId: string }).approvalId === second.id)).toBe(true);
    expect(state.tasks.get('t1')?.summary).toContain('/agent undo t1');
    // the preview is removed once building starts
    expect(world.calls.some((c) => c.call.tool === 'world.preview' && c.call.args.ttlSeconds === 0)).toBe(true);
    sim.stop();
  });

  it('refusing the project builds nothing', async () => {
    const { state, world, sim, foreman } = setup();
    const { goalId } = foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'costruisci una torre' }) as {
      goalId: string;
    };
    const d = await open(state, 'build');
    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: d.id, option: 'Rifiuta' });
    await expect.poll(() => state.goals.get(goalId)?.status, { timeout: 5000, interval: 5 }).toBe('cancelled');
    expect(world.calls.some((c) => c.call.tool === 'world.place')).toBe(false);
    sim.stop();
  });

  it('asks for a region when there is none big enough', async () => {
    const { state, world, sim, foreman } = setup();
    world.regions = [{ name: 'aiuola', size: '3x3x3' }];
    const { goalId } = foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'costruisci una casa' }) as {
      goalId: string;
    };
    await expect.poll(() => state.goals.get(goalId)?.status, { timeout: 5000, interval: 5 }).toBe('failed');
    expect(state.tasks.get('t1')?.blockedReason).toContain('regione');
    sim.stop();
  });
});

import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Runner, TurnResult, TurnSpec } from '../src/agents/runner.js';
import { TeamBackend } from '../src/agents/team.js';
import { DEFAULT_CAST } from '../src/cast.js';
import { Memory } from '../src/memory.js';
import { RepoManager } from '../src/repos.js';
import { Store } from '../src/store.js';

describe('building (real team, scripted agents)', () => {
  it('the lead hands the build to the architect, who designs and submits; builders place after approval', async () => {
    const house = JSON.parse(
      readFileSync(join(import.meta.dirname, '../../blueprints/examples/casa-medievale.json'), 'utf8'),
    );
    const turns: string[] = [];
    let firstError = '';
    const runner: Runner = {
      async run(spec: TurnSpec): Promise<TurnResult> {
        turns.push(spec.agentId);
        const call = (name: string, args: Record<string, unknown>) =>
          spec.tools.find((t) => t.name === name)!.handler(args);
        if (spec.agentId === 'bussola') {
          expect(spec.prompt).toContain('kind "build"');
          await call('create_task', {
            title: 'Casa medievale in piazza',
            kind: 'build',
            assignee: 'matita',
            deps: [],
            paths: [],
          });
        }
        if (spec.agentId === 'matita') {
          expect(spec.tools.map((t) => t.name)).toEqual(
            expect.arrayContaining(['world_regions', 'world_inspect', 'submit_blueprint']),
          );
          expect(spec.tools.map((t) => t.name)).not.toContain('update_task');
          expect(await call('world_regions', {})).toContain('piazza');
          firstError = await call('submit_blueprint', { blueprint: { ...house, palette: {} }, region: 'piazza' });
          expect(await call('submit_blueprint', { blueprint: house, region: 'piazza' })).toContain('Submitted');
        }
        return { ok: true, costUsd: 0.01, text: 'ok', sessionId: `s-${spec.agentId}` };
      },
    };
    const home = mkdtempSync(join(tmpdir(), 'ac-build-'));
    const state = newState();
    const cfg = ConfigSchema.parse({ backend: 'claude' });
    for (const a of buildTeam(cfg)) state.agents.set(a.id, a);
    const world = new FakeWorld();
    const team = new TeamBackend({
      cfg,
      state,
      repos: new RepoManager(join(home, 'wt')),
      memory: new Memory(join(home, 'mem')),
      runner,
      store: new Store(home),
      clock: realClock,
      cast: DEFAULT_CAST,
      home,
      baseEnv: { ...process.env, ANTHROPIC_API_KEY: 'k' },
      tools: world,
    });
    const foreman = new Foreman(state, realClock, team);
    world.foreman = foreman;
    team.start();
    const { goalId } = foreman.handle({
      v: 1,
      type: 'goal.submit',
      actor,
      text: 'costruite una casa medievale in piazza',
    }) as { goalId: string };
    const d = await open(state, 'build');
    expect(firstError).toContain('not in the palette'); // a broken blueprint went back to the architect
    expect(state.tasks.get('t1')).toMatchObject({ kind: 'build', status: 'review', assignee: 'matita' });
    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: d.id, option: '1' });
    await expect.poll(() => state.goals.get(goalId)?.status, { timeout: 5000, interval: 5 }).toBe('done');
    expect(
      world.calls
        .filter((c) => c.call.tool === 'world.place')
        .map((c) => c.agentId)
        .every((a) => a === 'mattone'),
    ).toBe(true);
    expect(turns).toEqual(['bussola', 'matita']); // no AI turns for placing blocks
    team.stop();
  });

  it('a generic task given to the architect becomes a build, and a JSON-string blueprint is accepted', async () => {
    const house = JSON.parse(
      readFileSync(join(import.meta.dirname, '../../blueprints/examples/casa-medievale.json'), 'utf8'),
    );
    const turns: string[] = [];
    let firstError = '';
    const runner: Runner = {
      async run(spec: TurnSpec): Promise<TurnResult> {
        turns.push(spec.agentId);
        const call = (name: string, args: Record<string, unknown>) =>
          spec.tools.find((t) => t.name === name)!.handler(args);
        if (spec.agentId === 'bussola') {
          expect(spec.prompt).toContain('kind "build"');
          await call('create_task', {
            title: 'Casa medievale in piazza',
            kind: 'generic',
            assignee: 'matita',
            deps: [],
            paths: [],
          });
        }
        if (spec.agentId === 'matita') {
          expect(spec.tools.map((t) => t.name)).toEqual(
            expect.arrayContaining(['world_regions', 'world_inspect', 'submit_blueprint']),
          );
          expect(spec.tools.map((t) => t.name)).not.toContain('update_task');
          expect(await call('world_regions', {})).toContain('piazza');
          firstError = await call('submit_blueprint', { blueprint: { ...house, palette: {} }, region: 'piazza' });
          expect(await call('submit_blueprint', { blueprint: JSON.stringify(house), region: 'piazza' })).toContain(
            'Submitted',
          );
        }
        return { ok: true, costUsd: 0.01, text: 'ok', sessionId: `s-${spec.agentId}` };
      },
    };
    const home = mkdtempSync(join(tmpdir(), 'ac-build-'));
    const state = newState();
    const cfg = ConfigSchema.parse({ backend: 'claude' });
    for (const a of buildTeam(cfg)) state.agents.set(a.id, a);
    const world = new FakeWorld();
    const team = new TeamBackend({
      cfg,
      state,
      repos: new RepoManager(join(home, 'wt')),
      memory: new Memory(join(home, 'mem')),
      runner,
      store: new Store(home),
      clock: realClock,
      cast: DEFAULT_CAST,
      home,
      baseEnv: { ...process.env, ANTHROPIC_API_KEY: 'k' },
      tools: world,
    });
    const foreman = new Foreman(state, realClock, team);
    world.foreman = foreman;
    team.start();
    const { goalId } = foreman.handle({
      v: 1,
      type: 'goal.submit',
      actor,
      text: 'costruite una casa medievale in piazza',
    }) as { goalId: string };
    const d = await open(state, 'build');
    expect(firstError).toContain('not in the palette'); // a broken blueprint went back to the architect
    expect(state.tasks.get('t1')).toMatchObject({ kind: 'build', status: 'review', assignee: 'matita' });
    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: d.id, option: '1' });
    await expect.poll(() => state.goals.get(goalId)?.status, { timeout: 5000, interval: 5 }).toBe('done');
    expect(
      world.calls
        .filter((c) => c.call.tool === 'world.place')
        .map((c) => c.agentId)
        .every((a) => a === 'mattone'),
    ).toBe(true);
    expect(turns).toEqual(['bussola', 'matita']); // no AI turns for placing blocks
    team.stop();
  });
});
