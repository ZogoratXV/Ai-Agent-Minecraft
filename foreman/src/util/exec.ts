import { spawn } from 'node:child_process';

export interface RunResult {
  code: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export interface RunOptions {
  cwd: string;
  env?: NodeJS.ProcessEnv;
  timeoutMs?: number;
  /** keep only the last N characters of each stream */
  maxOutput?: number;
}

/**
 * Runs a program without a shell (arguments are never re-parsed). On timeout the whole process
 * group is killed, so build tools cannot leave children behind.
 */
export function run(cmd: string, args: string[], opts: RunOptions): Promise<RunResult> {
  const max = opts.maxOutput ?? 200_000;
  return new Promise((resolve) => {
    const child = spawn(cmd, args, {
      cwd: opts.cwd,
      env: opts.env ?? process.env,
      detached: process.platform !== 'win32',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    const keep = (s: string, d: Buffer) => (s + d.toString()).slice(-max);
    child.stdout.on('data', (d: Buffer) => (stdout = keep(stdout, d)));
    child.stderr.on('data', (d: Buffer) => (stderr = keep(stderr, d)));
    const timer = opts.timeoutMs
      ? setTimeout(() => {
          timedOut = true;
          try {
            if (child.pid && process.platform !== 'win32') process.kill(-child.pid, 'SIGKILL');
            else child.kill('SIGKILL');
          } catch {
            // already gone
          }
        }, opts.timeoutMs)
      : undefined;
    child.on('error', (e) => {
      if (timer) clearTimeout(timer);
      resolve({ code: 127, stdout, stderr: stderr + e.message, timedOut });
    });
    child.on('close', (code) => {
      if (timer) clearTimeout(timer);
      resolve({ code: code ?? 1, stdout, stderr, timedOut });
    });
  });
}

/** git with the foreman's own environment (not the agents' restricted one). Throws on failure. */
export async function git(cwd: string, args: string[], timeoutMs = 120_000): Promise<string> {
  const r = await run('git', args, { cwd, timeoutMs });
  if (r.code !== 0) throw new Error(`git ${args.join(' ')}: ${(r.stderr || r.stdout).trim().slice(-500)}`);
  return r.stdout;
}
