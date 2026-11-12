import type { Vec3 } from "../core/math";
import type { Img } from "../core/image";

export const FACE_DIRS = ["north", "east", "south", "west", "up", "down"] as const;
export type FaceDir = (typeof FACE_DIRS)[number];

/** Formati di progetto Blockbench supportati. */
export type ModelFormat = "java_block" | "bedrock" | "free" | string;

export interface Face {
  /** [x1, y1, x2, y2] nello spazio UV del progetto (resolution / uv_width). */
  uv: [number, number, number, number];
  texture: number | null;
  rotation?: number;
}

export interface Cube {
  name: string;
  uuid: string;
  from: Vec3;
  to: Vec3;
  origin: Vec3;
  rotation: Vec3;
  inflate: number;
  mirror?: boolean;
  faces: Partial<Record<FaceDir, Face>>;
}

export interface Group {
  name: string;
  uuid: string;
  origin: Vec3;
  rotation: Vec3;
}

export type OutlineNode = { kind: "group"; group: Group; children: OutlineNode[] } | { kind: "cube"; cube: Cube };

export interface Texture {
  name: string;
  uuid: string;
  img: Img;
  /** dimensione dello spazio UV (in Blockbench può differire dai pixel reali). */
  uvWidth: number;
  uvHeight: number;
}

export interface Model {
  name: string;
  format: ModelFormat;
  resolution: { width: number; height: number };
  outline: OutlineNode[];
  textures: Texture[];
  /** elementi non-cubo (mesh, locator...) presenti ma non gestiti. */
  unsupportedElements: number;
}

export function allCubes(nodes: OutlineNode[], out: Cube[] = []): Cube[] {
  for (const n of nodes) n.kind === "cube" ? out.push(n.cube) : allCubes(n.children, out);
  return out;
}

export function allGroups(nodes: OutlineNode[], out: Group[] = []): Group[] {
  for (const n of nodes)
    if (n.kind === "group") {
      out.push(n.group);
      allGroups(n.children, out);
    }
  return out;
}

export function outlineDepth(nodes: OutlineNode[]): number {
  let d = 0;
  for (const n of nodes) if (n.kind === "group") d = Math.max(d, 1 + outlineDepth(n.children));
  return d;
}

/** Visita i cubi con la catena di gruppi antenati (dal più esterno). */
export function walkCubes(nodes: OutlineNode[], fn: (c: Cube, chain: Group[]) => void, chain: Group[] = []) {
  for (const n of nodes)
    if (n.kind === "cube") fn(n.cube, chain);
    else walkCubes(n.children, fn, [...chain, n.group]);
}

/** Dimensioni della faccia in unità modello: [larghezza, altezza]. */
export function faceSize(c: Cube, dir: FaceDir): [number, number] {
  const dx = Math.abs(c.to[0] - c.from[0]), dy = Math.abs(c.to[1] - c.from[1]), dz = Math.abs(c.to[2] - c.from[2]);
  switch (dir) {
    case "north":
    case "south":
      return [dx, dy];
    case "east":
    case "west":
      return [dz, dy];
    default:
      return [dx, dz];
  }
}

export function uuid(): string {
  const hex = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}
