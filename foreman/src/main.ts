import { existsSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { PROTOCOL_VERSION } from '@agentcraft-server/protocol';
import { buildConfig, readConfigFile } from './config.js';
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

console.log(`agentcraft foreman ${FOREMAN_VERSION}, backend ${cfg.backend}, bridge ${cfg.host}:${cfg.port}`);
console.log('Skeleton only: the bridge and the sim backend arrive in phase 3.');
