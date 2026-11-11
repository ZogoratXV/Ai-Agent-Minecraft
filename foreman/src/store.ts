import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

/**
 * State on disk: JSON files written atomically (temporary file + rename, so a crash never leaves
 * a half-written file) and append-only JSONL logs.
 */
export class Store {
  private timer: NodeJS.Timeout | undefined;
  private pending: (() => unknown) | undefined;

  constructor(readonly dir: string) {
    mkdirSync(dir, { recursive: true });
  }

  path(name: string): string {
    return join(this.dir, name);
  }

  readJson<T>(name: string): T | undefined {
    const p = this.path(name);
    if (!existsSync(p)) return undefined;
    return JSON.parse(readFileSync(p, 'utf8')) as T;
  }

  writeJson(name: string, data: unknown): void {
    const p = this.path(name);
    mkdirSync(dirname(p), { recursive: true });
    const tmp = `${p}.${process.pid}.tmp`;
    writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n');
    renameSync(tmp, p);
  }

  appendJsonl(name: string, record: unknown): void {
    const p = this.path(name);
    mkdirSync(dirname(p), { recursive: true });
    appendFileSync(p, JSON.stringify(record) + '\n');
  }

  /** Writes `name` at most every `ms`, with the latest data. */
  scheduleWrite(name: string, data: () => unknown, ms = 100): void {
    this.pending = () => this.writeJson(name, data());
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      const p = this.pending;
      this.pending = undefined;
      p?.();
    }, ms);
  }

  flush(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    const p = this.pending;
    this.pending = undefined;
    p?.();
  }
}
