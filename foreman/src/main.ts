import { existsSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { PROTOCOL_VERSION } from '@agentcraft-server/protocol';
import type { Backend } from './backend.js';
import { Bridge } from './bridge.js';
import { realClock } from './clock.js';
import { buildConfig, readConfigFile, resolveToken } from './config.js';
import { buildTeam, Foreman } from './foreman.js';
import { join, resolve } from 'node:path';
import { SimBackend } from './sim/scenario.js';
import { DEFAULT_CAST } from './cast.js';
import { Memory } from './memory.js';
import { RepoManager, type RepoSettings } from './repos.js';
import { Store } from './store.js';
import { dropUndefined, TeamState, type TeamData } from './state.js';
import { FOREMAN_VERSION } from './version.js';

const { values } = parseArgs({
  options: {
    config: { type: 'string', default: 'config.json' },
    backend: { type: 'string' },
    host: { type: 'string' },
    port: { type: 'string' },
    home: { type: 'string' },
    'max-concurrent': { type: 'string' },
    'max-budget': { type: 'string' },
    'max-turns': { type: 'string' },
    'step-ms': { type: 'string' },
    'use-claude-login': { type: 'boolean', default: false },
    'print-config': { type: 'boolean', default: false },
    'parent-pid': { type: 'string' },
    help: { type: 'boolean', default: false },
  },
});

if (values.help) {
  console.log(`agentcraft foreman ${FOREMAN_VERSION} (protocol v${PROTOCOL_VERSION})

  --config <file>         config file (default: config.json, optional)
  --backend sim|claude    sim = scripted team, no API usage
  --host, --port          bridge address (loopback/private only, default 127.0.0.1:7878)
  --max-concurrent <n>    workers running at once (default 3)
  --max-budget <usd>      daily spend cap (default 15)
  --max-turns <n>         turn cap per agent run
  --step-ms <ms>          sim: pause between scripted steps (default 2500)
  --use-claude-login      personal use only: a Claude subscription (CLAUDE_CODE_OAUTH_TOKEN from
                          \`claude setup-token\`) instead of an API key
  --print-config          print the effective configuration and exit

  Secrets: AGENTCRAFT_TOKEN (bridge token), ANTHROPIC_API_KEY (claude backend).`);
  process.exit(0);
}

const cfg = buildConfig({
  file: existsSync(values.config) ? readConfigFile(values.config) : {},
  env: process.env,
  cli: values,
});

if (values['print-config']) {
  console.log(JSON.stringify(cfg, null, 2));
  process.exit(0);
}

const log = (line: string) => console.log(`[${new Date().toISOString()}] ${line}`);
const token = resolveToken(cfg, process.env);
const state = new TeamState(realClock, {
  version: FOREMAN_VERSION,
  backend: cfg.backend,
  auth: cfg.backend === 'sim' ? 'ok' : 'unknown',
  paused: false,
});
for (const agent of buildTeam(cfg)) state.agents.set(agent.id, agent);

const foreman = new Foreman(state, realClock);
const bridge = new Bridge({
  host: cfg.host,
  port: cfg.port,
  token,
  snapshot: () => state.snapshot(),
  onIntent: (intent, session) => {
    log(`${session.server.id}: ${intent.type}`);
    return foreman.handle(intent);
  },
  onSessionChange: (s) =>
    log(`connected plugins: ${s.map((x) => `${x.server.id}(${x.server.role})`).join(', ') || 'none'}`),
  log,
});
state.on('message', (m) => bridge.broadcast(m));

let backend: Backend;
let store: Store | undefined;
if (cfg.backend === 'sim') {
  backend = new SimBackend(state, bridge, realClock, { stepMs: cfg.sim.stepMs });
} else {
  const home = resolve(cfg.home);
  store = new Store(home);
  const saved = store.readJson<TeamData>('state.json');
  if (saved) state.importData(saved);
  const persist = store;
  state.on('message', () => persist.scheduleWrite('state.json', () => state.exportData()));
  const repos = new RepoManager(join(home, 'worktrees'), (r) => state.putRepo(r));
  state.reposView = () => [...repos.repos.values()].map((r) => RepoManager.view(r));
  const { ClaudeRunner } = await import('./agents/claude.js');
  const { TeamBackend } = await import('./agents/team.js');
  const team = new TeamBackend({
    cfg,
    state,
    repos,
    memory: new Memory(join(home, 'memory')),
    runner: new ClaudeRunner(),
    store,
    clock: realClock,
    cast: DEFAULT_CAST,
    home,
    serverName: cfg.serverName,
    log,
    tools: bridge,
  });
  await team.init(cfg.repos.map((r) => dropUndefined({ ...r, path: resolve(r.path) }) as RepoSettings));
  backend = team;
}
foreman.setBackend(backend);
backend.start();
if (cfg.backend === 'claude' && cfg.useClaudeLogin) {
  log('Agents use a Claude subscription (personal use only): its usage limits are shared with your own Claude use.');
}
if (cfg.backend === 'claude' && state.status.auth === 'failed') {
  log('WARNING: no ANTHROPIC_API_KEY (or Bedrock/Vertex) in the environment: the agents will not work');
}

await bridge.start();
log(`agentcraft foreman ${FOREMAN_VERSION}, backend ${cfg.backend}, listening on ws://${cfg.host}:${bridge.port}`);

const shutdown = async () => {
  log('stopping');
  backend.stop();
  store?.flush();
  await bridge.stop();
  process.exit(0);
};
process.on('SIGINT', () => void shutdown());
process.on('SIGTERM', () => void shutdown());

// Started by the plugin: stop when the Minecraft server is gone, even if it was killed without
// shutting us down (closing its window on Windows, a crash), so no orphan keeps the port.
const parentPid = Number(values['parent-pid']);
if (Number.isInteger(parentPid) && parentPid > 0) {
  setInterval(() => {
    try {
      process.kill(parentPid, 0);
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ESRCH') {
        log(`the server (pid ${parentPid}) is gone`);
        void shutdown();
      }
    }
  }, 3000).unref();
}
