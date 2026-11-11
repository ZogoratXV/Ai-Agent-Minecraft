import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { detectBuilds, parseCommand, platformCommand, runCi } from '../src/ci.js';

describe('ci', () => {
  it('finds the nearest build of each changed file, the task module first', () => {
    const wt = mkdtempSync(join(tmpdir(), 'ac-ci-'));
    mkdirSync(join(wt, 'modules/A-1.0/src'), { recursive: true });
    mkdirSync(join(wt, 'modules/B-2.0'), { recursive: true });
    writeFileSync(join(wt, 'modules/A-1.0/pom.xml'), '<project/>');
    writeFileSync(join(wt, 'modules/B-2.0/build.gradle'), '');
    const builds = detectBuilds(wt, ['modules/A-1.0/src/X.java', 'modules/B-2.0/y.txt', 'README.md'], 'modules/A-1.0');
    expect(builds.map((b) => [b.dir, b.cmd])).toEqual([
      ['modules/A-1.0', 'mvn'],
      ['modules/B-2.0', 'gradle'],
    ]);
  });

  it('runs .cmd tools through cmd.exe on Windows only', () => {
    expect(platformCommand('mvn', ['-B', 'test'], 'win32')).toEqual([
      'cmd.exe',
      ['/d', '/s', '/c', 'mvn', '-B', 'test'],
    ]);
    expect(platformCommand('./gradlew', ['test'], 'win32')[1]).toContain('gradlew.bat');
    expect(platformCommand('mvn', ['-B', 'test'], 'linux')).toEqual(['mvn', ['-B', 'test']]);
    expect(platformCommand('node', ['x.js'], 'win32')).toEqual(['node', ['x.js']]);
  });

  it('parses a configured command without a shell', () => {
    expect(parseCommand('mvn -B -f "{module}/pom.xml" test', 'modules/A')).toEqual({
      dir: '',
      cmd: 'mvn',
      args: ['-B', '-f', 'modules/A/pom.xml', 'test'],
    });
  });

  it('reports failures with the end of the output', async () => {
    const wt = mkdtempSync(join(tmpdir(), 'ac-ci-'));
    const ok = await runCi(wt, [{ dir: '', cmd: 'node', args: ['-e', 'console.log("fine")'] }], process.env);
    expect(ok.ok).toBe(true);
    const bad = await runCi(
      wt,
      [{ dir: '', cmd: 'node', args: ['-e', 'console.error("rotto"); process.exit(3)'] }],
      process.env,
    );
    expect(bad).toMatchObject({ ok: false });
    expect(bad.output).toContain('rotto');
    expect(bad.summary).toContain('exit 3');
  });
});
