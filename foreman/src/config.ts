import { readFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { z } from 'zod';
import { DEFAULT_CAST } from './cast.js';

/**
 * Configuration: defaults < config file < environment < command line.
 * Secrets (the bridge token, API keys) never live in the config file: they come from the
 * environment (AGENTCRAFT_TOKEN, ANTHROPIC_API_KEY) or from a file outside git (tokenFile).
 */

/** Loopback and private ranges only: the bridge must never listen on a public address. */
export function isPrivateHost(host: string): boolean {
  if (host === 'localhost') return true;
  const kind = isIP(host);
  if (kind === 4) {
    const [a = -1, b = -1] = host.split('.').map(Number);
    return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  }
  if (kind === 6) {
    const h = host.toLowerCase();
    return h === '::1' || h.startsWith('fc') || h.startsWith('fd');
  }
  return false;
}

const Effort = z.enum(['low', 'medium', 'high', 'xhigh', 'max']);

const RepoEntry = z.object({
  path: z.string().min(1),
  id: z
    .string()
    .regex(/^[a-z0-9._-]{1,64}$/)
    .optional(),
  branch: z.string().max(200).optional(),
  versioning: z.enum(['in-place', 'new-folder']).optional(),
  sparse: z.boolean().optional(),
  sparseAlways: z.array(z.string().max(300)).optional(),
  ci: z.string().max(500).optional(),
  forbidden: z.array(z.string().min(2).max(100)).optional(),
  instructions: z.array(z.string().max(200)).optional(),
  push: z
    .object({
      remote: z.string().max(64).optional(),
      branch: z.string().max(200).optional(),
      sshKeyFile: z.string().max(500).optional(),
      tokenFile: z.string().max(500).optional(),
    })
    .optional(),
});

const CastOverride = z.object({
  id: z.string().regex(/^[a-z0-9_-]{1,32}$/),
  name: z.string().min(1).max(32).optional(),
  title: z.string().max(48).optional(),
  color: z
    .string()
    .regex(/^#[0-9a-fA-F]{6}$/)
    .optional(),
  personality: z.string().max(500).optional(),
  onShift: z.boolean().optional(),
  skin: z.object({ name: z.string().min(1).max(16) }).optional(),
});

export const ConfigSchema = z.object({
  host: z.string().default('127.0.0.1').refine(isPrivateHost, 'host must be loopback or a private network address'),
  port: z.number().int().min(1024).max(65535).default(7878),
  /** state root: tasks, decisions, memory, worktrees, logs */
  /** after a merge of a plugin: build its jar and offer to install it on the staging server */
  deployAfterMerge: z.boolean().default(true),
  home: z.string().default('.agentcraft'),
  backend: z.enum(['sim', 'claude']).default('sim'),
  tokenFile: z.string().optional(),
  models: z
    .object({
      lead: z.string().default('claude-opus-5-5'),
      worker: z.string().default('claude-sonnet-5-5'),
      light: z.string().default('claude-haiku-5-5'),
    })
    .default({ lead: 'claude-opus-5-5', worker: 'claude-sonnet-5-5', light: 'claude-haiku-5-5' }),
  effort: z
    .object({ lead: Effort.default('high'), worker: Effort.default('medium'), light: Effort.default('low') })
    .default({ lead: 'high', worker: 'medium', light: 'low' }),
  maxConcurrent: z.number().int().min(1).max(10).default(3),
  maxTurns: z
    .object({ lead: z.number().int().min(1).default(40), worker: z.number().int().min(1).default(80) })
    .default({ lead: 40, worker: 80 }),
  budget: z
    .object({
      dailyUsd: z.number().positive().default(15),
      monthlyUsd: z.number().positive().default(300),
      perTaskUsd: z.number().positive().default(5),
    })
    .default({ dailyUsd: 15, monthlyUsd: 300, perTaskUsd: 5 }),
  cast: z.array(CastOverride).default([]),
  /** plugin repositories the agents work on (more can be added in game with /agent repo add) */
  repos: z.array(RepoEntry).default([]),
  /**
   * Personal use only: the agents use a Claude subscription through CLAUDE_CODE_OAUTH_TOKEN
   * (made with `claude setup-token`) instead of an API key.
   */
  useClaudeLogin: z.boolean().default(false),
  /** server name the agents are told about */
  serverName: z.string().max(64).default('Minecraft'),
  sim: z.object({ stepMs: z.number().int().min(0).max(60_000).default(2500) }).default({ stepMs: 2500 }),
});
export type Config = z.infer<typeof ConfigSchema>;

/** Drops documentation keys ("//", "// models", ...) so config.example.json can carry comments. */
export function stripComments(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripComments);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([k]) => !k.startsWith('//'))
        .map(([k, v]) => [k, stripComments(v)]),
    );
  }
  return value;
}

export interface ConfigSources {
  file?: unknown;
  env?: NodeJS.ProcessEnv;
  cli?: Partial<Record<string, string | boolean>>;
}

const num = (v: unknown) => (typeof v === 'string' && v.trim() !== '' ? Number(v) : undefined);

export function buildConfig({ file = {}, env = {}, cli = {} }: ConfigSources): Config {
  const base = (stripComments(file) ?? {}) as Record<string, unknown>;
  const merged: Record<string, unknown> = { ...base };
  const set = (key: string, v: unknown) => {
    if (v !== undefined && !(typeof v === 'number' && Number.isNaN(v))) merged[key] = v;
  };
  set('host', env.AGENTCRAFT_HOST);
  set('port', num(env.AGENTCRAFT_PORT));
  set('home', env.AGENTCRAFT_HOME);
  set('backend', env.AGENTCRAFT_BACKEND);
  if (env.AGENTCRAFT_USE_CLAUDE_LOGIN === '1') merged.useClaudeLogin = true;
  if (cli['use-claude-login'] === true) merged.useClaudeLogin = true;

  set('host', cli.host);
  set('port', num(cli.port));
  set('home', cli.home);
  set('backend', cli.backend);
  set('maxConcurrent', num(cli['max-concurrent']));
  const daily = num(cli['max-budget']);
  if (daily !== undefined) merged.budget = { ...(merged.budget as object | undefined), dailyUsd: daily };
  const stepMs = num(cli['step-ms']);
  if (stepMs !== undefined) merged.sim = { ...(merged.sim as object | undefined), stepMs };
  const turns = num(cli['max-turns']);
  if (turns !== undefined) merged.maxTurns = { lead: turns, worker: turns };

  const parsed = ConfigSchema.safeParse(merged);
  if (!parsed.success) {
    throw new Error('invalid configuration:\n' + z.prettifyError(parsed.error));
  }
  const unknownCast = parsed.data.cast.filter((c) => !DEFAULT_CAST.some((m) => m.id === c.id));
  if (unknownCast.length) throw new Error(`unknown cast ids: ${unknownCast.map((c) => c.id).join(', ')}`);
  return parsed.data;
}

export function readConfigFile(path: string): unknown {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** The shared bridge token. Required, at least 16 characters, never logged. */
export function resolveToken(cfg: Config, env: NodeJS.ProcessEnv): string {
  const token = env.AGENTCRAFT_TOKEN ?? (cfg.tokenFile ? readFileSync(cfg.tokenFile, 'utf8').trim() : undefined);
  if (!token) throw new Error('missing bridge token: set AGENTCRAFT_TOKEN or tokenFile');
  if (token.length < 16) throw new Error('bridge token must be at least 16 characters');
  return token;
}
