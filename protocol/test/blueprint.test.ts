import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { Blueprint } from '../src/index.js';

const example = JSON.parse(
  readFileSync(join(import.meta.dirname, '../../blueprints/examples/casa-medievale.json'), 'utf8'),
);

describe('blueprint', () => {
  it('accepts the medieval house example', () => {
    const r = Blueprint.safeParse(example);
    expect(r.success, r.success ? '' : r.error.message).toBe(true);
  });

  it('refuses unknown palette letters, points outside the size and bad module links', () => {
    const bad = structuredClone(example);
    bad.modules[0].ops.push({ op: 'set', at: [20, 0, 0], block: 'z' });
    bad.modules[1].dependsOn = ['nessuno'];
    const r = Blueprint.safeParse(bad);
    expect(r.success).toBe(false);
    const msg = r.success ? '' : r.error.message;
    expect(msg).toContain("'z' is not in the palette");
    expect(msg).toContain('outside size');
    expect(msg).toContain('unknown nessuno');
  });
});
