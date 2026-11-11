import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  EXAMPLES,
  FOREMAN_TO_PLUGIN_TYPES,
  ForemanToPlugin,
  PLUGIN_TO_FOREMAN_TYPES,
  PluginToForeman,
  ToolInvocation,
} from '../src/index.js';

const examplesDir = join(import.meta.dirname, '..', 'examples');

describe('protocol', () => {
  it('has exactly one example per message type', () => {
    expect(Object.keys(EXAMPLES).sort()).toEqual([...PLUGIN_TO_FOREMAN_TYPES, ...FOREMAN_TO_PLUGIN_TYPES].sort());
  });

  it('message types are unique across directions', () => {
    const all = [...PLUGIN_TO_FOREMAN_TYPES, ...FOREMAN_TO_PLUGIN_TYPES];
    expect(new Set(all).size).toBe(all.length);
  });

  it('every committed fixture parses in its direction', () => {
    for (const file of readdirSync(examplesDir)) {
      const msg = JSON.parse(readFileSync(join(examplesDir, file), 'utf8'));
      const schema = PLUGIN_TO_FOREMAN_TYPES.includes(msg.type) ? PluginToForeman : ForemanToPlugin;
      expect(schema.safeParse(msg).success, file).toBe(true);
    }
  });

  it('rejects a wrong protocol version', () => {
    expect(PluginToForeman.safeParse({ ...EXAMPLES['goal.submit'], v: 2 }).success).toBe(false);
  });

  it('rejects unknown message types', () => {
    expect(PluginToForeman.safeParse({ v: 1, type: 'server.op', actor: {} }).success).toBe(false);
  });

  it('ignores unknown fields', () => {
    const parsed = PluginToForeman.parse({ ...EXAMPLES['goal.submit'], futureField: 1 });
    expect('futureField' in parsed).toBe(false);
  });

  it('rejects unknown tools and malformed tool args', () => {
    expect(ToolInvocation.safeParse({ tool: 'server.op', args: {} }).success).toBe(false);
    expect(ToolInvocation.safeParse({ tool: 'server.command', args: { command: '', as: 'console' } }).success).toBe(
      false,
    );
    expect(ToolInvocation.safeParse({ tool: 'world.undo', args: { jobId: 'j1' } }).success).toBe(true);
  });

  it('rejects a short token in hello', () => {
    expect(PluginToForeman.safeParse({ ...EXAMPLES.hello, token: 'short' }).success).toBe(false);
  });
});
