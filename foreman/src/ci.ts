import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { run } from './util/exec.js';

export interface CiCommand {
  /** folder relative to the worktree ('' = root) */
  dir: string;
  cmd: string;
  args: string[];
}

export interface CiResult {
  ok: boolean;
  /** nothing to build was found */
  skipped: boolean;
  summary: string;
  /** last lines of the output of the failing (or last) command */
  output: string;
}

const MAX_BUILDS = 3;

/** The build command for one folder, or undefined if it has no known build file. */
export function buildCommand(worktree: string, dir: string): CiCommand | undefined {
  const at = (f: string) => existsSync(join(worktree, dir, f));
  if (at('pom.xml')) return { dir, cmd: 'mvn', args: ['-B', '-q', '-ntp', 'test'] };
  if (at('gradlew')) return { dir, cmd: './gradlew', args: ['test', '--no-daemon', '-q'] };
  if (at('build.gradle') || at('build.gradle.kts')) return { dir, cmd: 'gradle', args: ['test', '--no-daemon', '-q'] };
  if (at('package.json')) return { dir, cmd: 'npm', args: ['test', '--silent'] };
  return undefined;
}

/**
 * Which builds to run for a change: for each changed file the nearest folder with a build file
 * (the task's module first). At most three distinct builds.
 */
export function detectBuilds(worktree: string, changed: string[], module?: string): CiCommand[] {
  const dirs: string[] = [];
  const add = (d: string) => {
    if (!dirs.includes(d)) dirs.push(d);
  };
  if (module) add(module.replace(/\/+$/, ''));
  for (const file of changed) {
    let d = dirname(file);
    for (;;) {
      if (buildCommand(worktree, d === '.' ? '' : d)) {
        add(d === '.' ? '' : d);
        break;
      }
      if (d === '.' || d === '' || d === '/') break;
      d = dirname(d);
    }
  }
  return dirs
    .map((d) => buildCommand(worktree, d))
    .filter((c): c is CiCommand => !!c)
    .slice(0, MAX_BUILDS);
}

/** Splits a configured command like `mvn -B test -f {module}/pom.xml`. No shell is involved. */
export function parseCommand(line: string, module: string): CiCommand {
  const parts = line.replace(/\{module\}/g, module || '.').match(/"[^"]*"|'[^']*'|\S+/g) ?? [];
  const [cmd = 'true', ...args] = parts.map((p) => p.replace(/^["']|["']$/g, ''));
  return { dir: '', cmd, args };
}

export async function runCi(
  worktree: string,
  commands: CiCommand[],
  env: NodeJS.ProcessEnv,
  timeoutMs = 20 * 60_000,
): Promise<CiResult> {
  if (commands.length === 0) {
    return {
      ok: true,
      skipped: true,
      summary: 'nessuna build trovata (pom.xml, build.gradle, package.json)',
      output: '',
    };
  }
  const lines: string[] = [];
  let lastOutput = '';
  for (const c of commands) {
    const r = await run(c.cmd, c.args, { cwd: join(worktree, c.dir), env, timeoutMs, maxOutput: 20_000 });
    const label = `${c.dir || '.'}: ${c.cmd} ${c.args.join(' ')}`;
    lastOutput = (r.stdout + '\n' + r.stderr).trim().split('\n').slice(-60).join('\n');
    if (r.timedOut) return { ok: false, skipped: false, summary: `${label} — timeout`, output: lastOutput };
    if (r.code !== 0)
      return { ok: false, skipped: false, summary: `${label} — fallito (exit ${r.code})`, output: lastOutput };
    lines.push(`${label} — ok`);
  }
  return { ok: true, skipped: false, summary: lines.join('\n'), output: lastOutput };
}
