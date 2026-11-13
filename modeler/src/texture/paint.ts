import { makeRamp, parseHex, shadeColor, type RGBA } from "../core/color";
import type { Img } from "../core/image";
import { rng } from "../core/math";
import type { Rect, UvLayout } from "../bbmodel/build";
import type { FaceDir } from "../bbmodel/model";
import type { PaintOp, PaintProgram } from "../bbmodel/spec";

const SIDES: FaceDir[] = ["north", "east", "south", "west"];
const ALL: FaceDir[] = [...SIDES, "up", "down"];

/** Palette risolta: nomi + rampe espanse (wood0..woodN). */
export function resolvePalette(p: PaintProgram): Record<string, string> {
  const out: Record<string, string> = { ...p.palette };
  for (const [name, r] of Object.entries(p.ramps)) makeRamp(r.base, r).forEach((c, i) => (out[`${name}${i}`] = c));
  return out;
}

interface Region extends Rect {
  /** la faccia è specchiata (uvFrom + mirror): le coordinate locali vanno invertite in x */
  mirrored?: boolean;
  label: string;
}

function matchCube(name: string, sel: string | string[] | undefined): boolean {
  if (sel === undefined) return true;
  const list = Array.isArray(sel) ? sel : [sel];
  return list.some((s) => s === "*" || s === name || (s.endsWith("*") && name.startsWith(s.slice(0, -1))));
}

function regions(op: PaintOp, layout: UvLayout): Region[] {
  const t = op.target;
  if (t.rect) return [{ x: t.rect[0], y: t.rect[1], w: t.rect[2], h: t.rect[3], label: "rect" }];
  const faces: FaceDir[] = t.faces === undefined || t.faces === "all" ? ALL : t.faces === "sides" ? SIDES : t.faces;
  const seen = new Set<string>();
  const out: Region[] = [];
  for (const [cube, fs] of Object.entries(layout)) {
    if (!matchCube(cube, t.cubes)) continue;
    for (const d of faces) {
      const r = fs[d];
      if (!r) continue;
      const key = `${r.x},${r.y},${r.w},${r.h}`; // facce condivise (uvFrom) dipinte una volta sola
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ ...r, label: `${cube}.${d}` });
    }
  }
  return out;
}

// matrice di Bayer 4x4 per dithering ordinato
const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map((v) => (v + 0.5) / 16);

export interface PaintReport {
  painted: number;
  warnings: string[];
}

export function paint(img: Img, layout: UvLayout, program: PaintProgram): PaintReport {
  const pal = resolvePalette(program);
  const warnings: string[] = [];
  const col = (c: string): RGBA => {
    const k = c.replace(/^\$/, "");
    if (pal[k]) return parseHex(pal[k]);
    if (c.startsWith("#")) return parseHex(c);
    throw new Error(`Colore '${c}' non definito nella palette (nomi disponibili: ${Object.keys(pal).join(", ") || "nessuno"})`);
  };
  let painted = 0;

  for (const [i, op] of program.ops.entries()) {
    const rs = regions(op, layout);
    if (!rs.length) {
      warnings.push(`op #${i} (${op.op}): il target non corrisponde a nessuna faccia`);
      continue;
    }
    for (const r of rs) {
      /** set in coordinate locali della faccia (gestisce il mirror) */
      const put = (lx: number, ly: number, c: RGBA, blend = false) => {
        if (lx < 0 || ly < 0 || lx >= r.w || ly >= r.h) return;
        const x = r.x + (r.mirrored ? r.w - 1 - lx : lx), y = r.y + ly;
        blend ? img.blend(x, y, c) : img.set(x, y, c);
        painted++;
      };
      const getL = (lx: number, ly: number) => img.get(r.x + (r.mirrored ? r.w - 1 - lx : lx), r.y + ly);

      switch (op.op) {
        case "fill": {
          const c = col(op.color);
          for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) put(x, y, c);
          break;
        }
        case "gradient": {
          const cs = op.colors.map(col);
          const len = op.direction === "vertical" ? r.h : r.w;
          for (let y = 0; y < r.h; y++)
            for (let x = 0; x < r.w; x++) {
              const p = len <= 1 ? 0 : (op.direction === "vertical" ? y : x) / (len - 1);
              const f = p * (cs.length - 1);
              let idx = Math.floor(f);
              const frac = f - idx;
              if (op.dither) idx += frac > BAYER[(y % 4) * 4 + (x % 4)] ? 1 : 0;
              else idx = Math.round(f);
              put(x, y, cs[Math.min(cs.length - 1, idx)]);
            }
          break;
        }
        case "noise": {
          const cs = op.colors.map(col);
          const rand = rng(op.seed + r.x * 7919 + r.y * 104729);
          for (let y = 0; y < r.h; y++)
            for (let x = 0; x < r.w; x++) if (rand() < op.density) put(x, y, cs[Math.floor(rand() * cs.length)]);
          break;
        }
        case "shade": {
          for (let y = 0; y < r.h; y++)
            for (let x = 0; x < r.w; x++) {
              const c = getL(x, y);
              if (c[3] === 0) continue;
              const py = r.h <= 1 ? 0.5 : y / (r.h - 1);
              const px = r.w <= 1 ? 0.5 : x / (r.w - 1);
              let t = 0.5 - py; // alto = luce
              if (op.light === "top-left") t = (t + (0.5 - px)) / 2;
              if (op.light === "top-right") t = (t + (px - 0.5)) / 2;
              // quantizza a 3 livelli per evitare banding da gradiente continuo
              const lvl = Math.round(t * 2) / 2;
              if (lvl !== 0) put(x, y, shadeColor(c, lvl * op.strength * 2));
            }
          break;
        }
        case "border": {
          const c = col(op.color);
          const sides = op.sides ?? ["top", "bottom", "left", "right"];
          for (let y = 0; y < r.h; y++)
            for (let x = 0; x < r.w; x++) {
              const on =
                (sides.includes("top") && y < op.width) ||
                (sides.includes("bottom") && y >= r.h - op.width) ||
                (sides.includes("left") && x < op.width) ||
                (sides.includes("right") && x >= r.w - op.width);
              if (on) put(x, y, c);
            }
          break;
        }
        case "rect": {
          const c = col(op.color);
          for (let y = op.y; y < op.y + op.h; y++) for (let x = op.x; x < op.x + op.w; x++) put(x, y, c);
          break;
        }
        case "line": {
          const c = col(op.color);
          let [x0, y0] = op.from;
          const [x1, y1] = op.to;
          const dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
          let err = dx + dy;
          for (;;) {
            put(x0, y0, c);
            if (x0 === x1 && y0 === y1) break;
            const e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
          }
          break;
        }
        case "pixels": {
          const c = col(op.color);
          for (const [x, y] of op.points) put(x, y, c);
          break;
        }
        case "pattern": {
          const key = Object.fromEntries(Object.entries(op.key).map(([k, v]) => [k, col(v)]));
          const ph = op.rows.length, pw = Math.max(...op.rows.map((s) => [...s].length));
          let ox = op.x, oy = op.y;
          if (op.anchor === "center") { ox += Math.floor((r.w - pw) / 2); oy += Math.floor((r.h - ph) / 2); }
          if (op.anchor === "bottom-left" || op.anchor === "bottom-right") oy = r.h - ph - op.y;
          if (op.anchor === "top-right" || op.anchor === "bottom-right") ox = r.w - pw - op.x;
          const stamp = (bx: number, by: number) =>
            op.rows.forEach((row, ry) =>
              [...row].forEach((ch, rx) => {
                if (ch === "." || ch === " ") return;
                const c = key[ch];
                if (!c) throw new Error(`pattern: carattere '${ch}' non presente in key`);
                put(bx + rx, by + ry, c, c[3] < 255);
              }),
            );
          if (op.tile) for (let by = 0; by < r.h; by += ph) for (let bx = 0; bx < r.w; bx += pw) stamp(bx, by);
          else stamp(ox, oy);
          break;
        }
        case "flow": {
          const cs = op.colors.map(col);
          const vert = op.direction === "vertical";
          const along = vert ? r.h : r.w, across = vert ? r.w : r.h;
          const ph = (op.seed * 2.399963) % (Math.PI * 2);
          const lo = op.margin, hi = across - op.margin - 1;
          if (hi < lo) break;
          // ogni linea k ha un'ondulazione propria (fase sfasata): le linee si avvicinano e si allontanano
          // come nell'acciaio damascato, senza muoversi tutte insieme. Linee sempre continue, larghe 1 px.
          const pos = (k: number, a: number) =>
            k * op.spacing +
            op.amplitude * Math.sin((2 * Math.PI * a) / op.wavelength + ph + k * 1.13) +
            op.amplitude * 0.45 * Math.sin((2 * Math.PI * a) / (op.wavelength * 2.37) + ph * 1.7 + k * 0.61);
          const kMin = Math.floor((lo - 2 * op.amplitude) / op.spacing) - 1, kMax = Math.ceil((hi + 2 * op.amplitude) / op.spacing) + 1;
          const set = (c: number, a: number, color: RGBA) => {
            if (c < lo || c > hi) return;
            vert ? put(c, a, color) : put(a, c, color);
          };
          for (let k = kMin; k <= kMax; k++) {
            const color = cs[((k % cs.length) + cs.length) % cs.length];
            let prev = Math.round(pos(k, 0));
            for (let a = 0; a < along; a++) {
              const c = Math.round(pos(k, a + 0.5));
              // riempi i salti per tenere la linea continua
              const from = Math.min(prev, c), to = Math.max(prev, c);
              if (a === 0) set(c, a, color);
              else for (let x = from; x <= to; x++) set(x, x === prev ? a - 1 : a, color);
              set(c, a, color);
              prev = c;
            }
          }
          break;
        }
        case "erase": {
          const x0 = op.x ?? 0, y0 = op.y ?? 0, w = op.w ?? r.w, h = op.h ?? r.h;
          for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) put(x, y, [0, 0, 0, 0]);
          break;
        }
      }
    }
  }

  // facce rimaste vuote = pixel trasparenti non voluti
  for (const [cube, fs] of Object.entries(layout))
    for (const [d, r] of Object.entries(fs)) {
      if (!r) continue;
      let empty = 0;
      for (let y = r.y; y < r.y + r.h; y++) for (let x = r.x; x < r.x + r.w; x++) if (img.get(x, y)[3] === 0) empty++;
      if (empty === r.w * r.h) warnings.push(`${cube}.${d}: faccia senza colore (trasparente)`);
    }
  return { painted, warnings };
}
