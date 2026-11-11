import { describe, expect, it } from 'vitest';
import { ConfigSchema } from '../src/config.js';
import { buildTeam, Foreman, resolveOption } from '../src/foreman.js';
import { realClock } from '../src/clock.js';
import { SimBackend } from '../src/sim/scenario.js';
import { actor, FakeTools, newState } from './helpers.js';

function setup() {
  const state = newState();
  for (const a of buildTeam(ConfigSchema.parse({}))) state.agents.set(a.id, a);
  const tools = new FakeTools();
  const sim = new SimBackend(state, tools, realClock, { stepMs: 1 });
  const foreman = new Foreman(state, realClock, sim);
  sim.start();
  const openDecision = async (kind: string) => {
    let found;
    await expect
      .poll(() => (found = [...state.decisions.values()].find((d) => d.status === 'open' && d.kind === kind)), {
        timeout: 3000,
        interval: 5,
      })
      .toBeTruthy();
    return found!;
  };
  return { state, tools, sim, foreman, openDecision };
}

describe('sim backend', () => {
  it('plays a whole goal: plan, question, merge, done', async () => {
    const { state, tools, foreman, openDecision, sim } = setup();
    const { goalId } = foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'Correggi /shop' }) as {
      goalId: string;
    };

    const q = await openDecision('question');
    expect(state.agents.get('briciola')).toMatchObject({ state: 'waiting_user', station: 'podium' });
    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: q.id, option: '1' });

    const m = await openDecision('merge');
    expect(m.options).toEqual(['Merge', 'Request changes', 'Reject']);
    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: m.id, option: 'merge' });

    await expect.poll(() => state.goals.get(goalId)?.status, { timeout: 3000, interval: 5 }).toBe('done');
    expect([...state.tasks.values()].map((t) => t.status)).toEqual(['done', 'done']);
    expect(state.tasks.get('t2')?.deps).toEqual(['t1']);
    // NPCs were moved and spoke through the world tools
    expect(tools.calls.some((c) => c.call.tool === 'body.move')).toBe(true);
    expect(tools.calls.some((c) => c.call.tool === 'body.say')).toBe(true);
    sim.stop();
  });

  it('loops on Request changes and cancels on Reject', async () => {
    const { state, foreman, openDecision, sim } = setup();
    const { goalId } = foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'x' }) as { goalId: string };
    foreman.handle({
      v: 1,
      type: 'decision.answer',
      actor,
      decisionId: (await openDecision('question')).id,
      option: '2',
    });
    const first = await openDecision('merge');
    foreman.handle({
      v: 1,
      type: 'decision.answer',
      actor,
      decisionId: first.id,
      option: 'Request changes',
      text: 'più test',
    });
    const second = await openDecision('merge');
    expect(second.id).not.toBe(first.id);
    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: second.id, option: '3' });
    await expect.poll(() => state.tasks.get('t2')?.status, { timeout: 3000, interval: 5 }).toBe('cancelled');
    await expect.poll(() => state.goals.get(goalId)?.status, { timeout: 3000, interval: 5 }).toBe('done');
    sim.stop();
  });

  it('waits while an agent is paused', async () => {
    const { state, foreman, openDecision, sim } = setup();
    foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'x' });
    foreman.handle({ v: 1, type: 'agent.action', actor, agentId: 'all', action: 'pause' });
    expect(state.status.paused).toBe(true);
    await new Promise((r) => setTimeout(r, 100));
    expect([...state.decisions.values()]).toHaveLength(0);
    foreman.handle({ v: 1, type: 'agent.action', actor, agentId: 'all', action: 'resume' });
    await openDecision('question');
    sim.stop();
  });
});

describe('foreman intents', () => {
  it('refuses answers to unknown or closed decisions and bad options', async () => {
    const { foreman, openDecision, sim } = setup();
    foreman.handle({ v: 1, type: 'goal.submit', actor, text: 'x' });
    const q = await openDecision('question');
    expect(() => foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: 'd99', option: '1' })).toThrow(
      /unknown/,
    );
    expect(() => foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: q.id, option: '7' })).toThrow(
      /unknown option/,
    );
    foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: q.id, option: '1' });
    expect(() => foreman.handle({ v: 1, type: 'decision.answer', actor, decisionId: q.id, option: '1' })).toThrow(
      /already/,
    );
    sim.stop();
  });

  it('resolves options by number or label, case-insensitive', () => {
    const d = { options: ['Merge', 'Reject'] } as Parameters<typeof resolveOption>[0];
    expect(resolveOption(d, '2')).toBe('Reject');
    expect(resolveOption(d, 'MERGE')).toBe('Merge');
    expect(() => resolveOption(d, 'boh')).toThrow();
  });

  it('stops and spawns agents', () => {
    const { state, foreman } = setup();
    foreman.handle({ v: 1, type: 'agent.action', actor, agentId: 'mattone', action: 'stop' });
    expect(state.agents.get('mattone')).toMatchObject({ active: false, station: 'lounge' });
    foreman.handle({ v: 1, type: 'agent.action', actor, agentId: 'cazzuola', action: 'spawn' });
    expect(state.agents.get('cazzuola')?.active).toBe(true);
    expect(() => foreman.handle({ v: 1, type: 'agent.action', actor, agentId: 'nessuno', action: 'stop' })).toThrow();
  });

  it('starts with the default team: off-shift agents in the lounge', () => {
    const team = buildTeam(ConfigSchema.parse({}));
    expect(team.find((a) => a.id === 'civetta')).toMatchObject({ active: false, station: 'lounge' });
    expect(team.find((a) => a.id === 'bussola')).toMatchObject({ active: true, role: 'lead', model: 'sim' });
  });
});
