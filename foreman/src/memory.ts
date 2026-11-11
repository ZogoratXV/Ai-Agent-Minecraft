import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { slug } from './repos.js';

/**
 * Markdown notes, editable by hand: memory/shared/<slug>.md for the whole team and
 * memory/agents/<id>/<slug>.md for one agent. The lead writes the plan here.
 */
export class Memory {
  constructor(private readonly root: string) {}

  private dir(scope: string): string {
    const d = scope === 'shared' ? join(this.root, 'shared') : join(this.root, 'agents', slug(scope));
    mkdirSync(d, { recursive: true });
    return d;
  }

  write(scope: string, title: string, body: string): string {
    const name = slug(title);
    writeFileSync(join(this.dir(scope), `${name}.md`), `# ${title}\n\n${body.trim()}\n`);
    return `${scope}/${name}`;
  }

  read(scope: string, name: string): string | undefined {
    const p = join(this.dir(scope), `${slug(name)}.md`);
    return existsSync(p) ? readFileSync(p, 'utf8') : undefined;
  }

  list(scope: string): string[] {
    return readdirSync(this.dir(scope))
      .filter((f) => f.endsWith('.md'))
      .map((f) => f.slice(0, -3));
  }
}
