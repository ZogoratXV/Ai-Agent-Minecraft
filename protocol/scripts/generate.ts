/**
 * Generates, from the zod schemas in src/:
 *   protocol/schema/*.schema.json   JSON Schema of both directions
 *   protocol/schema/manifest.json   message types and tool names (checked by the plugin's tests)
 *   protocol/examples/<type>.json   one validated example per message type (contract fixtures)
 *   docs/protocol.md                human reference
 *
 * `--check` writes nothing and fails if any generated file is out of date (used in CI).
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import {
  Blueprint,
  EXAMPLES,
  ForemanToPlugin,
  PluginToForeman,
  PROTOCOL_VERSION,
  TOOL_NAMES,
  STAGING_ONLY_TOOLS,
} from '../src/index.js';

const here = dirname(fileURLToPath(import.meta.url));
const pkg = resolve(here, '..');
const root = resolve(pkg, '..');
const check = process.argv.includes('--check');

type Json = Record<string, unknown>;
const files = new Map<string, string>();
const json = (v: unknown) => JSON.stringify(v, null, 2) + '\n';

const schemaOf = (s: z.ZodType) => z.toJSONSchema(s, { target: 'draft-2020-12', unrepresentable: 'any' }) as Json;

files.set(
  join(pkg, 'schema', 'plugin-to-foreman.schema.json'),
  json({ title: 'PluginToForeman', ...schemaOf(PluginToForeman) }),
);
files.set(
  join(pkg, 'schema', 'foreman-to-plugin.schema.json'),
  json({ title: 'ForemanToPlugin', ...schemaOf(ForemanToPlugin) }),
);

files.set(
  join(pkg, 'schema', 'manifest.json'),
  json({
    version: PROTOCOL_VERSION,
    pluginToForeman: PluginToForeman.options.map((o) => o.shape.type.value),
    foremanToPlugin: ForemanToPlugin.options.map((o) => o.shape.type.value),
    tools: TOOL_NAMES,
    stagingOnlyTools: STAGING_ONLY_TOOLS,
  }),
);

files.set(
  join(root, 'blueprints', 'blueprint.schema.json'),
  json({ title: 'AgentCraft blueprint v1', ...schemaOf(Blueprint) }),
);

const directions = [
  { title: 'Plugin → foreman', union: PluginToForeman },
  { title: 'Foreman → plugin', union: ForemanToPlugin },
] as const;

for (const { union } of directions) {
  for (const option of union.options) {
    const type = option.shape.type.value;
    const example = EXAMPLES[type];
    if (!example) throw new Error(`missing example for ${type}`);
    const parsed = option.safeParse(example);
    if (!parsed.success) throw new Error(`example for ${type} is invalid: ${parsed.error.message}`);
    files.set(join(pkg, 'examples', `${type}.json`), json(example));
  }
}

// ------------------------------------------------------------------ docs/protocol.md

function typeName(s: Json | undefined): string {
  if (!s) return 'any';
  if (s.const !== undefined) return `\`${JSON.stringify(s.const)}\``;
  if (Array.isArray(s.enum)) return s.enum.map((e) => `\`${String(e)}\``).join(' \\| ');
  if (Array.isArray(s.anyOf)) return (s.anyOf as Json[]).map(typeName).join(' \\| ');
  if (Array.isArray(s.oneOf)) return (s.oneOf as Json[]).map(typeName).join(' \\| ');
  if (s.type === 'array') {
    if (Array.isArray(s.prefixItems)) return `[${(s.prefixItems as Json[]).map(typeName).join(', ')}]`;
    return `${typeName(s.items as Json)}[]`;
  }
  if (s.type === 'object') return 'object';
  if (typeof s.type === 'string') return s.type;
  return 'any';
}

function fieldRows(s: Json, prefix = ''): string[] {
  const props = (s.properties ?? {}) as Record<string, Json>;
  const required = new Set((s.required ?? []) as string[]);
  const rows: string[] = [];
  for (const [name, p] of Object.entries(props)) {
    if (!prefix && (name === 'v' || name === 'type')) continue;
    const desc = String(p.description ?? '').replace(/\|/g, '\\|');
    rows.push(`| \`${prefix}${name}\` | ${typeName(p)} | ${required.has(name) ? 'yes' : 'no'} | ${desc} |`);
  }
  return rows;
}

const md: string[] = [];
md.push(`# AgentCraft Server protocol v${PROTOCOL_VERSION}`, '');
md.push('> GENERATED from `protocol/src/*.ts` by `npm run gen:protocol`. Do not edit by hand.', '');
md.push('## Transport', '');
md.push(
  '- The **foreman** listens on `ws://127.0.0.1:7878` (configurable, private networks only). The **plugin** connects as client.',
  '- The plugin sends `Authorization: Bearer <token>` on the upgrade request and repeats the token in `hello`. Wrong token: HTTP 401 / `error {code: "unauthorized"}` and close.',
  '- Upgrade requests with an `Origin` header (browsers) are rejected with HTTP 403.',
  '- One JSON object per text frame: `{ "v": 1, "type": "<type>", "id"?: "<correlation id>", ...payload }`.',
  '- A message with an `id` is answered with `ack { re: id, ok }` (except `tool.call`, answered with `tool.result`).',
  '- Timestamps are epoch milliseconds. Optional fields are omitted, never `null`. Receivers ignore unknown fields.',
  '- `*.upsert` replaces the whole entity by id; `feed.add` appends. After reconnecting, the plugin sends `hello` again and the `snapshot` rebuilds everything.',
  '',
);
md.push('## World tools', '');
md.push('`tool.call.call.tool` is one of: ' + TOOL_NAMES.map((t) => `\`${t}\``).join(', ') + '.', '');
md.push(
  `Staging only (refused with \`not_staging\` when the plugin's configured role is production): ${STAGING_ONLY_TOOLS.map((t) => `\`${t}\``).join(', ')}.`,
  '',
);
md.push(
  'The plugin validates every call (region, block blacklist, command whitelist, volume, rate limit) and never trusts the arguments.',
  '',
);

for (const { title, union } of directions) {
  md.push(`## ${title}`, '');
  for (const option of union.options) {
    const type = option.shape.type.value;
    const s = schemaOf(option);
    md.push(`### \`${type}\``, '', String(s.description ?? ''), '');
    const rows = fieldRows(s);
    if (rows.length) md.push('| field | type | required | notes |', '| --- | --- | --- | --- |', ...rows, '');
    md.push('```json', JSON.stringify(EXAMPLES[type]), '```', '');
  }
}
files.set(join(root, 'docs', 'protocol.md'), md.join('\n'));

// ------------------------------------------------------------------ write or check

let stale = 0;
for (const [path, content] of files) {
  const current = existsSync(path) ? readFileSync(path, 'utf8') : undefined;
  if (current === content) continue;
  if (check) {
    console.error(`out of date: ${path}`);
    stale++;
  } else {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, content);
    console.log(`wrote ${path}`);
  }
}
// examples that no longer correspond to a message type
const exampleDir = join(pkg, 'examples');
if (existsSync(exampleDir)) {
  for (const f of readdirSync(exampleDir)) {
    if (!files.has(join(exampleDir, f))) {
      console.error(`stale example (no such message type): ${f}`);
      stale++;
    }
  }
}
if (stale) {
  console.error('run `npm run gen:protocol` and commit the result');
  process.exit(1);
}
if (check) console.log('protocol files are up to date');
