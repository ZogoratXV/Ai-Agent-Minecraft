import fs from "node:fs";
import path from "node:path";
import { Img } from "../core/image";
import { median, quantile, round } from "../core/math";
import { toHex, type RGBA } from "../core/color";
import { loadModelFile } from "../bbmodel/read";
import type { Model } from "../bbmodel/model";
import { medianCut, modelMetrics, type ModelMetrics } from "./metrics";
import { renderTexture, renderViews } from "../render/render";

/** cartella del codice (knowledge/) */
export const CODE_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../..");
/** cartella dati (styles/, projects/): sovrascrivibile con ARTIST_HOME */
export const ROOT = process.env.ARTIST_HOME ? path.resolve(process.env.ARTIST_HOME) : CODE_ROOT;
export const STYLES_DIR = path.join(ROOT, "styles");

export interface ReferenceEntry {
  id: string;
  file: string;
  category: string;
  kind: "model" | "image";
  preview?: string;
  texturePreview?: string;
  metrics?: ModelMetrics;
  image?: { width: number; height: number };
  error?: string;
}

export interface Range {
  median: number;
  p10: number;
  p90: number;
}

export interface StyleProfile {
  style: string;
  builtAt: string;
  references: number;
  images: number;
  categories: Record<string, number>;
  formats: Record<string, number>;
  texelDensity: Range & { consistency: number };
  textureSizes: Record<string, number>;
  cubesPerModel: Range;
  cubesPerCategory: Record<string, Range>;
  rotatedModelsShare: number;
  offGridRotationShare: number;
  inflateUsageShare: number;
  colors: {
    palette: { hex: string; share: number }[];
    uniqueColorsPerModel: Range;
    saturation: Range;
    value: Range;
    contrast: Range;
    noise: Range;
    hueShift: Range;
    edgeDarkening: Range;
    transparency: Range;
  };
}

const rng = (xs: number[]): Range => ({ median: round(median(xs)), p10: round(quantile(xs, 0.1)), p90: round(quantile(xs, 0.9)) });

export const styleDir = (style: string) => path.join(STYLES_DIR, style);

export function listStyles(): string[] {
  if (!fs.existsSync(STYLES_DIR)) return [];
  return fs.readdirSync(STYLES_DIR).filter((d) => fs.existsSync(path.join(STYLES_DIR, d, "references")));
}

export function initStyle(style: string) {
  const dir = styleDir(style);
  fs.mkdirSync(path.join(dir, "references"), { recursive: true });
  const notes = path.join(dir, "notes.md");
  if (!fs.existsSync(notes))
    fs.writeFileSync(
      notes,
      `# Regole dello stile "${style}"\n\n` +
        `<!-- Scrivi qui le regole che l'artista DEVE rispettare. Hanno priorità sulle statistiche automatiche. Esempi: -->\n` +
        `<!-- - Densità: 16px per blocco, niente mixel. -->\n` +
        `<!-- - Palette calda e desaturata, ombre verso il viola. -->\n` +
        `<!-- - Niente outline neri; bordi evidenziati solo sul lato in luce. -->\n` +
        `<!-- - Proporzioni chibi: testa grande quanto il busto. -->\n`,
    );
  const lessons = path.join(dir, "lessons.md");
  if (!fs.existsSync(lessons)) fs.writeFileSync(lessons, `# Lezioni apprese (stile "${style}")\n\n`);
}

function walk(dir: string, base = dir): string[] {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? walk(p, base) : [p];
  });
}

/** Trova i file di riferimento: .bbmodel, modelli Java .json, immagini .png non usate come texture di un modello Java. */
function findReferences(refDir: string): { file: string; kind: "model" | "image" }[] {
  const files = walk(refDir);
  const models = files.filter((f) => f.endsWith(".bbmodel") || (f.endsWith(".json") && isJavaModel(f)));
  const javaTextureNames = new Set<string>();
  for (const f of models.filter((f) => f.endsWith(".json")))
    try {
      const j = JSON.parse(fs.readFileSync(f, "utf8"));
      for (const v of Object.values(j.textures ?? {})) javaTextureNames.add(String(v).split("/").pop() + ".png");
    } catch { /* ignora */ }
  const images = files.filter((f) => /\.png$/i.test(f) && !javaTextureNames.has(path.basename(f)));
  return [...models.map((file) => ({ file, kind: "model" as const })), ...images.map((file) => ({ file, kind: "image" as const }))];
}

function isJavaModel(f: string) {
  try {
    const j = JSON.parse(fs.readFileSync(f, "utf8"));
    return Array.isArray(j.elements);
  } catch {
    return false;
  }
}

export function readIndex(style: string): ReferenceEntry[] {
  const p = path.join(styleDir(style), "index.json");
  return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, "utf8")) : [];
}

export function readProfile(style: string): StyleProfile | null {
  const p = path.join(styleDir(style), "profile.json");
  return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, "utf8")) : null;
}

export function loadReference(style: string, id: string): { entry: ReferenceEntry; model?: Model } {
  const entry = readIndex(style).find((e) => e.id === id);
  if (!entry) throw new Error(`Riferimento '${id}' non trovato nello stile ${style}`);
  if (entry.kind === "image") return { entry };
  return { entry, model: loadModelFile(path.join(styleDir(style), entry.file)) };
}

/**
 * Analizza tutti i riferimenti di uno stile: metriche per modello, anteprime renderizzate,
 * profilo aggregato (profile.json) e guida leggibile (STYLE.md).
 */
export async function buildStyle(style: string, opts: { previews?: boolean; log?: (s: string) => void } = {}): Promise<StyleProfile> {
  const log = opts.log ?? (() => {});
  const dir = styleDir(style);
  initStyle(style);
  const refDir = path.join(dir, "references");
  const prevDir = path.join(dir, "previews");
  fs.mkdirSync(prevDir, { recursive: true });

  const index: ReferenceEntry[] = [];
  const allPx: RGBA[] = [];
  const refs = findReferences(refDir);
  if (!refs.length) log(`Nessun riferimento in ${refDir}. Aggiungi file .bbmodel, modelli Java .json (+ .png) o immagini .png.`);

  for (const { file, kind } of refs) {
    const rel = path.relative(dir, file);
    const relRef = path.relative(refDir, file);
    const category = relRef.includes(path.sep) ? relRef.split(path.sep)[0] : "generale";
    const id = relRef.replace(/\.(bbmodel|json|png)$/i, "").replace(/[\\/]/g, "__");
    const entry: ReferenceEntry = { id, file: rel, category, kind };
    try {
      if (kind === "image") {
        const img = Img.fromPng(fs.readFileSync(file));
        entry.image = { width: img.width, height: img.height };
        // i pixel delle immagini contribuiscono alla palette (campionati per non dominare)
        const step = Math.max(1, Math.floor((img.width * img.height) / 20000));
        for (let i = 0; i < img.width * img.height; i += step) {
          const c = img.get(i % img.width, Math.floor(i / img.width));
          if (c[3] >= 128) allPx.push(c);
        }
      } else {
        const model = loadModelFile(file);
        entry.metrics = modelMetrics(model);
        for (const t of model.textures)
          for (let y = 0; y < t.img.height; y++)
            for (let x = 0; x < t.img.width; x++) {
              const c = t.img.get(x, y);
              if (c[3] >= 128) allPx.push(c);
            }
        if (opts.previews !== false) {
          const views = await renderViews(model, {
            views: [
              { label: id, dir: [-1, 0.8, -1] },
              { label: "back", dir: [1, 0.6, 1] },
            ],
            cell: 384,
            cols: 2,
          });
          entry.preview = path.join("previews", `${id}.png`);
          fs.writeFileSync(path.join(dir, entry.preview), views);
          const tex = await renderTexture(model, 0, { size: 512, labels: false });
          if (tex) {
            entry.texturePreview = path.join("previews", `${id}.texture.png`);
            fs.writeFileSync(path.join(dir, entry.texturePreview), tex);
          }
        }
      }
      log(`✓ ${rel}`);
    } catch (e) {
      entry.error = (e as Error).message;
      log(`✗ ${rel}: ${entry.error}`);
    }
    index.push(entry);
  }

  const ms = index.filter((e) => e.metrics).map((e) => e.metrics!);
  const count = <T extends string>(xs: T[]) => xs.reduce<Record<string, number>>((a, x) => ((a[x] = (a[x] ?? 0) + 1), a), {});
  const byCat: Record<string, number[]> = {};
  index.forEach((e) => e.metrics && (byCat[e.category] ??= []).push(e.metrics.cubes));

  const sample = allPx.length > 400000 ? allPx.filter((_, i) => i % Math.ceil(allPx.length / 400000) === 0) : allPx;
  const profile: StyleProfile = {
    style,
    builtAt: new Date().toISOString(),
    references: ms.length,
    images: index.filter((e) => e.kind === "image" && !e.error).length,
    categories: count(index.map((e) => e.category)),
    formats: count(ms.map((m) => m.format)),
    texelDensity: { ...rng(ms.map((m) => m.texelDensity.median).filter(Boolean)), consistency: round(median(ms.map((m) => m.texelDensity.consistency))) },
    textureSizes: count(ms.flatMap((m) => m.textures.map((t) => `${t.width}x${t.height}`))),
    cubesPerModel: rng(ms.map((m) => m.cubes)),
    cubesPerCategory: Object.fromEntries(Object.entries(byCat).map(([k, v]) => [k, rng(v)])),
    rotatedModelsShare: round(ms.filter((m) => m.rotation.rotatedCubes > 0).length / Math.max(1, ms.length)),
    offGridRotationShare: round(ms.filter((m) => m.rotation.offGrid > 0).length / Math.max(1, ms.length)),
    inflateUsageShare: round(ms.filter((m) => m.inflatedCubes > 0).length / Math.max(1, ms.length)),
    colors: {
      palette: medianCut(sample, 32).map((p) => ({ hex: toHex(p.color), share: round(p.count / Math.max(1, sample.length), 3) })),
      uniqueColorsPerModel: rng(ms.map((m) => m.colors.uniqueColors)),
      saturation: rng(ms.map((m) => m.colors.meanSaturation)),
      value: rng(ms.map((m) => m.colors.meanValue)),
      contrast: rng(ms.map((m) => m.colors.contrast)),
      noise: rng(ms.map((m) => m.colors.noise)),
      hueShift: rng(ms.map((m) => m.colors.hueShift)),
      edgeDarkening: rng(ms.map((m) => m.colors.edgeDarkening)),
      transparency: rng(ms.map((m) => m.colors.transparentShare)),
    },
  };

  fs.writeFileSync(path.join(dir, "index.json"), JSON.stringify(index, null, 2));
  fs.writeFileSync(path.join(dir, "profile.json"), JSON.stringify(profile, null, 2));
  fs.writeFileSync(path.join(dir, "STYLE.md"), styleMarkdown(profile));
  return profile;
}

const describe = (v: number, steps: [number, string][], last: string) => steps.find(([t]) => v < t)?.[1] ?? last;

/** Traduce il profilo numerico in regole leggibili per l'artista. */
export function styleMarkdown(p: StyleProfile): string {
  const c = p.colors;
  const sizes = Object.entries(p.textureSizes).sort((a, b) => b[1] - a[1]).slice(0, 4).map(([k, n]) => `${k} (${n})`).join(", ");
  const r = (x: Range, d = 2) => `${round(x.median, d)} (p10–p90: ${round(x.p10, d)}–${round(x.p90, d)})`;
  return `# Profilo automatico dello stile "${p.style}"

_Generato da ${p.references} modelli e ${p.images} immagini di riferimento (${p.builtAt.slice(0, 10)}). Le regole scritte a mano in \`notes.md\` hanno sempre la precedenza._

## Geometria
- **Formati**: ${Object.entries(p.formats).map(([k, v]) => `${k} ×${v}`).join(", ") || "n/d"}
- **Densità texel**: ${r(p.texelDensity)} px per unità → ${p.texelDensity.median ? `${round(p.texelDensity.median * 16)}px per blocco` : "n/d"}. Consistenza media ${Math.round(p.texelDensity.consistency * 100)}% delle facce. ${p.texelDensity.consistency >= 0.9 ? "**Regola: densità uniforme ovunque, niente mixel.**" : "Lo stile tollera densità miste."}
- **Texture tipiche**: ${sizes || "n/d"}
- **Cubi per modello**: ${r(p.cubesPerModel, 0)}${Object.keys(p.cubesPerCategory).length > 1 ? "\n" + Object.entries(p.cubesPerCategory).map(([k, v]) => `  - ${k}: ${r(v, 0)}`).join("\n") : ""}
- **Rotazioni**: ${Math.round(p.rotatedModelsShare * 100)}% dei modelli usa cubi ruotati; ${Math.round(p.offGridRotationShare * 100)}% usa angoli non multipli di 22.5°.
- **Inflate**: usato nel ${Math.round(p.inflateUsageShare * 100)}% dei modelli.

## Colore e texture
- **Colori unici per modello**: ${r(c.uniqueColorsPerModel, 0)} → ${describe(c.uniqueColorsPerModel.median, [[24, "palette molto ristretta, rampe corte"], [64, "palette contenuta"], [160, "palette ricca"]], "molti colori (texture dipinte/rumorose)")}.
- **Saturazione media**: ${r(c.saturation)} → ${describe(c.saturation.median, [[0.2, "desaturato/terroso"], [0.4, "saturazione moderata"], [0.6, "colori vivaci"]], "molto saturo/cartoon")}.
- **Luminosità media**: ${r(c.value)} → ${describe(c.value.median, [[0.35, "scuro"], [0.6, "medio"]], "chiaro")}.
- **Contrasto (luma p5–p95)**: ${r(c.contrast)} → ${describe(c.contrast.median, [[0.25, "basso contrasto, morbido"], [0.5, "contrasto medio"]], "alto contrasto, molto leggibile")}.
- **Rumore/dettaglio superficie**: ${r(c.noise, 3)} → ${describe(c.noise.median, [[0.03, "superfici pulite e piatte: pochissimo noise"], [0.07, "texture moderata: noise leggero e controllato"], [0.12, "superfici texturizzate"]], "molto rumoroso/granuloso")}.
- **Hue shifting**: ${r(c.hueShift, 1)}° → ${c.hueShift.median > 4 ? "luci più calde e ombre più fredde: usa rampe con hue shift" : c.hueShift.median < -4 ? "luci più fredde delle ombre (insolito, rispettalo)" : "shading quasi solo per luminosità, hue shift minimo"}.
- **Bordi faccia scuriti**: ${r(c.edgeDarkening)} → ${describe(c.edgeDarkening.median, [[0.2, "nessun outline sui bordi delle facce"], [0.5, "bordi scuriti a volte (AO sui bordi)"]], "bordi delle facce spesso scuriti (outline/bevel)")}.
- **Trasparenza**: ${r(c.transparency)} delle aree UV è trasparente.

## Palette di riferimento (median cut, per frequenza)
${c.palette.map((x) => `\`${x.hex}\` ${Math.round(x.share * 1000) / 10}%`).join(" · ")}
`;
}
