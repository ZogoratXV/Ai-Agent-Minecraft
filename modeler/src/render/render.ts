import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { chromium, type Browser, type Page } from "playwright";
import type { Model } from "../bbmodel/model";
import { faceRects } from "../bbmodel/build";
import { buildMeshes, type MeshData } from "./geometry";
import { Img } from "../core/image";
import { uuid, type Cube, type FaceDir, type OutlineNode } from "../bbmodel/model";
import { apply, applyDir, multiply, type Mat4, type Vec3 } from "../core/math";

const require = createRequire(import.meta.url);
const THREE_DIR = path.dirname(require.resolve("three")); // .../three/build
const ORIGIN = "http://render.local";

const PAGE = `<!doctype html><html><head><meta charset="utf-8">
<script type="importmap">{"imports":{"three":"${ORIGIN}/three/three.module.js"}}</script></head>
<body style="margin:0;background:#000"><script type="module">
import * as THREE from "three";
window.__render = async (job) => {
  const renderer = new THREE.WebGLRenderer({ antialias: false, preserveDrawingBuffer: true, alpha: true });
  renderer.setPixelRatio(1);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  const loadTex = (uri) => new Promise((res) => new THREE.TextureLoader().load(uri, (t) => {
    t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.flipY = false;
    t.colorSpace = THREE.SRGBColorSpace; t.generateMipmaps = false; res(t); }));
  const scene = new THREE.Scene();
  const texs = await Promise.all(job.textures.map(loadTex));
  for (const m of job.meshes) {
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(m.positions, 3));
    g.setAttribute("uv", new THREE.Float32BufferAttribute(m.uvs, 2));
    g.setAttribute("color", new THREE.Float32BufferAttribute(m.colors, 3));
    const mat = new THREE.MeshBasicMaterial({ map: texs[m.texture], vertexColors: true, side: THREE.DoubleSide, alphaTest: 0.1, transparent: false });
    scene.add(new THREE.Mesh(g, mat));
  }
  const [min, max] = [job.min, job.max];
  const center = new THREE.Vector3((min[0]+max[0])/2, (min[1]+max[1])/2, (min[2]+max[2])/2);
  const radius = Math.max(0.5, Math.hypot(max[0]-min[0], max[1]-min[1], max[2]-min[2]) / 2);
  const cell = job.cell, cols = job.cols, rows = Math.ceil(job.views.length / cols);
  const out = document.createElement("canvas"); out.width = cell * cols; out.height = cell * rows + (job.texturePanel ? 0 : 0);
  const ctx = out.getContext("2d"); ctx.imageSmoothingEnabled = false;
  renderer.setSize(cell, cell);
  job.views.forEach((v, i) => {
    const cam = new THREE.OrthographicCamera(-radius*1.1, radius*1.1, radius*1.1, -radius*1.1, 0.1, radius * 20);
    const dir = new THREE.Vector3(...v.dir).normalize();
    cam.position.copy(center.clone().add(dir.multiplyScalar(radius * 5)));
    cam.up.set(0, 1, 0); cam.lookAt(center);
    renderer.setClearColor(new THREE.Color(job.background), 1);
    renderer.render(scene, cam);
    const x = (i % cols) * cell, y = Math.floor(i / cols) * cell;
    ctx.drawImage(renderer.domElement, x, y);
    ctx.fillStyle = "rgba(0,0,0,.55)"; ctx.fillRect(x, y, ctx.measureText(v.label).width + 14, 20);
    ctx.fillStyle = "#fff"; ctx.font = "13px monospace"; ctx.fillText(v.label, x + 6, y + 14);
    ctx.strokeStyle = "rgba(255,255,255,.15)"; ctx.strokeRect(x + .5, y + .5, cell - 1, cell - 1);
  });
  renderer.dispose();
  return out.toDataURL("image/png");
};
window.__texture = async (job) => {
  const img = await new Promise((res) => { const i = new Image(); i.onload = () => res(i); i.src = job.texture; });
  const scale = Math.max(1, Math.floor(job.size / Math.max(img.width, img.height)));
  const c = document.createElement("canvas"); c.width = img.width * scale; c.height = img.height * scale;
  const ctx = c.getContext("2d"); ctx.imageSmoothingEnabled = false;
  for (let y = 0; y < img.height; y++) for (let x = 0; x < img.width; x++) {
    ctx.fillStyle = (x + y) % 2 ? "#cfcfcf" : "#a8a8a8"; ctx.fillRect(x*scale, y*scale, scale, scale); }
  ctx.drawImage(img, 0, 0, c.width, c.height);
  if (job.grid && scale >= 6) { ctx.strokeStyle = "rgba(0,0,0,.12)"; ctx.lineWidth = 1;
    for (let x = 0; x <= img.width; x++) { ctx.beginPath(); ctx.moveTo(x*scale+.5, 0); ctx.lineTo(x*scale+.5, c.height); ctx.stroke(); }
    for (let y = 0; y <= img.height; y++) { ctx.beginPath(); ctx.moveTo(0, y*scale+.5); ctx.lineTo(c.width, y*scale+.5); ctx.stroke(); } }
  ctx.strokeStyle = "rgba(0,220,255,.9)"; ctx.lineWidth = 1;
  ctx.font = Math.max(9, Math.min(13, scale * 2)) + "px monospace";
  for (const r of job.rects) { ctx.strokeRect(r.x*scale+.5, r.y*scale+.5, r.w*scale-1, r.h*scale-1);
    if (job.labels && r.w*scale > 40 && r.h*scale > 14) { ctx.fillStyle = "rgba(0,0,0,.6)";
      const t = r.label; ctx.fillRect(r.x*scale+1, r.y*scale+1, ctx.measureText(t).width+4, 13);
      ctx.fillStyle = "#7ff"; ctx.fillText(t, r.x*scale+3, r.y*scale+11); } }
  return c.toDataURL("image/png");
};
window.__ready = true;
</script></body></html>`;

let browser: Browser | null = null;
let page: Page | null = null;

async function getPage(): Promise<Page> {
  if (page) return page;
  browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
  page = await browser.newPage();
  await page.route(`${ORIGIN}/**`, async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/" || url.pathname === "/index.html") return route.fulfill({ contentType: "text/html", body: PAGE });
    if (url.pathname.startsWith("/three/")) {
      const file = path.join(THREE_DIR, path.basename(url.pathname));
      if (fs.existsSync(file)) return route.fulfill({ contentType: "application/javascript", body: fs.readFileSync(file) });
    }
    return route.fulfill({ status: 404, body: "" });
  });
  await page.goto(`${ORIGIN}/index.html`);
  await page.waitForFunction(() => (window as unknown as { __ready?: boolean }).__ready === true);
  return page;
}

export async function closeRenderer() {
  await browser?.close();
  browser = null;
  page = null;
}

export interface View {
  label: string;
  dir: [number, number, number];
}

/** Viste standard. In Blockbench il "fronte" di un'entità guarda verso nord (-Z). */
export const STANDARD_VIEWS: View[] = [
  { label: "front (north)", dir: [0, 0, -1] },
  { label: "right (east)", dir: [1, 0, 0] },
  { label: "back (south)", dir: [0, 0, 1] },
  { label: "left (west)", dir: [-1, 0, 0] },
  { label: "3/4 front-left top", dir: [-1, 0.8, -1] },
  { label: "3/4 back-right top", dir: [1, 0.8, 1] },
  { label: "top", dir: [0, 1, -0.001] },
  { label: "3/4 front-right low", dir: [1, -0.35, -1] },
];

const fromDataUrl = (s: string) => Buffer.from(s.split(",")[1], "base64");

export async function renderViews(m: Model, opts: { views?: View[]; cell?: number; cols?: number; background?: string } = {}): Promise<Buffer> {
  const { meshes, min, max } = buildMeshes(m);
  return renderMeshes(meshes, m.textures.map((t) => t.img.toDataUri()), min, max, opts);
}

async function renderMeshes(
  meshes: MeshData[],
  textures: string[],
  min: number[],
  max: number[],
  opts: { views?: View[]; cell?: number; cols?: number; background?: string } = {},
): Promise<Buffer> {
  const p = await getPage();
  const views = opts.views ?? STANDARD_VIEWS;
  const job = {
    meshes,
    min,
    max,
    textures,
    views,
    cell: opts.cell ?? 320,
    cols: opts.cols ?? 4,
    background: opts.background ?? "#6e7480",
  };
  const url = await p.evaluate((j) => (window as unknown as { __render: (j: unknown) => Promise<string> }).__render(j), job);
  return fromDataUrl(url);
}

export async function renderTexture(m: Model, texIndex = 0, opts: { size?: number; labels?: boolean } = {}): Promise<Buffer | null> {
  const t = m.textures[texIndex];
  if (!t) return null;
  const p = await getPage();
  const rects = faceRects(m)
    .filter((r) => r.tex === texIndex)
    .map((r) => ({ ...r.rect, label: `${r.cube}.${r.dir[0].toUpperCase()}` }));
  const job = { texture: t.img.toDataUri(), rects, size: opts.size ?? 768, grid: true, labels: opts.labels ?? true };
  const url = await p.evaluate((j) => (window as unknown as { __texture: (j: unknown) => Promise<string> }).__texture(j), job);
  return fromDataUrl(url);
}


/* ---------- render "in mano" ---------- */

const PLAYER_COLORS: [number, number, number, number][] = [
  [199, 156, 124, 255], // pelle
  [72, 126, 140, 255], // maglia
  [64, 72, 140, 255], // pantaloni
  [90, 90, 90, 255], // scarpe
];

/** Giocatore di riferimento 1:1 (alto 32 unità = 2 blocchi), rivolto a nord, mano destra verso +X. */
function playerModel(): Model {
  const tex = new Img(4, 1);
  PLAYER_COLORS.forEach((c, i) => tex.set(i, 0, c));
  const part = (name: string, from: Vec3, to: Vec3, color: number): OutlineNode => {
    const faces: Cube["faces"] = {};
    for (const d of ["north", "east", "south", "west", "up", "down"] as FaceDir[]) faces[d] = { uv: [color + 0.25, 0.25, color + 0.75, 0.75], texture: 0 };
    return { kind: "cube", cube: { name, uuid: uuid(), from, to, origin: [0, 0, 0], rotation: [0, 0, 0], inflate: 0, faces } };
  };
  return {
    name: "player",
    format: "free",
    resolution: { width: 4, height: 1 },
    textures: [{ name: "player", uuid: uuid(), img: tex, uvWidth: 4, uvHeight: 1 }],
    unsupportedElements: 0,
    outline: [
      part("head", [-4, 24, -4], [4, 32, 4], 0),
      part("body", [-4, 12, -2], [4, 24, 2], 1),
      part("arm_r", [4, 12, -2], [8, 24, 2], 1),
      part("arm_l", [-8, 12, -2], [-4, 24, 2], 1),
      part("leg_r", [0, 0, -2], [4, 12, 2], 2),
      part("leg_l", [-4, 0, -2], [0, 12, 2], 2),
      part("shoe_r", [0, 0, -2.01], [4, 2, 2.01], 3),
      part("shoe_l", [-4, 0, -2.01], [0, 2, 2.01], 3),
    ],
  };
}

const rad = (d: number) => (d * Math.PI) / 180;
function eulerXYZ([x, y, z]: Vec3): Mat4 {
  const cx = Math.cos(rad(x)), sx = Math.sin(rad(x)), cy = Math.cos(rad(y)), sy = Math.sin(rad(y)), cz = Math.cos(rad(z)), sz = Math.sin(rad(z));
  const Rx: Mat4 = [1, 0, 0, 0, 0, cx, sx, 0, 0, -sx, cx, 0, 0, 0, 0, 1];
  const Ry: Mat4 = [cy, 0, -sy, 0, 0, 1, 0, 0, sy, 0, cy, 0, 0, 0, 0, 1];
  const Rz: Mat4 = [cz, sz, 0, 0, -sz, cz, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
  return multiply(Rx, multiply(Ry, Rz));
}

/** Punto della mano destra del giocatore di riferimento (fondo del braccio, leggermente in avanti). */
export const HAND_POINT: Vec3 = [6, 11, -1];

/**
 * Mostra l'item tenuto in mano destra (terza persona) da un giocatore 1:1, applicando
 * display.thirdperson_righthand (traslazione, rotazione, scala attorno al centro del blocco 8,8,8).
 * Serve a giudicare proporzioni e posizione dell'impugnatura.
 */
export async function renderInHand(m: Model): Promise<Buffer> {
  const d = m.display?.thirdperson_righthand ?? {};
  // Approssimazione: oggetto tenuto dritto. La rotazione del braccio e quella di display non vengono applicate
  // (dipendono dalla posa del giocatore); scala e traslazione sì, perché decidono grandezza e punto di presa.
  const S = d.scale ?? [1, 1, 1], T = d.translation ?? [0, 0, 0], R = eulerXYZ([0, 0, 0]);
  const item = buildMeshes(m);
  for (const mesh of item.meshes) {
    for (let i = 0; i < mesh.positions.length; i += 3) {
      const p: Vec3 = [(mesh.positions[i] - 8) * S[0], (mesh.positions[i + 1] - 8) * S[1], (mesh.positions[i + 2] - 8) * S[2]];
      const q = apply(R, p);
      mesh.positions[i] = q[0] + T[0] + HAND_POINT[0];
      mesh.positions[i + 1] = q[1] + T[1] + HAND_POINT[1];
      mesh.positions[i + 2] = q[2] + T[2] + HAND_POINT[2];
    }
  }
  void applyDir;
  const player = buildMeshes(playerModel());
  const offset = m.textures.length;
  for (const mesh of player.meshes) mesh.texture += offset;
  const meshes = [...item.meshes, ...player.meshes];
  const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
  for (const mesh of meshes)
    for (let i = 0; i < mesh.positions.length; i += 3)
      for (let k = 0; k < 3; k++) { min[k] = Math.min(min[k], mesh.positions[i + k]); max[k] = Math.max(max[k], mesh.positions[i + k]); }
  const scaleLabel = `scala ${S.map((x) => +x.toFixed(2)).join("/")}`;
  return renderMeshes(meshes, [...m.textures.map((t) => t.img.toDataUri()), playerModel().textures[0].img.toDataUri()], min, max, {
    views: [
      { label: `in mano ≈ (${scaleLabel})`, dir: [0, 0.1, -1] },
      { label: "in mano: lato destro", dir: [1, 0.1, 0] },
      { label: "in mano: 3/4", dir: [1, 0.5, -1] },
    ],
    cols: 3,
    cell: 360,
  });
}
