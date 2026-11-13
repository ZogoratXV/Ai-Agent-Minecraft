import { apply, applyDir, identity, multiply, rotateAround, type Mat4, type Vec3 } from "../core/math";
import { walkCubes, type FaceDir, type Model } from "../bbmodel/model";

export interface MeshData {
  texture: number;
  positions: number[];
  uvs: number[];
  colors: number[];
}

const NORMALS: Record<FaceDir, Vec3> = {
  north: [0, 0, -1],
  south: [0, 0, 1],
  east: [1, 0, 0],
  west: [-1, 0, 0],
  up: [0, 1, 0],
  down: [0, -1, 0],
};

/** Angoli della faccia visti dall'esterno: TL, TR, BR, BL (convenzione UV Blockbench/Minecraft). */
export function corners(d: FaceDir, f: Vec3, t: Vec3): Vec3[] {
  switch (d) {
    case "north": return [[t[0], t[1], f[2]], [f[0], t[1], f[2]], [f[0], f[1], f[2]], [t[0], f[1], f[2]]];
    case "south": return [[f[0], t[1], t[2]], [t[0], t[1], t[2]], [t[0], f[1], t[2]], [f[0], f[1], t[2]]];
    case "east": return [[t[0], t[1], t[2]], [t[0], t[1], f[2]], [t[0], f[1], f[2]], [t[0], f[1], t[2]]];
    case "west": return [[f[0], t[1], f[2]], [f[0], t[1], t[2]], [f[0], f[1], t[2]], [f[0], f[1], f[2]]];
    case "up": return [[f[0], t[1], f[2]], [t[0], t[1], f[2]], [t[0], t[1], t[2]], [f[0], t[1], t[2]]];
    case "down": return [[f[0], f[1], t[2]], [t[0], f[1], t[2]], [t[0], f[1], f[2]], [f[0], f[1], f[2]]];
  }
}

/** Shading per direzione come in Minecraft: sopra 1.0, N/S 0.8, E/O 0.6, sotto 0.5. */
function shade(n: Vec3): number {
  return n[0] * n[0] * 0.6 + n[1] * n[1] * (n[1] > 0 ? 1 : 0.5) + n[2] * n[2] * 0.8;
}

export function buildMeshes(m: Model): { meshes: MeshData[]; min: Vec3; max: Vec3 } {
  const byTex = new Map<number, MeshData>();
  const min: Vec3 = [Infinity, Infinity, Infinity], max: Vec3 = [-Infinity, -Infinity, -Infinity];

  walkCubes(m.outline, (c, chain) => {
    let mat: Mat4 = identity();
    for (const g of chain) mat = multiply(mat, rotateAround(g.origin, g.rotation));
    mat = multiply(mat, rotateAround(c.origin, c.rotation));
    const inf = c.inflate;
    const f: Vec3 = [Math.min(c.from[0], c.to[0]) - inf, Math.min(c.from[1], c.to[1]) - inf, Math.min(c.from[2], c.to[2]) - inf];
    const t: Vec3 = [Math.max(c.from[0], c.to[0]) + inf, Math.max(c.from[1], c.to[1]) + inf, Math.max(c.from[2], c.to[2]) + inf];

    for (const [d, face] of Object.entries(c.faces) as [FaceDir, NonNullable<(typeof c.faces)[FaceDir]>][]) {
      if (face.texture === null) continue;
      const tex = m.textures[face.texture];
      if (!tex) continue;
      let mesh = byTex.get(face.texture);
      if (!mesh) byTex.set(face.texture, (mesh = { texture: face.texture, positions: [], uvs: [], colors: [] }));

      const cs = corners(d, f, t).map((p) => apply(mat, p));
      for (const p of cs) for (let i = 0; i < 3; i++) { min[i] = Math.min(min[i], p[i]); max[i] = Math.max(max[i], p[i]); }
      const [u1, v1, u2, v2] = face.uv;
      const uvc: [number, number][] = [[u1, v1], [u2, v1], [u2, v2], [u1, v2]];
      const k = (((face.rotation ?? 0) / 90) % 4 + 4) % 4;
      const uvRot = [0, 1, 2, 3].map((i) => uvc[(i - k + 4) % 4]);
      const n = applyDir(mat, NORMALS[d]);
      const b = shade(n);
      for (const i of [0, 3, 2, 0, 2, 1]) {
        mesh.positions.push(...cs[i]);
        mesh.uvs.push(uvRot[i][0] / tex.uvWidth, uvRot[i][1] / tex.uvHeight);
        mesh.colors.push(b, b, b);
      }
    }
  });
  if (!isFinite(min[0])) return { meshes: [], min: [0, 0, 0], max: [16, 16, 16] };
  return { meshes: [...byTex.values()], min, max };
}
