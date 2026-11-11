import { EXAMPLES, ForemanToPlugin, type PluginToForeman } from '@agentcraft-server/protocol';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WebSocket } from 'ws';
import { Bridge, tokenMatches } from '../src/bridge.js';
import { actor, newState } from './helpers.js';

const TOKEN = 'test-token-0123456789abcdef';
const hello = { ...(EXAMPLES.hello as Extract<PluginToForeman, { type: 'hello' }>), token: TOKEN };

let bridge: Bridge;
const intents: string[] = [];

beforeEach(async () => {
  intents.length = 0;
  const state = newState();
  bridge = new Bridge({
    host: '127.0.0.1',
    port: 0,
    token: TOKEN,
    snapshot: () => state.snapshot(),
    onIntent: (i) => {
      intents.push(i.type);
      if (i.type === 'repo.add') throw new Error('nope');
      return { echoed: i.type };
    },
    helloTimeoutMs: 300,
  });
  await bridge.start();
});

afterEach(async () => {
  await bridge.stop();
});

function connect(headers: Record<string, string> = { authorization: `Bearer ${TOKEN}` }) {
  const ws = new WebSocket(`ws://127.0.0.1:${bridge.port}`, { headers });
  const inbox: Array<Record<string, unknown>> = [];
  const waiters: Array<() => void> = [];
  ws.on('message', (d) => {
    inbox.push(JSON.parse(d.toString()));
    waiters.splice(0).forEach((w) => w());
  });
  const next = async (type: string): Promise<Record<string, unknown>> => {
    for (;;) {
      const i = inbox.findIndex((m) => m.type === type);
      if (i >= 0) return inbox.splice(i, 1)[0]!;
      await new Promise<void>((r) => waiters.push(r));
    }
  };
  const opened = new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('unexpected-response', (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
    ws.once('error', reject);
  });
  const closed = new Promise<number>((r) => ws.once('close', (code) => r(code)));
  return { ws, next, opened, closed, send: (m: unknown) => ws.send(JSON.stringify(m)) };
}

describe('bridge', () => {
  it('compares tokens in constant time without leaking length', () => {
    expect(tokenMatches(TOKEN, TOKEN)).toBe(true);
    expect(tokenMatches(TOKEN, 'x')).toBe(false);
    expect(tokenMatches(TOKEN, undefined)).toBe(false);
  });

  it('rejects a wrong or missing token on the upgrade', async () => {
    await expect(connect({ authorization: 'Bearer wrong-token-wrong-token' }).opened).rejects.toThrow('HTTP 401');
    await expect(connect({}).opened).rejects.toThrow('HTTP 401');
  });

  it('rejects browsers (any Origin header)', async () => {
    await expect(connect({ authorization: `Bearer ${TOKEN}`, origin: 'http://localhost:3000' }).opened).rejects.toThrow(
      'HTTP 403',
    );
  });

  it('answers hello with a valid snapshot', async () => {
    const c = connect();
    await c.opened;
    c.send(hello);
    const snap = await c.next('snapshot');
    expect(ForemanToPlugin.safeParse(snap).success).toBe(true);
    expect(bridge.sessions()[0]?.server.role).toBe('staging');
    c.ws.close();
  });

  it('closes when the first message is not hello, or hello has a wrong token', async () => {
    const a = connect();
    await a.opened;
    a.send(EXAMPLES['goal.submit']);
    expect((await a.next('error')).code).toBe('invalid_message');
    await a.closed;

    const b = connect();
    await b.opened;
    b.send({ ...hello, token: 'another-token-0123456789' });
    expect((await b.next('error')).code).toBe('unauthorized');
    expect(await b.closed).toBe(4401);
  });

  it('closes silent connections after the hello timeout', async () => {
    const c = connect();
    await c.opened;
    expect(await c.closed).toBe(4400);
  });

  it('refuses a different protocol version', async () => {
    const c = connect();
    await c.opened;
    c.send({ ...hello, v: 2 });
    expect((await c.next('error')).code).toBe('version_mismatch');
  });

  it('acks intents with their result or error', async () => {
    const c = connect();
    await c.opened;
    c.send(hello);
    await c.next('snapshot');
    c.send({ v: 1, type: 'goal.submit', id: 'c1', actor, text: 'costruisci una torre' });
    expect(await c.next('ack')).toMatchObject({ re: 'c1', ok: true, result: { echoed: 'goal.submit' } });
    c.send({ v: 1, type: 'repo.add', id: 'c2', actor, path: '/x' });
    expect(await c.next('ack')).toMatchObject({ re: 'c2', ok: false, error: 'nope' });
    c.send({ v: 1, type: 'goal.submit', id: 'c3', actor, text: '' });
    expect(await c.next('ack')).toMatchObject({ re: 'c3', ok: false });
    expect(intents).toEqual(['goal.submit', 'repo.add']);
  });

  it('routes tool calls and settles them with tool.result', async () => {
    const c = connect();
    await c.opened;
    c.send(hello);
    await c.next('snapshot');
    const pending = bridge.callTool('mattone', { tool: 'body.say', args: { text: 'ciao' } }, { requestedBy: 'test' });
    const call = await c.next('tool.call');
    expect(call).toMatchObject({ agentId: 'mattone', call: { tool: 'body.say' } });
    c.send({ v: 1, type: 'tool.result', re: call.id, ok: true, result: { said: true } });
    expect(await pending).toEqual({ ok: true, result: { said: true } });
  });

  it('times out tool calls nobody answers, and fails fast without a server', async () => {
    expect((await bridge.callTool('x', { tool: 'server.metrics', args: {} })).error?.code).toBe('not_found');
    const c = connect();
    await c.opened;
    c.send(hello);
    await c.next('snapshot');
    const r = await bridge.callTool('x', { tool: 'server.metrics', args: {} }, { requestedBy: 't', deadlineMs: 100 });
    expect(r.error?.code).toBe('timeout');
  }, 10_000);

  it('sends staging-only tools only to staging servers', async () => {
    const c = connect();
    await c.opened;
    c.send({ ...hello, server: { ...hello.server, role: 'production' } });
    await c.next('snapshot');
    const r = await bridge.callTool('briciola', {
      tool: 'player.simulate',
      args: { actions: [{ kind: 'wait', ticks: 1 }] },
    });
    expect(r.error?.code).toBe('not_found');
  });
});
