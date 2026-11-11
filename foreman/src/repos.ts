import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import type { Repo } from '@agentcraft-server/protocol';
import type { z } from 'zod';
import { git, run } from './util/exec.js';

export type RepoT = z.infer<typeof Repo>;

/** Per-repository settings (foreman config.json `repos`, or detected on `/agent repo add`). */
export interface RepoSettings {
  id?: string;
  path: string;
  branch?: string;
  versioning?: 'in-place' | 'new-folder';
  /** check out only the task's folders (automatic for big repositories) */
  sparse?: boolean;
  /** extra folders always checked out in sparse worktrees */
  sparseAlways?: string[];
  /** test command run in the worktree root; `{module}` = the task's folder. Default: detected */
  ci?: string;
  /** path fragments agents may never write to (e.g. excluded plugins) */
  forbidden?: string[];
  /** files the agents read first (e.g. AGENTS.md) */
  instructions?: string[];
  /** `/agent push` (people only): where and how the base branch is published */
  push?: PushSettings;
}

export interface PushSettings {
  /** git remote name (default "origin") */
  remote?: string;
  /** branch on the remote (default: the base branch). With PR workflows use e.g. "agentcraft/lavori" */
  branch?: string;
  /** private SSH deploy key (file readable only by the server user) */
  sshKeyFile?: string;
  /** HTTPS token in a file (e.g. a fine-grained GitHub token with contents: write) */
  tokenFile?: string;
}

export interface PushResult {
  remote: string;
  branch: string;
  sha: string;
}

export interface RepoRecord extends RepoT {
  settings: Required<Pick<RepoSettings, 'sparse' | 'sparseAlways' | 'forbidden' | 'instructions'>> &
    Pick<RepoSettings, 'ci' | 'push'>;
}

export interface Worktree {
  path: string;
  branch: string;
}

export interface DiffSummary {
  files: number;
  additions: number;
  deletions: number;
  stat: string;
  patch: string;
  changed: string[];
}

export type MergeResult = { ok: true; sha: string } | { ok: false; conflict: boolean; reason: string };

const BIG_REPO_FILES = 5000;

export function slug(text: string): string {
  return (
    text
      .toLowerCase()
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
      .slice(0, 30) || 'task'
  );
}

/** Detects the "one folder per version" layout: modules/<Name>-<x.y...> twice or more. */
export function detectVersioning(repoPath: string): 'in-place' | 'new-folder' {
  const modules = join(repoPath, 'modules');
  if (!existsSync(modules)) return 'in-place';
  const versioned = readdirSync(modules).filter((d) => /-\d+\.\d+(\.\d+)?([-.].+)?$/.test(d));
  return versioned.length >= 2 ? 'new-folder' : 'in-place';
}

export class RepoManager {
  readonly repos = new Map<string, RepoRecord>();

  constructor(
    private readonly worktreeRoot: string,
    private readonly onChange: (repo: RepoT) => void = () => {},
  ) {}

  async add(settings: RepoSettings): Promise<RepoRecord> {
    const path = resolve(settings.path);
    const top = (await git(path, ['rev-parse', '--show-toplevel'])).trim();
    const id = settings.id ?? slug(basename(top));
    const branch = settings.branch ?? (await git(top, ['rev-parse', '--abbrev-ref', 'HEAD'])).trim();
    const count = (await git(top, ['ls-files'], 300_000)).split('\n').filter(Boolean).length;
    const instructions =
      settings.instructions ?? ['AGENTS.md', 'CLAUDE.md', 'CONTRIBUTING.md'].filter((f) => existsSync(join(top, f)));
    const record: RepoRecord = {
      id,
      name: basename(top),
      path: top,
      branch,
      dirty: false,
      versioning: settings.versioning ?? detectVersioning(top),
      settings: {
        sparse: settings.sparse ?? count > BIG_REPO_FILES,
        sparseAlways: settings.sparseAlways ?? ['scripts'],
        forbidden: settings.forbidden ?? [],
        instructions,
        ...(settings.ci ? { ci: settings.ci } : {}),
        ...(settings.push ? { push: settings.push } : {}),
      },
    };
    this.repos.set(id, record);
    await this.refresh(id);
    return this.repos.get(id)!;
  }

  get(id: string): RepoRecord {
    const r = this.repos.get(id);
    if (!r) throw new Error(`unknown repository '${id}'`);
    return r;
  }

  /** The public view (no settings) for the protocol. */
  static view(r: RepoRecord): RepoT {
    return {
      id: r.id,
      name: r.name,
      path: r.path,
      branch: r.branch,
      ...(r.head ? { head: r.head } : {}),
      dirty: r.dirty,
      versioning: r.versioning,
    };
  }

  async refresh(id: string): Promise<RepoRecord> {
    const r = this.get(id);
    const head = (await git(r.path, ['rev-parse', '--short', r.branch])).trim();
    const dirty = (await git(r.path, ['status', '--porcelain', '--untracked-files=no'])).trim().length > 0;
    const next = { ...r, head, dirty };
    this.repos.set(id, next);
    this.onChange(RepoManager.view(next));
    return next;
  }

  worktreePath(repoId: string, agentId: string, taskId: string): string {
    return join(this.worktreeRoot, repoId, `${agentId}-${taskId}`);
  }

  /**
   * Worktree on `agentcraft/<agent>/<task>-<slug>` from the base branch. In sparse repositories only
   * the root files, `paths` and the repo's `sparseAlways` folders are checked out.
   */
  async createWorktree(
    repoId: string,
    agentId: string,
    taskId: string,
    title: string,
    paths: string[],
  ): Promise<Worktree> {
    const r = this.get(repoId);
    const path = this.worktreePath(repoId, agentId, taskId);
    const branch = `agentcraft/${agentId}/${taskId}-${slug(title)}`;
    if (existsSync(path)) return { path, branch };
    mkdirSync(join(this.worktreeRoot, repoId), { recursive: true });
    const exists = (await run('git', ['rev-parse', '--verify', '--quiet', branch], { cwd: r.path })).code === 0;
    const add = exists
      ? ['worktree', 'add', '--no-checkout', path, branch]
      : ['worktree', 'add', '--no-checkout', '-b', branch, path, r.branch];
    await git(r.path, add, 600_000);
    if (r.settings.sparse) {
      const dirs = [...new Set([...paths, ...r.settings.sparseAlways])].filter((p) => p && !p.includes('..'));
      await git(path, ['sparse-checkout', 'set', '--cone', ...dirs]);
    }
    await git(path, ['checkout', branch], 600_000);
    return { path, branch };
  }

  /** Version folders that exist on the base branch (new-folder repositories): they are never changed. */
  async frozenFolders(repoId: string): Promise<string[]> {
    const r = this.get(repoId);
    if (r.versioning !== 'new-folder') return [];
    const out = await run('git', ['ls-tree', '-d', '--name-only', `${r.branch}:modules`], { cwd: r.path });
    if (out.code !== 0) return [];
    return out.stdout
      .split('\n')
      .filter((d) => /-\d+\.\d+/.test(d))
      .map((d) => `modules/${d}`);
  }

  async removeWorktree(repoId: string, path: string): Promise<void> {
    const r = this.get(repoId);
    await run('git', ['worktree', 'remove', '--force', path], { cwd: r.path, timeoutMs: 120_000 });
    if (existsSync(path)) rmSync(path, { recursive: true, force: true });
    await run('git', ['worktree', 'prune'], { cwd: r.path });
  }

  /** Commits everything in the worktree as the agent. Returns false when there was nothing to commit. */
  async commitAll(wt: Worktree, identity: { name: string; email: string }, message: string): Promise<boolean> {
    await git(wt.path, ['add', '-A', '--sparse']);
    const staged = (await git(wt.path, ['diff', '--cached', '--name-only'])).trim();
    if (!staged) return false;
    await git(wt.path, [
      '-c',
      `user.name=${identity.name}`,
      '-c',
      `user.email=${identity.email}`,
      '-c',
      'commit.gpgsign=false',
      'commit',
      '--no-verify',
      '-m',
      message,
    ]);
    return true;
  }

  async diff(repoId: string, branch: string): Promise<DiffSummary> {
    const r = this.get(repoId);
    const range = `${r.branch}...${branch}`;
    const numstat = await git(r.path, ['diff', '--numstat', range]);
    let additions = 0;
    let deletions = 0;
    const changed: string[] = [];
    for (const line of numstat.split('\n').filter(Boolean)) {
      const [a, d, file] = line.split('\t');
      additions += Number(a) || 0;
      deletions += Number(d) || 0;
      if (file) changed.push(file);
    }
    const stat = (await git(r.path, ['diff', '--stat', range])).trim();
    const patch = await git(r.path, ['diff', range]);
    return { files: changed.length, additions, deletions, stat, patch, changed };
  }

  /**
   * Publishes the base branch (`/agent push`, asked by a person). Never forced. Credentials come
   * from files and reach git through environment variables only (never the command line).
   */
  async push(repoId: string): Promise<PushResult> {
    const r = await this.refresh(repoId);
    const p = r.settings.push ?? {};
    const remote = p.remote ?? 'origin';
    const branch = p.branch ?? r.branch;
    const url = (await run('git', ['remote', 'get-url', remote], { cwd: r.path })).stdout.trim();
    if (!url) throw new Error(`the repository has no remote '${remote}'`);
    const env: NodeJS.ProcessEnv = { ...process.env, GIT_TERMINAL_PROMPT: '0' };
    if (p.tokenFile) {
      const token = readFileSync(p.tokenFile, 'utf8').trim();
      if (!token) throw new Error('the push token file is empty');
      const basic = Buffer.from(`x-access-token:${token}`).toString('base64');
      env.GIT_CONFIG_COUNT = '2';
      env.GIT_CONFIG_KEY_0 = 'credential.helper';
      env.GIT_CONFIG_VALUE_0 = '';
      env.GIT_CONFIG_KEY_1 = 'http.extraHeader';
      env.GIT_CONFIG_VALUE_1 = `Authorization: Basic ${basic}`;
    }
    if (p.sshKeyFile) {
      env.GIT_SSH_COMMAND = `ssh -i '${p.sshKeyFile.replace(/'/g, '')}' -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new`;
    }
    const res = await run('git', ['push', '--porcelain', remote, `refs/heads/${r.branch}:refs/heads/${branch}`], {
      cwd: r.path,
      env,
      timeoutMs: 180_000,
    });
    if (res.code !== 0) {
      // never echo credentials: git prints the URL, not the header
      throw new Error(
        `push failed: ${(res.stderr || res.stdout).trim().split('\n').slice(-3).join(' ').slice(0, 400)}`,
      );
    }
    const sha = (await git(r.path, ['rev-parse', '--short', r.branch])).trim();
    return { remote, branch, sha };
  }

  /**
   * Merges the task branch into the base branch of the user's checkout, as the user. Refused when
   * the checkout is dirty or not on the base branch; a conflict is aborted and reported.
   */
  async merge(repoId: string, branch: string, message: string, approvedBy: string): Promise<MergeResult> {
    const r = await this.refresh(repoId);
    const current = (await git(r.path, ['rev-parse', '--abbrev-ref', 'HEAD'])).trim();
    if (current !== r.branch)
      return { ok: false, conflict: false, reason: `the checkout is on '${current}', not '${r.branch}'` };
    if (r.dirty) return { ok: false, conflict: false, reason: 'the checkout has uncommitted changes' };
    const hasName = (await run('git', ['config', 'user.name'], { cwd: r.path })).stdout.trim() !== '';
    const identity = hasName
      ? []
      : ['-c', `user.name=AgentCraft (approvato da ${approvedBy})`, '-c', 'user.email=agentcraft@localhost'];
    const res = await run('git', [...identity, 'merge', '--no-ff', '--no-edit', '-m', message, branch], {
      cwd: r.path,
      timeoutMs: 300_000,
    });
    if (res.code !== 0) {
      await run('git', ['merge', '--abort'], { cwd: r.path });
      const conflict = /CONFLICT|conflict/.test(res.stdout + res.stderr);
      return { ok: false, conflict, reason: (res.stdout + res.stderr).trim().slice(-800) };
    }
    const sha = (await git(r.path, ['rev-parse', '--short', 'HEAD'])).trim();
    await this.refresh(repoId);
    return { ok: true, sha };
  }
}
