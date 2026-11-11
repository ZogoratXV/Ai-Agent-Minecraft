import { createHash, timingSafeEqual } from 'node:crypto';
import type { IncomingMessage } from 'node:http';
import type { ForemanToPlugin } from '@agentcraft-server/protocol';
import {
  PluginToForeman,
  PROTOCOL_VERSION,
  STAGING_ONLY_TOOLS,
  type ToolInvocation,
} from '@agentcraft-server/protocol';
import { WebSocket, WebSocketServer } from 'ws';
import type { z } from 'zod';

type Hello = Extract<PluginToForeman, { type: 'hello' }>;
type ToolResultMsg = Extract<PluginToForeman, { type: 'tool.result' }>;
type Intent = Exclude<PluginToForeman, { type: 'hello' | 'tool.result' }>;
type ServerInfo = Hello['server'];

export interface ToolOutcome {
  ok: boolean;
  result?: unknown;
  error?: { code: string; message: string };
}

export interface Session {
  readonly id: number;
  readonly server: ServerInfo;
  readonly capabilities: Hello['capabilities'];
  readonly limits: Hello['limits'];
}

export interface BridgeOptions {
  host: string;
  port: number;
  token: string;
  /** builds the snapshot sent after a valid hello */
  snapshot: () => z.infer<typeof ForemanToPlugin>;
  /** user intents (goal.submit, decision.answer, ...). The returned value becomes ack.result. */
  onIntent: (intent: Intent, session: Session) => Promise<unknown> | unknown;
  onSessionChange?: (sessions: Session[]) => void;
  log?: (line: string) => void;
  helloTimeoutMs?: number;
}

const MAX_PAYLOAD = 1 << 20;

/** Constant-time token comparison (hashes first so lengths never leak). */
export function tokenMatches(expected: string, given: string | undefined): boolean {
  if (given === undefined) return false;
  const a = createHash('sha256').update(expected).digest();
  const b = createHash('sha256').update(given).digest();
  return timingSafeEqual(a, b);
}

function bearer(req: IncomingMessage): string | undefined {
  const h = req.headers.authorization;
  return h?.startsWith('Bearer ') ? h.slice(7) : undefined;
}

interface Conn {
  ws: WebSocket;
  session?: Session;
}

/**
 * WebSocket server the plugins connect to. Loopback/private bind only (checked by config),
 * no browsers (any Origin header is refused), shared token on the upgrade and in hello.
 */
export class Bridge {
  private wss?: WebSocketServer;
  private readonly conns = new Set<Conn>();
  private readonly pending = new Map<string, { resolve: (r: ToolOutcome) => void; timer: NodeJS.Timeout }>();
  private nextSession = 1;
  private nextCall = 1;
  private readonly log: (line: string) => void;

  constructor(private readonly opts: BridgeOptions) {
    this.log = opts.log ?? (() => {});
  }

  get port(): number {
    const addr = this.wss?.address();
    return typeof addr === 'object' && addr ? addr.port : this.opts.port;
  }

  sessions(): Session[] {
    return [...this.conns].flatMap((c) => (c.session ? [c.session] : []));
  }

  start(): Promise<void> {
    return new Promise((resolve, reject) => {
      const wss = new WebSocketServer({
        host: this.opts.host,
        port: this.opts.port,
        maxPayload: MAX_PAYLOAD,
        verifyClient: (info, done) => {
          if (info.req.headers.origin !== undefined) return done(false, 403, 'browsers are not allowed');
          if (!tokenMatches(this.opts.token, bearer(info.req))) return done(false, 401, 'invalid token');
          done(true);
        },
      });
      wss.once('listening', () => resolve());
      wss.once('error', reject);
      wss.on('connection', (ws) => this.accept(ws));
      this.wss = wss;
    });
  }

  async stop(): Promise<void> {
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer);
      p.resolve({ ok: false, error: { code: 'internal', message: 'foreman stopping' } });
      this.pending.delete(id);
    }
    for (const c of this.conns) c.ws.close(1001, 'foreman stopping');
    await new Promise<void>((resolve) => (this.wss ? this.wss.close(() => resolve()) : resolve()));
  }

  /** Sends a state message to every authenticated plugin. */
  broadcast(msg: z.infer<typeof ForemanToPlugin>): void {
    const frame = JSON.stringify(msg);
    for (const c of this.conns) if (c.session && c.ws.readyState === WebSocket.OPEN) c.ws.send(frame);
  }

  /**
   * Asks a plugin to run a world tool. Staging-only tools go to a staging server only; otherwise
   * the first connected server (production preferred) is used. Never throws.
   */
  callTool(
    agentId: string,
    call: ToolInvocation,
    opts: { requestedBy: string; taskId?: string; deadlineMs?: number; serverId?: string } = { requestedBy: 'foreman' },
  ): Promise<ToolOutcome> {
    const stagingOnly = STAGING_ONLY_TOOLS.includes(call.tool);
    const candidates = [...this.conns].filter(
      (c) =>
        c.session &&
        c.ws.readyState === WebSocket.OPEN &&
        (opts.serverId ? c.session.server.id === opts.serverId : true) &&
        (stagingOnly ? c.session.server.role === 'staging' : true),
    );
    candidates.sort(
      (a, b) => Number(b.session?.server.role === 'production') - Number(a.session?.server.role === 'production'),
    );
    const target = candidates[0];
    if (!target) {
      return Promise.resolve({
        ok: false,
        error: { code: 'not_found', message: stagingOnly ? 'no staging server connected' : 'no server connected' },
      });
    }
    const id = `f${this.nextCall++}`;
    const deadlineMs = opts.deadlineMs ?? 30_000;
    const msg = {
      v: PROTOCOL_VERSION,
      type: 'tool.call' as const,
      id,
      agentId,
      ...(opts.taskId ? { taskId: opts.taskId } : {}),
      requestedBy: opts.requestedBy,
      deadlineMs,
      call,
    };
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ ok: false, error: { code: 'timeout', message: `no answer within ${deadlineMs} ms` } });
      }, deadlineMs + 2_000);
      this.pending.set(id, { resolve, timer });
      target.ws.send(JSON.stringify(msg));
    });
  }

  private accept(ws: WebSocket): void {
    const conn: Conn = { ws };
    this.conns.add(conn);
    const helloTimer = setTimeout(
      () => this.reject(conn, 'invalid_message', 'expected hello'),
      this.opts.helloTimeoutMs ?? 10_000,
    );
    ws.on('message', (data, isBinary) => {
      if (isBinary) return this.reject(conn, 'invalid_message', 'binary frames are not supported');
      void this.onFrame(conn, data.toString(), helloTimer);
    });
    ws.on('close', () => {
      clearTimeout(helloTimer);
      this.conns.delete(conn);
      if (conn.session) {
        this.log(`plugin '${conn.session.server.id}' disconnected`);
        this.opts.onSessionChange?.(this.sessions());
      }
    });
    ws.on('error', (e) => this.log(`bridge connection error: ${e.message}`));
  }

  private reject(conn: Conn, code: 'invalid_message' | 'unauthorized' | 'version_mismatch', message: string): void {
    this.send(conn, { v: 1, type: 'error', code, message });
    conn.ws.close(code === 'unauthorized' ? 4401 : 4400, message.slice(0, 100));
  }

  private send(conn: Conn, msg: z.infer<typeof ForemanToPlugin>): void {
    if (conn.ws.readyState === WebSocket.OPEN) conn.ws.send(JSON.stringify(msg));
  }

  private async onFrame(conn: Conn, frame: string, helloTimer: NodeJS.Timeout): Promise<void> {
    let raw: unknown;
    try {
      raw = JSON.parse(frame);
    } catch {
      return this.reject(conn, 'invalid_message', 'not valid JSON');
    }
    const v = (raw as { v?: unknown } | null)?.v;
    if (v !== PROTOCOL_VERSION) return this.reject(conn, 'version_mismatch', `protocol v${PROTOCOL_VERSION} required`);
    const parsed = PluginToForeman.safeParse(raw);
    if (!parsed.success) {
      const id = (raw as { id?: unknown }).id;
      if (typeof id === 'string')
        this.send(conn, { v: 1, type: 'ack', re: id.slice(0, 64), ok: false, error: 'invalid message' });
      else
        this.send(conn, {
          v: 1,
          type: 'error',
          code: 'invalid_message',
          message: parsed.error.issues[0]?.message ?? 'invalid',
        });
      if (!conn.session) conn.ws.close(4400, 'invalid hello');
      return;
    }
    const msg = parsed.data;

    if (!conn.session) {
      if (msg.type !== 'hello') return this.reject(conn, 'invalid_message', 'expected hello');
      if (!tokenMatches(this.opts.token, msg.token)) return this.reject(conn, 'unauthorized', 'invalid token');
      clearTimeout(helloTimer);
      conn.session = {
        id: this.nextSession++,
        server: msg.server,
        capabilities: msg.capabilities,
        limits: msg.limits,
      };
      this.log(`plugin '${msg.server.id}' (${msg.server.role}, ${msg.server.software}) connected`);
      this.send(conn, this.opts.snapshot());
      this.opts.onSessionChange?.(this.sessions());
      return;
    }

    if (msg.type === 'hello') return; // repeated hello on an open session: ignore
    if (msg.type === 'tool.result') return this.settle(msg);

    try {
      const result = await this.opts.onIntent(msg, conn.session);
      if (msg.id)
        this.send(conn, { v: 1, type: 'ack', re: msg.id, ok: true, ...(result === undefined ? {} : { result }) });
    } catch (e) {
      const error = e instanceof Error ? e.message : String(e);
      if (msg.id) this.send(conn, { v: 1, type: 'ack', re: msg.id, ok: false, error: error.slice(0, 1000) });
    }
  }

  private settle(msg: ToolResultMsg): void {
    const p = this.pending.get(msg.re);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(msg.re);
    p.resolve({
      ok: msg.ok,
      ...(msg.result === undefined ? {} : { result: msg.result }),
      ...(msg.error ? { error: msg.error } : {}),
    });
  }
}
