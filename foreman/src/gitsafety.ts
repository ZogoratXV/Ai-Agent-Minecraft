// Git safety environment for every process an agent can influence (its Claude CLI process, every
// Bash command it runs, and the CI runs in its worktree). Same technique as AgentCraft's
// foreman/src/gitsafety.ts (MIT, see NOTICE), reimplemented here:
//
//   GIT_ALLOW_PROTOCOL=agentcraft-none   no git transport at all: push, fetch, clone, ls-remote fail
//   protocol.allow=never                  the same through env-scoped config
//   url.<dead>.pushInsteadOf=""           every push URL is rewritten to one git cannot use
//   commit/tag.gpgsign=false, gpg.*.program=<missing>   agents never sign with the user's keys
//   GIT_CEILING_DIRECTORIES                git never walks out of the worktree
//   GIT_DIR, GIT_WORK_TREE, ... removed    inherited redirects cannot point git elsewhere
//
// Env-scoped config (GIT_CONFIG_COUNT/KEY/VALUE) wins over the repository's and the user's own
// config. Not a sandbox: the policy also refuses commands that mention these variables.

import { dirname } from 'node:path';

export const PUSH_BLOCK_URL = 'agentcraft-push-blocked:///';
export const NO_SIGNING_PROGRAM = 'agentcraft-signing-disabled';

export const GIT_SAFETY_VARS = [
  'GIT_ALLOW_PROTOCOL',
  'GIT_CONFIG_COUNT',
  'GIT_CONFIG_KEY_',
  'GIT_CONFIG_VALUE_',
  'GIT_CONFIG_PARAMETERS',
  'GIT_CONFIG_NOSYSTEM',
  'GIT_CONFIG_GLOBAL',
  'GIT_CONFIG_SYSTEM',
  'GIT_CEILING_DIRECTORIES',
];

export const GIT_REDIRECT_VARS = [
  'GIT_DIR',
  'GIT_WORK_TREE',
  'GIT_INDEX_FILE',
  'GIT_COMMON_DIR',
  'GIT_OBJECT_DIRECTORY',
  'GIT_ALTERNATE_OBJECT_DIRECTORIES',
  'GIT_NAMESPACE',
  'GIT_DISCOVERY_ACROSS_FILESYSTEM',
];

/** Environment for an agent working in `worktree`, with its git identity. */
export function agentEnv(
  base: NodeJS.ProcessEnv,
  worktree: string,
  identity: { name: string; email: string },
): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...base };
  for (const k of Object.keys(env)) {
    if (GIT_REDIRECT_VARS.includes(k.toUpperCase()) || k.toUpperCase().startsWith('GIT_CONFIG_')) delete env[k];
  }
  const pairs: Array<[string, string]> = [
    ['protocol.allow', 'never'],
    [`url.${PUSH_BLOCK_URL}.pushInsteadOf`, ''],
    ['commit.gpgsign', 'false'],
    ['tag.gpgsign', 'false'],
    ['gpg.program', NO_SIGNING_PROGRAM],
    ['gpg.ssh.program', NO_SIGNING_PROGRAM],
    ['gpg.x509.program', NO_SIGNING_PROGRAM],
    ['core.hooksPath', '/dev/null'],
  ];
  pairs.forEach(([k, v], i) => {
    env[`GIT_CONFIG_KEY_${i}`] = k;
    env[`GIT_CONFIG_VALUE_${i}`] = v;
  });
  env.GIT_CONFIG_COUNT = String(pairs.length);
  env.GIT_ALLOW_PROTOCOL = 'agentcraft-none';
  env.GIT_TERMINAL_PROMPT = '0';
  env.GCM_INTERACTIVE = 'never';
  env.GIT_CEILING_DIRECTORIES = dirname(worktree);
  env.GIT_AUTHOR_NAME = identity.name;
  env.GIT_AUTHOR_EMAIL = identity.email;
  env.GIT_COMMITTER_NAME = identity.name;
  env.GIT_COMMITTER_EMAIL = identity.email;
  return env;
}
