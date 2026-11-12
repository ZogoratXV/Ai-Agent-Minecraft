import { STAGING_ONLY_TOOLS, TOOL_NAMES } from '@agentcraft-server/protocol';
import { describe, expect, it } from 'vitest';
import { DEFAULT_CAST } from '../src/cast.js';
import { ROLES, toolsFor } from '../src/roles.js';

describe('roles and cast', () => {
  it('only the lead gets lead tools', () => {
    expect(toolsFor('lead')).toContain('create_task');
    for (const role of ['developer', 'qa', 'architect', 'builder', 'analyst'] as const) {
      expect(toolsFor(role)).not.toContain('create_task');
      expect(toolsFor(role)).not.toContain('request_merge');
    }
  });

  it('only builders may place blocks, only qa may simulate players', () => {
    for (const [role, spec] of Object.entries(ROLES)) {
      expect(spec.worldTools.includes('world.place'), role).toBe(role === 'builder');
      expect(spec.worldTools.includes('player.simulate'), role).toBe(role === 'qa');
      for (const t of STAGING_ONLY_TOOLS.filter((x) => x.startsWith('server.'))) {
        expect(spec.worldTools.includes(t), role).toBe(role === 'developer');
      }
    }
  });

  it('every world tool referenced exists in the protocol', () => {
    for (const spec of Object.values(ROLES)) for (const t of spec.worldTools) expect(TOOL_NAMES).toContain(t);
  });

  it('cast ids are unique and there is exactly one lead', () => {
    expect(new Set(DEFAULT_CAST.map((m) => m.id)).size).toBe(DEFAULT_CAST.length);
    expect(DEFAULT_CAST.filter((m) => m.role === 'lead')).toHaveLength(1);
  });
});
