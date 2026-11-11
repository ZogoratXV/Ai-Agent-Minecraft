// How the agents authenticate with Claude: always API authentication (ANTHROPIC_API_KEY or a cloud
// provider), as the Agent SDK docs require for tools like this one. The server user's own claude.ai
// login is never used: its token variable is removed and the CLI gets its own config folder, so it
// cannot find a stored login either. Same rule as AgentCraft's agents/claude/auth.ts (MIT).

export const PROVIDER_SWITCHES: Record<string, string> = {
  CLAUDE_CODE_USE_BEDROCK: 'Amazon Bedrock',
  CLAUDE_CODE_USE_VERTEX: 'Google Vertex AI',
  CLAUDE_CODE_USE_FOUNDRY: 'Microsoft Foundry',
};

const LOGIN_VARS = ['CLAUDE_CODE_OAUTH_TOKEN', 'CLAUDE_CONFIG_DIR'];

const truthy = (v: string | undefined) => !!v && v !== '0' && v.toLowerCase() !== 'false';

export type ApiAuth = { ok: true; source: string } | { ok: false };

export function detectApiAuth(env: NodeJS.ProcessEnv): ApiAuth {
  if (env.ANTHROPIC_API_KEY?.trim()) return { ok: true, source: 'API key' };
  for (const [k, name] of Object.entries(PROVIDER_SWITCHES)) if (truthy(env[k])) return { ok: true, source: name };
  if (env.ANTHROPIC_AUTH_TOKEN?.trim() && env.ANTHROPIC_BASE_URL?.trim()) return { ok: true, source: 'API gateway' };
  return { ok: false };
}

export const NO_API_AUTH_MESSAGE =
  'manca ANTHROPIC_API_KEY (o Bedrock/Vertex): imposta la chiave (foreman.api-key-file) e riavvia il server';

/** The agent CLI environment: no claude.ai login token, and a private Claude config folder. */
export function withApiAuthOnly(env: NodeJS.ProcessEnv, configDir: string): NodeJS.ProcessEnv {
  const out: NodeJS.ProcessEnv = {};
  for (const [k, v] of Object.entries(env)) if (!LOGIN_VARS.includes(k.toUpperCase())) out[k] = v;
  out.CLAUDE_CONFIG_DIR = configDir;
  return out;
}
