import { apply, applyDir, identity, multiply, rotateAround, type Mat4, type Vec3 } from "../core/math";
import { walkCubes, type FaceDir, type Model } from "../bbmodel/model";
import { faceRects } from "../bbmodel/build";
import { corners } from "../render/geometry";

const NORMALS: Record<FaceDir, Vec3> = {
  north: [0, 0, -1],
  south: [0, 0, 1],
  east: [1, 0, 0],
  west: [-1, 0, 0],
  up: [0, 1, 0],
  down: [0, -1, 0],
};

interface WorldFace {
  label: string;
  cube: string;
  pts: Vec3[];
  n: Vec3;
}

const sub = (a: Vec3, b: Vec3): Vec3 => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a: Vec3, b: Vec3) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a: Vec3, b: Vec3): Vec3 => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a: Vec3): Vec3 => {
  const l = Math.hypot(...a) || 1;
  return [a[0] / l, a[1] / l, a[2] / l];
};

/** Facce texturizzate in coordinate mondo (con rotazioni di cubi e bone). */
export function worldFaces(m: Model): WorldFace[] {
  const out: WorldFace[] = [];
  walkCubes(m.outline, (c, chain) => {
    let mat: Mat4 = identity();
    for (const g of chain) mat = multiply(mat, rotateAround(g.origin, g.rotation));
    mat = multiply(mat, rotateAround(c.origin, c.rotation));
    const i = c.inflate;
    const f: Vec3 = [0, 1, 2].map((k) => Math.min(c.from[k], c.to[k]) - i) as Vec3;
    const t: Vec3 = [0, 1, 2].map((k) => Math.max(c.from[k], c.to[k]) + i) as Vec3;
    for (const [d, face] of Object.entries(c.faces) as [FaceDir, NonNullable<(typeof c.faces)[FaceDir]>][]) {
      if (!face || face.texture === null) continue;
      out.push({ label: `${c.name}.${d}`, cube: c.uuid, pts: corners(d, f, t).map((p) => apply(mat, p)), n: norm(applyDir(mat, NORMALS[d])) });
    }
  });
  return out;
}

type P2 = [number, number];

function polyArea(p: P2[]) {
  let a = 0;
  for (let i = 0; i < p.length; i++) {
    const [x1, y1] = p[i], [x2, y2] = p[(i + 1) % p.length];
    a += x1 * y2 - x2 * y1;
  }
  return Math.abs(a) / 2;
}

/** Intersezione di poligoni convessi (Sutherland–Hodgman). */
function clip(subject: P2[], clipper: P2[]): P2[] {
  // orienta il clipper in senso antiorario
  let c = clipper;
  let s = 0;
  for (let i = 0; i < c.length; i++) s += c[i][0] * c[(i + 1) % c.length][1] - c[(i + 1) % c.length][0] * c[i][1];
  if (s < 0) c = [...c].reverse();
  let out = subject;
  for (let i = 0; i < c.length && out.length; i++) {
    const a = c[i], b = c[(i + 1) % c.length];
    const inside = (p: P2) => (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= -1e-9;
    const inter = (p: P2, q: P2): P2 => {
      const a1 = b[1] - a[1], b1 = a[0] - b[0], c1 = a1 * a[0] + b1 * a[1];
      const a2 = q[1] - p[1], b2 = p[0] - q[0], c2 = a2 * p[0] + b2 * p[1];
      const det = a1 * b2 - a2 * b1;
      return det === 0 ? p : [(b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det];
    };
    const input = out;
    out = [];
    for (let j = 0; j < input.length; j++) {
      const p = input[j], q = input[(j + 1) % input.length];
      if (inside(q)) {
        if (!inside(p)) out.push(inter(p, q));
        out.push(q);
      } else if (inside(p)) out.push(inter(p, q));
    }
  }
  return out;
}

/**
 * Z-fighting su tutte le facce, anche di cubi ruotati: due facce di cubi diversi sullo stesso piano,
 * rivolte nella stessa direzione e sovrapposte per un'area visibile.
 */
export function zFighting(m: Model, minArea = 0.05): string[] {
  const faces = worldFaces(m);
  const found: string[] = [];
  for (let i = 0; i < faces.length; i++)
    for (let j = i + 1; j < faces.length; j++) {
      const a = faces[i], b = faces[j];
      if (a.cube === b.cube || dot(a.n, b.n) < 0.999) continue;
      if (Math.abs(dot(a.n, sub(b.pts[0], a.pts[0]))) > 1e-3) continue;
      const u = norm(sub(a.pts[1], a.pts[0])), v = cross(a.n, u);
      const to2 = (p: Vec3): P2 => [dot(sub(p, a.pts[0]), u), dot(sub(p, a.pts[0]), v)];
      const area = polyArea(clip(a.pts.map(to2), b.pts.map(to2)));
      if (area > minArea) found.push(`${a.label} / ${b.label}`);
    }
  return found;
}

/** UV che non cadono sulla griglia dei pixel: campionano pixel delle isole vicine (sbavature). */
export function offGridUvs(m: Model): string[] {
  const out: string[] = [];
  const off = (x: number) => Math.abs(x - Math.round(x)) > 0.02;
  for (const r of faceRects(m)) {
    const { x, y, w, h } = r.rect;
    if (off(x) || off(y) || off(x + w) || off(y + h)) out.push(`${r.cube}.${r.dir} [${+x.toFixed(2)},${+y.toFixed(2)} ${+w.toFixed(2)}x${+h.toFixed(2)}]`);
  }
  return out;
}

/** Facce le cui UV si sovrappongono solo in parte (quelle identiche sono riuso voluto). */
export function partialUvOverlaps(m: Model): string[] {
  const r = faceRects(m);
  const out: string[] = [];
  for (let i = 0; i < r.length; i++)
    for (let j = i + 1; j < r.length; j++) {
      if (r[i].tex !== r[j].tex) continue;
      const a = r[i].rect, b = r[j].rect;
      const ix = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x), iy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
      if (ix <= 0.01 || iy <= 0.01) continue;
      const same = [a.x - b.x, a.y - b.y, a.w - b.w, a.h - b.h].every((d) => Math.abs(d) < 0.01);
      if (!same) out.push(`${r[i].cube}.${r[i].dir} ∩ ${r[j].cube}.${r[j].dir}`);
    }
  return out;
}

/** Facce texturizzate che mostrano pixel trasparenti (buchi) senza che sia voluto. */
export function transparentFaces(m: Model): string[] {
  const out: string[] = [];
  for (const r of faceRects(m)) {
    const img = m.textures[r.tex].img;
    let tr = 0, tot = 0;
    for (let y = Math.floor(r.rect.y); y < Math.ceil(r.rect.y + r.rect.h); y++)
      for (let x = Math.floor(r.rect.x); x < Math.ceil(r.rect.x + r.rect.w); x++) {
        if (!img.inside(x, y)) continue;
        tot++;
        if (img.get(x, y)[3] < 128) tr++;
      }
    if (tr) out.push(`${r.cube}.${r.dir} (${tr}/${tot} px)`);
  }
  return out;
}
