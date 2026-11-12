import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { platformCommand } from './ci.js';
import { run } from './util/exec.js';

/** Texts of the deploy decision; the plugin checks the same strings. */
export const INSTALL_AND_RESTART = 'Installa e riavvia';
export const INSTALL_ONLY = 'Installa, riavvio io';
export const DEPLOY_OPTIONS = [INSTALL_AND_RESTART, INSTALL_ONLY, 'No'];

export interface PluginInfo {
  name: string;
  version: string;
  commands: string[];
}

/** The nearest folder (from the module up to the worktree root) with a Maven or Gradle build. */
export function findBuildDir(
  worktree: string,
  module: string | undefined,
): { dir: string; tool: 'maven' | 'gradle' } | undefined {
  let d = join(worktree, module ?? '');
  for (;;) {
    if (existsSync(join(d, 'pom.xml'))) return { dir: d, tool: 'maven' };
    if (['build.gradle', 'build.gradle.kts'].some((f) => existsSync(join(d, f)))) return { dir: d, tool: 'gradle' };
    if (d === worktree || dirname(d) === d || !d.startsWith(worktree)) return undefined;
    d = dirname(d);
  }
}

/** Builds the plugin jar of a module, without the tests (they already ran). */
export async function packageJar(
  worktree: string,
  module: string | undefined,
  env: NodeJS.ProcessEnv,
): Promise<{ jar?: string; error?: string }> {
  const b = findBuildDir(worktree, module);
  if (!b) return { error: 'nessuna build Maven o Gradle' };
  const [cmd, args] =
    b.tool === 'maven'
      ? platformCommand('mvn', ['-B', '-q', '-ntp', '-DskipTests', 'package'])
      : existsSync(join(b.dir, 'gradlew'))
        ? platformCommand('./gradlew', ['build', '-x', 'test', '--no-daemon', '-q'])
        : platformCommand('gradle', ['build', '-x', 'test', '--no-daemon', '-q']);
  const r = await run(cmd, args, { cwd: b.dir, env, timeoutMs: 20 * 60_000, maxOutput: 20_000 });
  if (r.code !== 0 || r.timedOut) {
    return { error: `build del jar fallita: ${(r.stderr || r.stdout).trim().split('\n').slice(-5).join(' ')}` };
  }
  const jar = pickJar(join(b.dir, b.tool === 'maven' ? 'target' : join('build', 'libs')));
  return jar ? { jar } : { error: 'nessun jar prodotto dalla build' };
}

/** The plugin jar among the build outputs: not sources/javadoc/tests, the biggest (shaded) one. */
export function pickJar(dir: string): string | undefined {
  if (!existsSync(dir)) return undefined;
  const jars = readdirSync(dir)
    .filter(
      (f) => f.endsWith('.jar') && !/(-sources|-javadoc|-tests|-plain)\.jar$/.test(f) && !f.startsWith('original-'),
    )
    .map((f) => ({ f, size: statSync(join(dir, f)).size }));
  jars.sort((a, b) => b.size - a.size);
  return jars[0] ? join(dir, jars[0].f) : undefined;
}

/** name, version and commands from the module's plugin.yml / paper-plugin.yml (best effort). */
export function readPluginInfo(worktree: string, module: string | undefined): PluginInfo | undefined {
  const b = findBuildDir(worktree, module);
  const base = b?.dir ?? join(worktree, module ?? '');
  for (const f of ['paper-plugin.yml', 'plugin.yml']) {
    const p = join(base, 'src', 'main', 'resources', f);
    if (!existsSync(p)) continue;
    const text = readFileSync(p, 'utf8');
    const name = /^name:\s*['"]?([A-Za-z0-9_.-]+)/m.exec(text)?.[1];
    if (!name) continue;
    let version = /^version:\s*['"]?([^'"\n]+)/m.exec(text)?.[1]?.trim() ?? '?';
    if (version.includes('${') && b?.tool === 'maven') {
      const pom = readFileSync(join(b.dir, 'pom.xml'), 'utf8').replace(/<parent>[\s\S]*?<\/parent>/, '');
      version = /<version>([^<]+)<\/version>/.exec(pom)?.[1] ?? version;
    }
    const commands: string[] = [];
    const block = /^commands:\s*\n((?:[ \t]+.*\n?)*)/m.exec(text)?.[1] ?? '';
    for (const m of block.matchAll(/^ {2}([A-Za-z0-9_-]+):/gm)) commands.push(m[1]!);
    return { name, version, commands: commands.slice(0, 40) };
  }
  return undefined;
}

export function sha256(file: string): string {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}
