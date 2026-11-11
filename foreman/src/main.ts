import { existsSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { PROTOCOL_VERSION } from '@agentcraft-server/protocol';
import type { Backend } from './backend.js';
import { Bridge } from './bridge.js';
import { realClock } from './clock.js';
import { buildConfig, readConfigFile, resolveToken } from './config.js';
import { buildTeam, Foreman } from './foreman.js';
import { SimBackend } from './sim/scenario.js';
import { TeamState } from './state.js';
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
    'print-config': { type: 'boolean', default: false },
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
if (cfg.backend === 'sim') {
  backend = new SimBackend(state, bridge, realClock, { stepMs: cfg.sim.stepMs });
} else {
  console.error('The claude backend arrives in phase 4. Use --backend sim for now.');
  process.exit(2);
}
foreman.setBackend(backend);
backend.start();

await bridge.start();
log(`agentcraft foreman ${FOREMAN_VERSION}, backend ${cfg.backend}, listening on ws://${cfg.host}:${bridge.port}`);

const shutdown = async () => {
  log('stopping');
  backend.stop();
  await bridge.stop();
  process.exit(0);
};
process.on('SIGINT', () => void shutdown());
process.on('SIGTERM', () => void shutdown());
