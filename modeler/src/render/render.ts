import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { chromium, type Browser, type Page } from "playwright";
import type { Model } from "../bbmodel/model";
import { faceRects } from "../bbmodel/build";
import { buildMeshes } from "./geometry";

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
  const p = await getPage();
  const { meshes, min, max } = buildMeshes(m);
  const views = opts.views ?? STANDARD_VIEWS;
  const job = {
    meshes,
    min,
    max,
    textures: m.textures.map((t) => t.img.toDataUri()),
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
