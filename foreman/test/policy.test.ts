import { describe, expect, it } from 'vitest';
import { win32 } from 'node:path';
import { decide, frozenMatch, type PolicyContext } from '../src/policy.js';

const wt = '/srv/wt/castigo/lucciola-t2';
const worker: PolicyContext = {
  root: wt,
  writer: true,
  forbidden: ['CastigoClassi', 'CastigoMagia', 'CastigoCombat'],
  granted: new Set(),
};
const lead: PolicyContext = { ...worker, root: '/srv/repos/castigo', writer: false };
const kind = (tool: string, input: Record<string, unknown>, ctx = worker) => decide(tool, input, ctx).kind;
const sh = (command: string, ctx = worker) => kind('Bash', { command }, ctx);

describe('policy: files', () => {
  it('reads inside the worktree, asks outside', () => {
    expect(kind('Read', { file_path: `${wt}/modules/x/pom.xml` })).toBe('allow');
    expect(kind('Grep', { pattern: 'x', path: 'modules' })).toBe('allow');
    expect(kind('Read', { file_path: '/etc/passwd' })).toBe('ask');
  });

  it('writes only inside the worktree, never .git or excluded plugins, never for the lead', () => {
    expect(kind('Edit', { file_path: `${wt}/modules/CastigoBanca-2.7.0-alpha.2/src/A.java` })).toBe('allow');
    expect(kind('Write', { file_path: '/srv/repos/castigo/README.md' })).toBe('deny');
    expect(kind('Write', { file_path: `${wt}/.git/config` })).toBe('deny');
    expect(kind('Edit', { file_path: `${wt}/modules/CastigoMagia-1.0/src/A.java` })).toBe('deny');
    expect(kind('Edit', { file_path: `${lead.root}/a.txt` }, lead)).toBe('deny');
  });

  it('denies sub-agents and asks for the web', () => {
    expect(kind('Task', {})).toBe('deny');
    expect(kind('WebFetch', { url: 'https://example.com/x' })).toBe('ask');
  });
});

describe('policy: bash', () => {
  it('never allows git push, however it is written', () => {
    for (const c of [
      'git push',
      'git push origin main',
      'ls && git push',
      'git -C . push',
      'echo x | git push --force',
    ]) {
      expect(sh(c), c).toBe('deny');
    }
  });

  it('denies git network and config commands and safety variables', () => {
    for (const c of [
      'git fetch',
      'git pull',
      'git clone https://x',
      'git remote add o x',
      'git config user.name x',
      'git -c core.hooksPath=x commit',
      'GIT_ALLOW_PROTOCOL=all git status',
      'unset GIT_CONFIG_COUNT',
    ]) {
      expect(sh(c), c).toBe('deny');
    }
  });

  it('allows reading, local git, builds and edits inside the worktree', () => {
    for (const c of [
      'ls -la modules',
      'grep -rn "shop" modules/CastigoBanca-2.7.0-alpha.1/src | head -20',
      'git status && git diff',
      'git add -A && git commit -m "fix"',
      'cp -r modules/CastigoBanca-2.7.0-alpha.1 modules/CastigoBanca-2.7.0-alpha.2',
      'cd modules/CastigoBanca-2.7.0-alpha.2 && mvn -B -q test',
      'python3 scripts/plugin_dependencies.py',
      "sed -i 's/alpha.1/alpha.2/' modules/CastigoBanca-2.7.0-alpha.2/pom.xml",
      'mvn -q test > build.log 2>&1',
    ]) {
      expect(sh(c), c).toBe('allow');
    }
  });

  it('asks for network, substitutions, shells, outside paths and recursive deletes', () => {
    for (const c of [
      'curl https://example.com',
      'echo $(cat /etc/shadow)',
      'bash -c "ls"',
      'cat /etc/hosts',
      'rm -rf build',
      'find . -name x -exec rm {} ;',
      'npm install left-pad',
      'cd /tmp',
      'foo --bar',
      'node -e "1"',
    ]) {
      expect(sh(c), c).toBe('ask');
    }
  });

  it('refuses writes outside the worktree and to excluded plugins', () => {
    expect(sh('rm /srv/repos/castigo/README.md')).toBe('deny');
    expect(sh('mkdir modules/CastigoCombat-2.0')).toBe('deny');
    expect(sh('echo x > /etc/motd')).toBe('ask');
  });

  it('lets the lead only read in the user checkout', () => {
    expect(sh('git log --oneline -5', lead)).toBe('allow');
    expect(sh('cat AGENTS.md', lead)).toBe('allow');
    expect(sh('git commit -m x', lead)).toBe('deny');
    expect(sh('mvn test', lead)).toBe('ask');
    expect(sh('echo x > a.txt', lead)).toBe('deny');
  });

  it('"Always allow" covers only the granted key', () => {
    const granted: PolicyContext = { ...worker, granted: new Set(['Bash:net:curl']) };
    expect(sh('curl https://example.com', granted)).toBe('allow');
    expect(sh('wget https://example.com', granted)).toBe('ask');
    expect(sh('git push', { ...worker, granted: new Set(['Bash:git push', 'Bash:git']) })).toBe('deny');
  });
});

describe('policy: existing version folders are frozen', () => {
  const ctx: PolicyContext = { ...worker, frozen: ['modules/CastigoBanca-2.7.0-alpha.1'] };
  it('allows reading and copying them, never changing them', () => {
    expect(kind('Read', { file_path: `${wt}/modules/CastigoBanca-2.7.0-alpha.1/pom.xml` }, ctx)).toBe('allow');
    expect(sh('cp -r modules/CastigoBanca-2.7.0-alpha.1 modules/CastigoBanca-2.7.0-alpha.2', ctx)).toBe('allow');
    expect(kind('Edit', { file_path: `${wt}/modules/CastigoBanca-2.7.0-alpha.1/src/A.java` }, ctx)).toBe('deny');
    expect(kind('Edit', { file_path: `${wt}/modules/CastigoBanca-2.7.0-alpha.10/src/A.java` }, ctx)).toBe('allow');
    expect(sh("sed -i 's/a/b/' modules/CastigoBanca-2.7.0-alpha.1/pom.xml", ctx)).toBe('deny');
    expect(sh('rm modules/CastigoBanca-2.7.0-alpha.1/pom.xml', ctx)).toBe('deny');
    expect(sh('mv modules/CastigoBanca-2.7.0-alpha.1 modules/x', ctx)).toBe('deny');
    expect(sh('git rm -r modules/CastigoBanca-2.7.0-alpha.1', ctx)).toBe('deny');
    expect(sh('echo x > modules/CastigoBanca-2.7.0-alpha.1/a.txt', ctx)).toBe('deny');
  });
});

describe('policy: Windows paths', () => {
  it('matches frozen version folders with backslashes and drive letters', () => {
    const root = 'C:\\Users\\x\\wt\\lucciola-t1';
    const frozen = ['modules/CastigoBanca-2.7.0-alpha.1'];
    expect(
      frozenMatch(root, frozen, 'C:\\Users\\x\\wt\\lucciola-t1\\modules\\CastigoBanca-2.7.0-alpha.1\\pom.xml', win32),
    ).toBe(frozen[0]);
    expect(
      frozenMatch(root, frozen, 'C:/Users/x/wt/lucciola-t1/modules/CastigoBanca-2.7.0-alpha.1/src/A.java', win32),
    ).toBe(frozen[0]);
    expect(frozenMatch(root, frozen, 'modules\\CastigoBanca-2.7.0-alpha.1\\a.txt', win32)).toBe(frozen[0]);
    expect(
      frozenMatch(root, frozen, 'C:\\Users\\x\\wt\\lucciola-t1\\modules\\CastigoBanca-2.7.0-alpha.2\\pom.xml', win32),
    ).toBeUndefined();
  });
});
