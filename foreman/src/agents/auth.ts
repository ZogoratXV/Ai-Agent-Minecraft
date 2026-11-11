// How the agents authenticate with Claude.
//
// Default: API authentication (ANTHROPIC_API_KEY or a cloud provider), as the Agent SDK docs ask
// for tools like this one. The login of whoever runs the server is never picked up by accident:
// the token variable is removed and the CLI gets its own config folder.
//
// Opt-in (useClaudeLogin, for personal use only, like AgentCraft's --use-claude-login): the
// agents use a Claude subscription through a long-lived token made with `claude setup-token`
// (CLAUDE_CODE_OAUTH_TOKEN). API keys are then removed, so a key left in the environment cannot
// silently bill instead. Same rules as AgentCraft's agents/claude/auth.ts (MIT).

export const PROVIDER_SWITCHES: Record<string, string> = {
  CLAUDE_CODE_USE_BEDROCK: 'Amazon Bedrock',
  CLAUDE_CODE_USE_VERTEX: 'Google Vertex AI',
  CLAUDE_CODE_USE_FOUNDRY: 'Microsoft Foundry',
};

const LOGIN_VARS = ['CLAUDE_CODE_OAUTH_TOKEN'];
const API_VARS = ['ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN'];

const truthy = (v: string | undefined) => !!v && v !== '0' && v.toLowerCase() !== 'false';

export type ApiAuth = { ok: true; source: string } | { ok: false };

export function detectApiAuth(env: NodeJS.ProcessEnv, useClaudeLogin = false): ApiAuth {
  if (useClaudeLogin) {
    return env.CLAUDE_CODE_OAUTH_TOKEN?.trim()
      ? { ok: true, source: 'abbonamento Claude, uso personale' }
      : { ok: false };
  }
  if (env.ANTHROPIC_API_KEY?.trim()) return { ok: true, source: 'API key' };
  for (const [k, name] of Object.entries(PROVIDER_SWITCHES)) if (truthy(env[k])) return { ok: true, source: name };
  if (env.ANTHROPIC_AUTH_TOKEN?.trim() && env.ANTHROPIC_BASE_URL?.trim()) return { ok: true, source: 'API gateway' };
  return { ok: false };
}

export const NO_API_AUTH_MESSAGE =
  'manca ANTHROPIC_API_KEY (o Bedrock/Vertex): imposta la chiave (foreman.api-key-file) e riavvia il server';
export const NO_LOGIN_MESSAGE =
  "manca il token dell'abbonamento: crealo con `claude setup-token`, mettilo in foreman.claude-token-file e riavvia";

/** The agent CLI environment for the chosen mode, always with a private Claude config folder. */
export function withAuthMode(env: NodeJS.ProcessEnv, configDir: string, useClaudeLogin = false): NodeJS.ProcessEnv {
  const drop = [...(useClaudeLogin ? API_VARS : LOGIN_VARS), 'CLAUDE_CONFIG_DIR'];
  const out: NodeJS.ProcessEnv = {};
  for (const [k, v] of Object.entries(env)) if (!drop.includes(k.toUpperCase())) out[k] = v;
  out.CLAUDE_CONFIG_DIR = configDir;
  return out;
}

/** Kept for callers written before the subscription mode. */
export function withApiAuthOnly(env: NodeJS.ProcessEnv, configDir: string): NodeJS.ProcessEnv {
  return withAuthMode(env, configDir, false);
}
