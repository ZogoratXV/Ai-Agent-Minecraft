import fs from "node:fs";
import path from "node:path";
import { buildModel, type BuildResult } from "./bbmodel/build";
import { ModelSpec, PaintProgram } from "./bbmodel/spec";
import { writeBBModel, writeJavaModel } from "./bbmodel/write";
import { paint, resolvePalette, type PaintReport } from "./texture/paint";
import { ROOT } from "./analyze/style";
import { allCubes, outlineDepth, type Model, type OutlineNode } from "./bbmodel/model";

export const PROJECTS_DIR = path.join(ROOT, "projects");

export const projectDir = (name: string) => {
  if (!/^[a-z0-9_\-]+$/i.test(name)) throw new Error("Nome progetto: solo lettere, numeri, _ e -");
  return path.join(PROJECTS_DIR, name);
};

const readJson = (p: string) => (fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, "utf8")) : null);

export function loadSpec(project: string): ModelSpec | null {
  const j = readJson(path.join(projectDir(project), "spec.json"));
  return j ? ModelSpec.parse(j) : null;
}

export function loadPaint(project: string): PaintProgram {
  return PaintProgram.parse(readJson(path.join(projectDir(project), "paint.json")) ?? {});
}

export function saveSpec(project: string, spec: ModelSpec) {
  fs.mkdirSync(projectDir(project), { recursive: true });
  fs.writeFileSync(path.join(projectDir(project), "spec.json"), JSON.stringify(spec, null, 2));
}

export function savePaint(project: string, p: PaintProgram) {
  fs.mkdirSync(projectDir(project), { recursive: true });
  fs.writeFileSync(path.join(projectDir(project), "paint.json"), JSON.stringify(p, null, 2));
}

export interface ProjectState extends BuildResult {
  paint: PaintReport;
  palette: Record<string, string>;
}

/** Ricostruisce modello + texture da spec.json e paint.json (la texture è "procedurale" e sempre riproducibile). */
export function buildProject(project: string): ProjectState {
  const spec = loadSpec(project);
  if (!spec) throw new Error(`Il progetto '${project}' non ha ancora un modello (usa set_model)`);
  const program = loadPaint(project);
  const b = buildModel(spec);
  const report = paint(b.model.textures[0].img, b.layout, program);
  return { ...b, paint: report, palette: resolvePalette(program) };
}

export function exportProject(project: string): string[] {
  const st = buildProject(project);
  const dir = path.join(projectDir(project), "export");
  fs.mkdirSync(dir, { recursive: true });
  const name = st.model.name.replace(/[^a-z0-9_\-]/gi, "_").toLowerCase();
  const files: string[] = [];
  const bb = path.join(dir, `${name}.bbmodel`);
  fs.writeFileSync(bb, JSON.stringify(writeBBModel(st.model)));
  files.push(bb);
  const png = path.join(dir, `${name}.png`);
  fs.writeFileSync(png, st.model.textures[0].img.toPng());
  files.push(png);
  if (st.model.format === "java_block") {
    const js = path.join(dir, `${name}.json`);
    fs.writeFileSync(js, JSON.stringify(writeJavaModel(st.model, `minecraft:block/${name}`), null, 2));
    files.push(js);
  }
  return files.map((f) => path.relative(ROOT, f));
}

/** Descrizione testuale della costruzione di un modello: come un artista "legge" un riferimento. */
export function describeModel(m: Model, maxCubes = 150): string {
  const lines: string[] = [];
  let n = 0;
  const fmt = (v: number[]) => `[${v.map((x) => Math.round(x * 100) / 100).join(",")}]`;
  const walk = (ns: OutlineNode[], ind: string) => {
    for (const node of ns) {
      if (n >= maxCubes) return;
      if (node.kind === "group") {
        const r = node.group.rotation.some(Boolean) ? ` rot${fmt(node.group.rotation)}` : "";
        lines.push(`${ind}▸ ${node.group.name} pivot${fmt(node.group.origin)}${r}`);
        walk(node.children, ind + "  ");
      } else {
        n++;
        const c = node.cube;
        const size = c.to.map((v, i) => v - c.from[i]);
        const r = c.rotation.some(Boolean) ? ` rot${fmt(c.rotation)}@${fmt(c.origin)}` : "";
        const inf = c.inflate ? ` inflate ${c.inflate}` : "";
        lines.push(`${ind}■ ${c.name} size${fmt(size)} from${fmt(c.from)}${r}${inf}`);
      }
    }
  };
  walk(m.outline, "");
  const total = allCubes(m.outline).length;
  if (total > maxCubes) lines.push(`… altri ${total - maxCubes} cubi`);
  return `Formato ${m.format}, ${total} cubi, profondità gerarchia ${outlineDepth(m.outline)}, texture ${m.textures.map((t) => `${t.img.width}x${t.img.height}`).join(", ") || "nessuna"}\n` + lines.join("\n");
}
