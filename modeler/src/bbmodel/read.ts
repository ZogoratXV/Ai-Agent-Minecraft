import fs from "node:fs";
import path from "node:path";
import { Img } from "../core/image";
import type { Vec3 } from "../core/math";
import { FACE_DIRS, uuid, type Cube, type Face, type FaceDir, type Group, type Model, type OutlineNode, type Texture } from "./model";

/* eslint-disable @typescript-eslint/no-explicit-any */
const v3 = (a: any, d: Vec3 = [0, 0, 0]): Vec3 => (Array.isArray(a) && a.length >= 3 ? [+a[0], +a[1], +a[2]] : d);

/** Layout box-UV (stile Bedrock/Java entity) per un cubo senza UV per faccia. */
function boxUv(from: Vec3, to: Vec3, off: [number, number], mirror: boolean): Partial<Record<FaceDir, Face>> {
  const w = Math.abs(to[0] - from[0]), h = Math.abs(to[1] - from[1]), d = Math.abs(to[2] - from[2]);
  const [u, v] = off;
  const f = (uv: [number, number, number, number]): Face => ({
    uv: mirror ? [uv[2], uv[1], uv[0], uv[3]] : uv,
    texture: 0,
  });
  const east = f([u, v + d, u + d, v + d + h]);
  const west = f([u + d + w, v + d, u + 2 * d + w, v + d + h]);
  return {
    [mirror ? "west" : "east"]: east,
    north: f([u + d, v + d, u + d + w, v + d + h]),
    [mirror ? "east" : "west"]: west,
    south: f([u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h]),
    up: f([u + d + w, v + d, u + d, v]),
    down: f([u + d + 2 * w, v, u + d + w, v + d]),
  };
}

function loadTexture(t: any, baseDir: string, res: { width: number; height: number }, i: number, perTextureUv: boolean): Texture | null {
  let img: Img | null = null;
  try {
    if (typeof t.source === "string" && t.source.startsWith("data:image/png")) img = Img.fromDataUri(t.source);
    else {
      for (const p of [t.path, t.relative_path && path.join(baseDir, t.relative_path), t.name && path.join(baseDir, t.name)])
        if (p && fs.existsSync(p)) {
          img = Img.fromPng(fs.readFileSync(p));
          break;
        }
    }
  } catch {
    img = null;
  }
  if (!img) return null;
  const uvW = perTextureUv ? (t.uv_width ?? res.width) : res.width;
  const uvH = perTextureUv ? (t.uv_height ?? res.height) : res.height;
  img = img.firstFrame(uvH / uvW);
  return {
    name: t.name ?? `texture_${i}`,
    uuid: t.uuid ?? uuid(),
    img,
    // Solo il formato java_block ha UV per-texture; negli altri le UV delle facce sono nella risoluzione del progetto
    uvWidth: perTextureUv ? (t.uv_width ?? res.width) : res.width,
    uvHeight: perTextureUv ? (t.uv_height ?? res.height) : res.height,
  };
}

/** Legge un file .bbmodel (formato 3.x/4.x con gruppi nell'outliner, e 5.x con array `groups`). */
export function readBBModel(json: any, baseDir = "."): Model {
  const res = { width: json.resolution?.width ?? 16, height: json.resolution?.height ?? 16 };
  const textures: Texture[] = [];
  const texIndex = new Map<number, number>();
  const perTextureUv = (json.meta?.model_format ?? "free") === "java_block";
  (json.textures ?? []).forEach((t: any, i: number) => {
    const tex = loadTexture(t, baseDir, res, i, perTextureUv);
    if (tex) {
      texIndex.set(i, textures.length);
      textures.push(tex);
    }
  });

  const cubes = new Map<string, Cube>();
  let unsupported = 0;
  for (const e of json.elements ?? []) {
    if (e.type && e.type !== "cube") {
      unsupported++;
      continue;
    }
    // cubi di riferimento non esportati/nascosti (es. corpo del giocatore nei modelli di armatura)
    if (e.export === false || e.visibility === false) continue;
    const from = v3(e.from), to = v3(e.to);
    let faces: Partial<Record<FaceDir, Face>> = {};
    const hasFaceUv = e.faces && FACE_DIRS.some((d) => Array.isArray(e.faces[d]?.uv));
    if (e.box_uv && (!hasFaceUv || e.uv_offset)) faces = boxUv(from, to, (e.uv_offset ?? [0, 0]) as [number, number], !!e.mirror_uv);
    else
      for (const d of FACE_DIRS) {
        const f = e.faces?.[d];
        if (!f || f.texture === null || f.texture === undefined || f.enabled === false) continue;
        const ti = typeof f.texture === "number" ? (texIndex.get(f.texture) ?? null) : null;
        faces[d] = { uv: (f.uv ?? [0, 0, 0, 0]).map(Number) as Face["uv"], texture: ti, rotation: f.rotation || 0 };
      }
    if (e.box_uv) for (const d of FACE_DIRS) if (faces[d] && e.faces?.[d]?.texture === null) delete faces[d];
    cubes.set(e.uuid ?? uuid(), {
      name: e.name ?? "cube",
      uuid: e.uuid ?? uuid(),
      from,
      to,
      origin: v3(e.origin),
      rotation: v3(e.rotation),
      inflate: +e.inflate || 0,
      mirror: !!e.mirror_uv,
      faces,
    });
  }

  const groupDefs = new Map<string, any>();
  for (const g of json.groups ?? []) if (g?.uuid) groupDefs.set(g.uuid, g);
  const used = new Set<string>();

  const toNode = (entry: any): OutlineNode | null => {
    if (typeof entry === "string") {
      const c = cubes.get(entry);
      if (c) {
        used.add(entry);
        return { kind: "cube", cube: c };
      }
      const g = groupDefs.get(entry);
      return g ? toNode({ ...g, children: g.children ?? [] }) : null;
    }
    if (!entry || typeof entry !== "object") return null;
    const def = { ...(groupDefs.get(entry.uuid) ?? {}), ...entry };
    const group: Group = { name: def.name ?? "group", uuid: def.uuid ?? uuid(), origin: v3(def.origin), rotation: v3(def.rotation) };
    return { kind: "group", group, children: (def.children ?? []).map(toNode).filter(Boolean) as OutlineNode[] };
  };

  const outline = (json.outliner ?? []).map(toNode).filter(Boolean) as OutlineNode[];
  for (const [id, c] of cubes) if (!used.has(id)) outline.push({ kind: "cube", cube: c });

  return {
    name: json.name || json.model_identifier || "model",
    format: json.meta?.model_format ?? "free",
    resolution: res,
    outline,
    textures,
    unsupportedElements: unsupported,
  };
}

/** Legge un modello Java Edition (block/item JSON con `elements`). Le texture vengono cercate per nome in `textureDirs`. */
export function readJavaModel(json: any, name: string, textureDirs: string[]): Model {
  const texKeys = Object.keys(json.textures ?? {});
  const textures: Texture[] = [];
  const keyToIndex = new Map<string, number>();
  for (const k of texKeys) {
    const ref = String(json.textures[k]);
    if (ref.startsWith("#")) continue;
    const base = ref.split("/").pop()!.replace(/^.*:/, "") + ".png";
    for (const dir of textureDirs) {
      const p = path.join(dir, base);
      const already = textures.findIndex((t) => t.name === base);
      if (already >= 0) {
        keyToIndex.set(k, already);
        break;
      }
      if (fs.existsSync(p)) {
        // nei modelli Java lo spazio UV è sempre quadrato: le texture animate vanno ridotte al primo fotogramma
        const img = Img.fromPng(fs.readFileSync(p)).firstFrame(1);
        keyToIndex.set(k, textures.length);
        textures.push({ name: base, uuid: uuid(), img, uvWidth: 16, uvHeight: 16 });
        break;
      }
    }
  }
  const resolveKey = (k: string, depth = 0): number | null => {
    k = k.replace(/^#/, "");
    if (keyToIndex.has(k)) return keyToIndex.get(k)!;
    const ref = json.textures?.[k];
    if (typeof ref === "string" && ref.startsWith("#") && depth < 8) return resolveKey(ref, depth + 1);
    return null;
  };

  const outline: OutlineNode[] = [];
  (json.elements ?? []).forEach((e: any, i: number) => {
    const from = v3(e.from), to = v3(e.to);
    const rotation: Vec3 = [0, 0, 0];
    let origin: Vec3 = [8, 8, 8];
    if (e.rotation) {
      const ax = ({ x: 0, y: 1, z: 2 } as const)[e.rotation.axis as "x" | "y" | "z"] ?? 1;
      rotation[ax] = +e.rotation.angle || 0;
      origin = v3(e.rotation.origin, origin);
    }
    const faces: Partial<Record<FaceDir, Face>> = {};
    for (const d of FACE_DIRS) {
      const f = e.faces?.[d];
      if (!f) continue;
      let uv = f.uv as Face["uv"] | undefined;
      if (!uv) {
        // UV automatiche come fa il gioco: proiezione della posizione
        const [x1, y1, z1] = from, [x2, y2, z2] = to;
        uv =
          d === "north" ? [16 - x2, 16 - y2, 16 - x1, 16 - y1]
          : d === "south" ? [x1, 16 - y2, x2, 16 - y1]
          : d === "east" ? [16 - z2, 16 - y2, 16 - z1, 16 - y1]
          : d === "west" ? [z1, 16 - y2, z2, 16 - y1]
          : d === "up" ? [x1, z1, x2, z2]
          : [x1, 16 - z2, x2, 16 - z1];
      }
      faces[d] = { uv, texture: resolveKey(String(f.texture ?? "")), rotation: f.rotation || 0 };
    }
    outline.push({
      kind: "cube",
      cube: { name: e.name ?? `element_${i}`, uuid: uuid(), from, to, origin, rotation, inflate: 0, faces },
    });
  });
  return { name, format: "java_block", resolution: { width: 16, height: 16 }, outline, textures, unsupportedElements: 0 };
}

/** Carica un file modello in base all'estensione. */
export function loadModelFile(file: string): Model {
  const json = JSON.parse(fs.readFileSync(file, "utf8"));
  const dir = path.dirname(file);
  if (file.endsWith(".bbmodel")) return readBBModel(json, dir);
  if (Array.isArray(json.elements) && !json.meta)
    return readJavaModel(json, path.basename(file, ".json"), [dir, path.join(dir, "textures"), path.join(dir, "..", "textures")]);
  if (json.meta?.format_version) return readBBModel(json, dir);
  throw new Error(`Formato non riconosciuto: ${file}`);
}
