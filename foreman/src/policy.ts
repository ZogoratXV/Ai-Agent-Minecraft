import { createHash } from 'node:crypto';
import path, { isAbsolute, relative, resolve, sep } from 'node:path';
import { GIT_REDIRECT_VARS, GIT_SAFETY_VARS } from './gitsafety.js';

/**
 * What an agent may do without asking. Conservative by design: reads inside the worktree, edits
 * inside the worktree (workers only), builds and tests, local git. `git push` and git network or
 * config commands are always denied; anything the policy cannot verify becomes an in-game
 * permission decision whose "Always allow" covers exactly the printed key.
 */

export type Verdict =
  { kind: 'allow' } | { kind: 'deny'; reason: string } | { kind: 'ask'; reason: string; key: string };

export interface PolicyContext {
  /** the agent's working directory: its worktree, or the user's checkout for the lead */
  root: string;
  /** may write files and run builds (developer, qa) */
  writer: boolean;
  /** path fragments never writable, e.g. excluded plugins */
  forbidden: string[];
  /** keys granted with "Always allow for this agent" */
  granted: ReadonlySet<string>;
  /** folders (relative to root) that may be read and copied but never changed: existing version folders */
  frozen?: readonly string[];
}

/**
 * The frozen folder `p` falls in, if any. Frozen folders are written with "/"; the path is
 * compared with "/" too, so it also works with Windows paths ("C:\\...\\modules\\X").
 */
export function frozenMatch(
  root: string,
  frozen: readonly string[],
  p: string,
  impl: Pick<typeof path, 'relative' | 'resolve' | 'sep'> = path,
): string | undefined {
  const rel = impl.relative(root, impl.resolve(root, p)).split(impl.sep).join('/');
  return frozen.find((f) => rel === f || rel.startsWith(`${f}/`));
}

function frozenHit(ctx: PolicyContext, p: string): string | undefined {
  return frozenMatch(ctx.root, ctx.frozen ?? [], p);
}

const FROZEN_MSG = (f: string) =>
  `${f} is an existing version folder: never change it, copy it to a new version folder and work there`;

const ALLOW: Verdict = { kind: 'allow' };
const deny = (reason: string): Verdict => ({ kind: 'deny', reason });
const ask = (reason: string, key: string): Verdict => ({ kind: 'ask', reason, key });

const READ_TOOLS = new Set(['Read', 'Glob', 'Grep', 'LS', 'NotebookRead']);
const WRITE_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);
const FREE_TOOLS = new Set(['TodoWrite', 'TodoRead', 'ToolSearch']);

const READ_CMDS = new Set([
  'ls',
  'cat',
  'head',
  'tail',
  'wc',
  'grep',
  'egrep',
  'rg',
  'find',
  'sort',
  'uniq',
  'diff',
  'tree',
  'pwd',
  'echo',
  'printf',
  'stat',
  'file',
  'du',
  'which',
  'jq',
  'sed',
  'cut',
  'tr',
  'basename',
  'dirname',
  'realpath',
  'true',
  'false',
  'test',
  '[',
  'zipinfo',
  'javap',
  'nl',
  'less',
  'more',
  'xxd',
  'md5sum',
  'sha256sum',
]);
const GIT_READ = new Set([
  'status',
  'diff',
  'log',
  'show',
  'ls-files',
  'grep',
  'blame',
  'rev-parse',
  'describe',
  'shortlog',
  'branch',
]);
const GIT_WRITE = new Set([
  'add',
  'commit',
  'mv',
  'rm',
  'restore',
  'stash',
  'checkout',
  'switch',
  'reset',
  'merge',
  'sparse-checkout',
]);
const GIT_DENY = new Set([
  'push',
  'fetch',
  'pull',
  'clone',
  'remote',
  'config',
  'submodule',
  'send-email',
  'request-pull',
  'daemon',
  'worktree',
  'filter-branch',
  'gc',
  'prune',
  'update-ref',
  'symbolic-ref',
  'hook',
]);
const WRITE_CMDS = new Set(['mkdir', 'cp', 'mv', 'rm', 'touch', 'chmod']);
const BUILD_CMDS = new Set([
  'mvn',
  './mvnw',
  'gradle',
  './gradlew',
  'npm',
  'npx',
  'node',
  'python3',
  'python',
  'javac',
  'java',
]);
const NET_CMDS = new Set(['curl', 'wget', 'ssh', 'scp', 'sftp', 'rsync', 'nc', 'ncat', 'telnet', 'ftp', 'gh']);
const SHELLS = new Set([
  'bash',
  'sh',
  'zsh',
  'dash',
  'eval',
  'exec',
  'sudo',
  'su',
  'env',
  'xargs',
  'nohup',
  'timeout',
  'watch',
]);

function inside(root: string, p: string): boolean {
  const rel = relative(root, resolve(root, p));
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel));
}

function insideGitDir(root: string, p: string): boolean {
  const rel = relative(root, resolve(root, p));
  return rel === '.git' || rel.startsWith(`.git${sep}`);
}

function worst(verdicts: Verdict[]): Verdict {
  return verdicts.find((v) => v.kind === 'deny') ?? verdicts.find((v) => v.kind === 'ask') ?? ALLOW;
}

function grant(v: Verdict, ctx: PolicyContext): Verdict {
  return v.kind === 'ask' && ctx.granted.has(v.key) ? ALLOW : v;
}

export function decide(tool: string, input: Record<string, unknown>, ctx: PolicyContext): Verdict {
  if (FREE_TOOLS.has(tool)) return ALLOW;
  if (tool === 'Task' || tool === 'Agent') return deny('sub-agents are not available in AgentCraft');
  if (READ_TOOLS.has(tool)) {
    const p = String(input.file_path ?? input.path ?? input.notebook_path ?? '.');
    return grant(
      inside(ctx.root, p) ? ALLOW : ask(`${tool} outside the working folder: ${p}`, `Read:${resolve(ctx.root, p)}`),
      ctx,
    );
  }
  if (WRITE_TOOLS.has(tool)) {
    if (!ctx.writer) return deny('this role does not change files');
    const p = String(input.file_path ?? input.notebook_path ?? '');
    if (!p) return deny('missing file path');
    if (insideGitDir(ctx.root, p)) return deny('the .git folder is managed by the foreman');
    const hit = ctx.forbidden.find((f) => resolve(ctx.root, p).includes(f));
    if (hit) return deny(`'${hit}' is excluded: agents must not change it`);
    const frozen = frozenHit(ctx, p);
    if (frozen) return deny(FROZEN_MSG(frozen));
    return inside(ctx.root, p) ? ALLOW : deny(`writing outside the worktree is not allowed: ${p}`);
  }
  if (tool === 'WebFetch' || tool === 'WebSearch') {
    const host = typeof input.url === 'string' ? safeHost(input.url) : 'search';
    return grant(ask(`${tool} (${host})`, `Web:${host}`), ctx);
  }
  if (tool === 'Bash') return grant(bash(String(input.command ?? ''), ctx), ctx);
  return grant(ask(`tool ${tool}`, `Tool:${tool}`), ctx);
}

function safeHost(url: string): string {
  try {
    return new URL(url).host || 'unknown';
  } catch {
    return 'unknown';
  }
}

/** Splits a shell line into words, honouring quotes. Returns undefined for constructs we refuse to parse. */
export function words(segment: string): string[] | undefined {
  const out: string[] = [];
  const re = /"((?:[^"\\]|\\.)*)"|'([^']*)'|(\S+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(segment))) out.push(m[1] ?? m[2] ?? m[3] ?? '');
  return out;
}

function exactKey(command: string): string {
  return `Bash:exact:${createHash('sha256').update(command).digest('hex').slice(0, 16)}`;
}

export function bash(command: string, ctx: PolicyContext): Verdict {
  const c = command.trim();
  if (!c) return deny('empty command');
  for (const v of [...GIT_SAFETY_VARS, ...GIT_REDIRECT_VARS]) {
    if (c.includes(v)) return deny(`commands may not touch ${v}`);
  }
  if (/\bgit\b[^|;&\n]*\bpush\b/.test(c)) return deny('git push is never allowed: pushing is done by people');
  if (/\$\(|`|<\(|>\(/.test(c)) return ask(`command with substitutions: ${c.slice(0, 120)}`, exactKey(c));
  const segments = c
    .split(/&&|\|\||;|\||\n/)
    .map((s) => s.trim())
    .filter(Boolean);
  return worst(segments.map((s) => segment(s, c, ctx)));
}

function segment(seg: string, full: string, ctx: PolicyContext): Verdict {
  // redirections: only into the worktree, or /dev/null
  const redirect = /(?:^|\s)\d?>>?\s*(\S+)/g;
  let r: RegExpExecArray | null;
  const verdicts: Verdict[] = [];
  while ((r = redirect.exec(seg))) {
    const target = r[1] ?? '';
    if (target === '/dev/null' || target.startsWith('&')) continue;
    if (!ctx.writer) verdicts.push(deny('this role does not write files'));
    else if (frozenHit(ctx, target)) verdicts.push(deny(FROZEN_MSG(frozenHit(ctx, target)!)));
    else if (!inside(ctx.root, target)) verdicts.push(ask(`writes outside the worktree: ${target}`, exactKey(full)));
  }
  const cleaned = seg.replace(/(?:^|\s)\d?>>?\s*\S+/g, ' ').replace(/(?:^|\s)\d?<\s*\S+/g, ' ');
  const w = words(cleaned) ?? [];
  if (w.length === 0) return worst(verdicts);
  if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(w[0] ?? ''))
    return ask(`command with environment assignments: ${seg}`, exactKey(full));
  const [cmd = '', ...args] = w;
  const pathsOutside = args.filter(
    (a) =>
      !a.startsWith('-') &&
      (isAbsolute(a) || a.startsWith('~') || a.includes('..')) &&
      !inside(ctx.root, a.replace(/^~/, '/root')),
  );

  if (cmd === 'cd')
    return worst([
      ...verdicts,
      args[0] && !inside(ctx.root, args[0]) ? ask(`cd out of the worktree: ${args[0]}`, exactKey(full)) : ALLOW,
    ]);
  if (SHELLS.has(cmd))
    return worst([...verdicts, ask(`'${cmd}' runs other commands: ${seg.slice(0, 120)}`, exactKey(full))]);
  if (NET_CMDS.has(cmd)) return worst([...verdicts, ask(`network access with ${cmd}`, `Bash:net:${cmd}`)]);

  if (cmd === 'git') {
    const sub = args.find((a) => !a.startsWith('-')) ?? '';
    if (args.some((a) => a === '-c' || a.startsWith('--git-dir') || a.startsWith('--work-tree') || a === '-C')) {
      return deny('git options that change config or location are not allowed');
    }
    if (GIT_DENY.has(sub)) return deny(`git ${sub} is not allowed for agents`);
    if (GIT_READ.has(sub)) {
      if (sub === 'branch' && args.some((a) => /^-[dDmMcC]/.test(a)))
        return worst([...verdicts, ask('git branch change', 'Bash:git branch')]);
      return worst([...verdicts, pathsOutside.length ? ask(`git ${sub} outside the worktree`, exactKey(full)) : ALLOW]);
    }
    if (GIT_WRITE.has(sub)) {
      if (!ctx.writer) return deny(`git ${sub} changes the repository: not for this role`);
      if (sub === 'reset' && args.includes('--hard'))
        return worst([...verdicts, ask('git reset --hard', 'Bash:git reset --hard')]);
      if (sub === 'sparse-checkout' && !['add', 'list'].includes(args[1] ?? ''))
        return deny('only git sparse-checkout add/list');
      if (['mv', 'rm', 'restore', 'checkout'].includes(sub)) {
        const fzg = args
          .filter((a) => !a.startsWith('-') && a !== sub)
          .map((a) => frozenHit(ctx, a))
          .find(Boolean);
        if (fzg) return deny(FROZEN_MSG(fzg));
      }
      return worst([...verdicts, pathsOutside.length ? ask(`git ${sub} outside the worktree`, exactKey(full)) : ALLOW]);
    }
    return worst([...verdicts, ask(`git ${sub}`, `Bash:git ${sub}`)]);
  }

  if (READ_CMDS.has(cmd)) {
    if (
      cmd === 'find' &&
      args.some((a) => ['-exec', '-execdir', '-ok', '-okdir', '-delete', '-fprint', '-fls'].includes(a))
    ) {
      return worst([...verdicts, ask('find that runs or deletes', exactKey(full))]);
    }
    if (cmd === 'sed' && args.some((a) => a.startsWith('-i') || a === '--in-place')) {
      if (!ctx.writer) return deny('this role does not change files');
      const fz = args
        .filter((a) => !a.startsWith('-'))
        .slice(1)
        .map((a) => frozenHit(ctx, a))
        .find(Boolean);
      if (fz) return deny(FROZEN_MSG(fz));
      return worst([...verdicts, pathsOutside.length ? ask('sed -i outside the worktree', exactKey(full)) : ALLOW]);
    }
    return worst([
      ...verdicts,
      pathsOutside.length ? ask(`${cmd} outside the worktree: ${pathsOutside[0]}`, `Bash:outside:${cmd}`) : ALLOW,
    ]);
  }

  if (!ctx.writer) return worst([...verdicts, ask(`'${cmd}' in your checkout (the lead only reads)`, exactKey(full))]);

  if (WRITE_CMDS.has(cmd)) {
    if (pathsOutside.length) return deny(`${cmd} outside the worktree is not allowed: ${pathsOutside[0]}`);
    const hit = ctx.forbidden.find((f) => args.some((a) => a.includes(f)));
    if (hit && cmd !== 'cp') return deny(`'${hit}' is excluded: agents must not change it`);
    const operands = args.filter((a) => !a.startsWith('-'));
    const targets = cmd === 'cp' ? operands.slice(-1) : operands;
    const fz = targets.map((a) => frozenHit(ctx, a)).find(Boolean);
    if (fz) return deny(FROZEN_MSG(fz));
    if (cmd === 'rm' && args.some((a) => /^-[a-zA-Z]*[rR]/.test(a)))
      return worst([...verdicts, ask('recursive delete', 'Bash:rm -r')]);
    if (args.some((a) => a === '.git' || a.startsWith('.git/')))
      return deny('the .git folder is managed by the foreman');
    return worst(verdicts);
  }

  if (BUILD_CMDS.has(cmd)) {
    if (pathsOutside.length) return worst([...verdicts, ask(`${cmd} with paths outside the worktree`, exactKey(full))]);
    if ((cmd === 'node' || cmd.startsWith('python')) && args.some((a) => a === '-e' || a === '-c' || a === '--eval')) {
      return worst([...verdicts, ask(`inline ${cmd} code`, exactKey(full))]);
    }
    if (cmd === 'npm' && ['install', 'i', 'add', 'publish', 'login', 'adduser', 'exec'].includes(args[0] ?? '')) {
      return worst([...verdicts, ask(`npm ${args[0]}`, `Bash:npm ${args[0]}`)]);
    }
    if (cmd === 'npx') return worst([...verdicts, ask('npx runs downloaded tools', `Bash:npx ${args[0] ?? ''}`)]);
    if (cmd.startsWith('mvn') || cmd.endsWith('mvnw')) {
      if (args.some((a) => /deploy|release:|site-deploy|install/.test(a)))
        return worst([...verdicts, ask(`mvn ${args.join(' ')}`, 'Bash:mvn deploy/install')]);
    }
    if (cmd === 'java' && !args.some((a) => a === '-version' || a === '--version'))
      return worst([...verdicts, ask('running java programs', 'Bash:java')]);
    return worst(verdicts);
  }

  return worst([...verdicts, ask(`unknown command '${cmd}'`, `Bash:${cmd}`)]);
}
