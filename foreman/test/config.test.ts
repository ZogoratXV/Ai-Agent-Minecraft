import { describe, expect, it } from 'vitest';
import { buildConfig, isPrivateHost, resolveToken, stripComments } from '../src/config.js';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

describe('config', () => {
  it('has safe defaults', () => {
    const cfg = buildConfig({});
    expect(cfg.host).toBe('127.0.0.1');
    expect(cfg.port).toBe(7878);
    expect(cfg.backend).toBe('sim');
    expect(cfg.maxConcurrent).toBe(3);
    expect(cfg.models).toEqual({ lead: 'claude-opus-5-5', worker: 'claude-sonnet-5-5', light: 'claude-haiku-5-5' });
    expect(cfg.budget.dailyUsd).toBe(15);
  });

  it('refuses public bind addresses', () => {
    expect(() => buildConfig({ cli: { host: '0.0.0.0' } })).toThrow(/private/);
    expect(() => buildConfig({ cli: { host: '8.8.8.8' } })).toThrow(/private/);
    expect(buildConfig({ cli: { host: '192.168.1.20' } }).host).toBe('192.168.1.20');
  });

  it('classifies hosts', () => {
    for (const h of ['127.0.0.1', 'localhost', '::1', '10.1.2.3', '172.20.0.5', '192.168.0.9', 'fd00::1']) {
      expect(isPrivateHost(h), h).toBe(true);
    }
    for (const h of ['0.0.0.0', '172.32.0.1', '1.1.1.1', 'example.com', '::']) {
      expect(isPrivateHost(h), h).toBe(false);
    }
  });

  it('applies file < env < cli', () => {
    const cfg = buildConfig({
      file: { port: 7001, backend: 'claude' },
      env: { AGENTCRAFT_PORT: '7002' },
      cli: { port: '7003', 'max-budget': '4' },
    });
    expect(cfg.port).toBe(7003);
    expect(cfg.backend).toBe('claude');
    expect(cfg.budget.dailyUsd).toBe(4);
    expect(cfg.budget.monthlyUsd).toBe(300);
  });

  it('accepts the commented example config', () => {
    const file = JSON.parse(readFileSync(join(import.meta.dirname, '..', 'config.example.json'), 'utf8'));
    const cfg = buildConfig({ file });
    expect(cfg.cast.length).toBeGreaterThan(0);
  });

  it('strips comment keys recursively', () => {
    expect(stripComments({ '//': 'x', a: { '// b': 1, c: [{ '//': 2, d: 3 }] } })).toEqual({ a: { c: [{ d: 3 }] } });
  });

  it('rejects unknown cast ids', () => {
    expect(() => buildConfig({ file: { cast: [{ id: 'nessuno' }] } })).toThrow(/unknown cast/);
  });

  it('requires a long enough token', () => {
    const cfg = buildConfig({});
    expect(() => resolveToken(cfg, {})).toThrow(/missing/);
    expect(() => resolveToken(cfg, { AGENTCRAFT_TOKEN: 'short' })).toThrow(/16/);
    expect(resolveToken(cfg, { AGENTCRAFT_TOKEN: 'a'.repeat(32) })).toHaveLength(32);
  });
});
