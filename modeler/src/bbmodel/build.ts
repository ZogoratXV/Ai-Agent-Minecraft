import { Img } from "../core/image";
import type { Vec3 } from "../core/math";
import { FACE_DIRS, faceSize, uuid, type Cube, type Face, type FaceDir, type Group, type Model, type OutlineNode } from "./model";
import type { ModelSpec } from "./spec";

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}
/** cubo -> faccia -> rettangolo in pixel della texture */
export type UvLayout = Record<string, Partial<Record<FaceDir, Rect & { mirrored?: boolean }>>>;

export interface BuildResult {
  model: Model;
  layout: UvLayout;
  texture: { width: number; height: number };
  warnings: string[];
}

interface Island {
  cube: string;
  w: number;
  h: number;
  faces: Partial<Record<FaceDir, Rect>>;
}

/** Isola "a croce" come il box UV: [up][down] sopra, [east][north][west][south] sotto. */
function makeIsland(cube: string, dx: number, dy: number, dz: number, hidden: Set<FaceDir>): Island {
  const faces: Partial<Record<FaceDir, Rect>> = {};
  const put = (d: FaceDir, x: number, y: number, w: number, h: number) => {
    if (!hidden.has(d) && w > 0 && h > 0) faces[d] = { x, y, w, h };
  };
  const top = hidden.has("up") && hidden.has("down") ? 0 : dz;
  put("up", dz, 0, dx, dz);
  put("down", dz + dx, 0, dx, dz);
  put("east", 0, top, dz, dy);
  put("north", dz, top, dx, dy);
  put("west", dz + dx, top, dz, dy);
  put("south", 2 * dz + dx, top, dx, dy);
  // compatta: rimuovi colonne vuote se mancano facce laterali
  const rects = Object.values(faces);
  const w = Math.max(0, ...rects.map((r) => r.x + r.w));
  const h = Math.max(0, ...rects.map((r) => r.y + r.h));
  return { cube, w, h, faces };
}

/** Shelf packing semplice con 1px di margine opzionale. */
function pack(islands: Island[], W: number, H: number, pad: number): Map<string, [number, number]> | null {
  const order = [...islands].sort((a, b) => b.h - a.h || b.w - a.w);
  const pos = new Map<string, [number, number]>();
  let x = 0, y = 0, rowH = 0;
  for (const is of order) {
    if (is.w > W) return null;
    if (x + is.w > W) {
      x = 0;
      y += rowH + pad;
      rowH = 0;
    }
    if (y + is.h > H) return null;
    pos.set(is.cube, [x, y]);
    x += is.w + pad;
    rowH = Math.max(rowH, is.h);
  }
  return pos;
}

const snap = (x: number) => Math.round(x * 1000) / 1000;

export function buildModel(spec: ModelSpec): BuildResult {
  const warnings: string[] = [];
  const ppu = spec.pixelsPerUnit ?? 1;
  const names = new Set<string>();
  for (const c of spec.cubes) {
    if (names.has(c.name)) throw new Error(`Nome cubo duplicato: ${c.name}`);
    names.add(c.name);
  }

  // --- isole UV
  const islands: Island[] = [];
  for (const c of spec.cubes) {
    if (c.uvFrom) continue;
    const d = [0, 1, 2].map((i) => Math.abs(c.to[i] - c.from[i]) * ppu);
    for (const [i, v] of d.entries())
      if (v > 0 && Math.abs(v - Math.round(v)) > 0.01)
        warnings.push(`${c.name}: lato ${"xyz"[i]} = ${snap(v)}px non intero → texel stirati (usa misure multiple di ${1 / ppu}).`);
    const [dx, dy, dz] = d.map((v) => (v > 0 ? Math.max(1, Math.round(v)) : 0));
    islands.push(makeIsland(c.name, dx, dy, dz, new Set(c.hideFaces ?? [])));
  }

  let W = spec.texture?.width ?? 16, H = spec.texture?.height ?? 16;
  let pos = pack(islands, W, H, 0);
  while (!pos) {
    if (W <= H) W *= 2;
    else H *= 2;
    if (W > 4096) throw new Error("Le UV non entrano neanche in 4096px: riduci pixelsPerUnit o le dimensioni");
    pos = pack(islands, W, H, 0);
  }
  if (spec.texture && (W !== spec.texture.width || H !== spec.texture.height))
    warnings.push(`Texture ingrandita a ${W}x${H} per far stare le UV.`);

  const layout: UvLayout = {};
  for (const is of islands) {
    const [ox, oy] = pos.get(is.cube)!;
    layout[is.cube] = {};
    for (const [d, r] of Object.entries(is.faces) as [FaceDir, Rect][]) layout[is.cube][d] = { x: ox + r.x, y: oy + r.y, w: r.w, h: r.h };
  }
  for (const c of spec.cubes) {
    if (!c.uvFrom) continue;
    const src = layout[c.uvFrom];
    if (!src) throw new Error(`${c.name}: uvFrom '${c.uvFrom}' non esiste o usa a sua volta uvFrom`);
    layout[c.name] = {};
    for (const d of FACE_DIRS) {
      // con mirror le facce est/ovest si scambiano
      const sd: FaceDir = c.mirrorUv && d === "east" ? "west" : c.mirrorUv && d === "west" ? "east" : d;
      const r = src[sd];
      if (r && !c.hideFaces?.includes(d)) layout[c.name][d] = { ...r, mirrored: !!c.mirrorUv };
    }
  }

  // --- outline
  const groups = new Map<string, { node: OutlineNode & { kind: "group" }; parent?: string }>();
  for (const b of spec.bones) {
    if (groups.has(b.name)) throw new Error(`Bone duplicato: ${b.name}`);
    const group: Group = { name: b.name, uuid: uuid(), origin: b.pivot as Vec3, rotation: (b.rotation ?? [0, 0, 0]) as Vec3 };
    groups.set(b.name, { node: { kind: "group", group, children: [] }, parent: b.parent });
  }
  const root: OutlineNode[] = [];
  for (const [name, g] of groups) {
    if (g.parent) {
      const p = groups.get(g.parent);
      if (!p) throw new Error(`Bone ${name}: parent '${g.parent}' inesistente`);
      p.node.children.push(g.node);
    } else root.push(g.node);
  }

  for (const c of spec.cubes) {
    const center: Vec3 = [0, 1, 2].map((i) => (c.from[i] + c.to[i]) / 2) as Vec3;
    const faces: Partial<Record<FaceDir, Face>> = {};
    for (const [d, r] of Object.entries(layout[c.name] ?? {}) as [FaceDir, Rect & { mirrored?: boolean }][]) {
      faces[d] = { uv: r.mirrored ? [r.x + r.w, r.y, r.x, r.y + r.h] : [r.x, r.y, r.x + r.w, r.y + r.h], texture: 0 };
    }
    const cube: Cube = {
      name: c.name,
      uuid: uuid(),
      from: c.from as Vec3,
      to: c.to as Vec3,
      origin: (c.pivot ?? center) as Vec3,
      rotation: (c.rotation ?? [0, 0, 0]) as Vec3,
      inflate: c.inflate ?? 0,
      mirror: !!c.mirrorUv,
      faces,
    };
    const node: OutlineNode = { kind: "cube", cube };
    if (c.bone) {
      const g = groups.get(c.bone);
      if (!g) throw new Error(`Cubo ${c.name}: bone '${c.bone}' inesistente`);
      g.node.children.push(node);
    } else root.push(node);
  }

  const img = new Img(W, H);
  return {
    model: {
      name: spec.name,
      format: spec.format,
      resolution: { width: W, height: H },
      outline: root,
      textures: [{ name: `${spec.name}.png`, uuid: uuid(), img, uvWidth: W, uvHeight: H }],
      unsupportedElements: 0,
      ...(spec.display ? { display: spec.display as Model["display"] } : {}),
    },
    layout,
    texture: { width: W, height: H },
    warnings,
  };
}

/** Rettangoli UV (pixel) di tutte le facce di un modello qualsiasi, per overlay e analisi. */
export function faceRects(m: Model): { cube: string; dir: FaceDir; tex: number; rect: Rect; size: [number, number] }[] {
  const out: { cube: string; dir: FaceDir; tex: number; rect: Rect; size: [number, number] }[] = [];
  const walk = (ns: OutlineNode[]) => {
    for (const n of ns) {
      if (n.kind === "group") walk(n.children);
      else
        for (const d of FACE_DIRS) {
          const f = n.cube.faces[d];
          if (!f || f.texture === null) continue;
          const t = m.textures[f.texture];
          if (!t) continue;
          const sx = t.img.width / t.uvWidth, sy = t.img.height / t.uvHeight;
          const x1 = Math.min(f.uv[0], f.uv[2]) * sx, x2 = Math.max(f.uv[0], f.uv[2]) * sx;
          const y1 = Math.min(f.uv[1], f.uv[3]) * sy, y2 = Math.max(f.uv[1], f.uv[3]) * sy;
          out.push({ cube: n.cube.name, dir: d, tex: f.texture, rect: { x: x1, y: y1, w: x2 - x1, h: y2 - y1 }, size: faceSize(n.cube, d) });
        }
    }
  };
  walk(m.outline);
  return out;
}
