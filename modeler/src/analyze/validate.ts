import { isZero, round } from "../core/math";
import { walkCubes, type Cube, type FaceDir, type Model } from "../bbmodel/model";
import { modelMetrics, paletteConformance, type ModelMetrics } from "./metrics";
import type { Range, StyleProfile } from "./style";

export interface ValidationReport {
  score: number;
  errors: string[];
  warnings: string[];
  info: string[];
  metrics: Omit<ModelMetrics, "texelDensity"> & { texelDensity: { median: number; consistency: number } };
}

const JAVA_ANGLES = [-45, -22.5, 0, 22.5, 45];

const outside = (v: number, r: Range, tol: number) => v < r.p10 - tol || v > r.p90 + tol;

/** Facce coplanari sovrapposte (z-fighting) e facce interne completamente nascoste, solo su cubi non ruotati. */
function geometryChecks(m: Model, warnings: string[], info: string[]) {
  const boxes: { c: Cube; f: number[]; t: number[] }[] = [];
  walkCubes(m.outline, (c, chain) => {
    if (!isZero(c.rotation) || chain.some((g) => !isZero(g.rotation))) return;
    const i = c.inflate;
    boxes.push({ c, f: c.from.map((v, k) => Math.min(v, c.to[k]) - i), t: c.to.map((v, k) => Math.max(v, c.from[k]) + i) });
  });
  const dirs: [FaceDir, number, 0 | 1][] = [["west", 0, 0], ["east", 0, 1], ["down", 1, 0], ["up", 1, 1], ["north", 2, 0], ["south", 2, 1]];
  const overlap2 = (a: { f: number[]; t: number[] }, b: { f: number[]; t: number[] }, ax: number) => {
    let area = 1;
    for (let k = 0; k < 3; k++) {
      if (k === ax) continue;
      const o = Math.min(a.t[k], b.t[k]) - Math.max(a.f[k], b.f[k]);
      if (o <= 1e-6) return 0;
      area *= o;
    }
    return area;
  };
  const zf = new Set<string>();
  const hidden: string[] = [];
  for (let i = 0; i < boxes.length; i++)
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i], b = boxes[j];
      for (const [d, ax, side] of dirs) {
        const pa = side ? a.t[ax] : a.f[ax], pb = side ? b.t[ax] : b.f[ax];
        if (Math.abs(pa - pb) < 1e-6 && a.c.faces[d] && b.c.faces[d] && overlap2(a, b, ax) > 0) zf.add(`${a.c.name}/${b.c.name} (${d})`);
      }
    }
  // faccia nascosta: coperta interamente da un altro cubo che la tocca o la attraversa
  for (const a of boxes)
    for (const [d, ax, side] of dirs) {
      if (!a.c.faces[d]) continue;
      const p = side ? a.t[ax] : a.f[ax];
      const faceArea = [0, 1, 2].filter((k) => k !== ax).reduce((s, k) => s * (a.t[k] - a.f[k]), 1);
      if (faceArea <= 0) continue;
      const covered = boxes.some((b) => b !== a && b.f[ax] - 1e-6 <= p && b.t[ax] + 1e-6 >= p && (side ? b.t[ax] > p + 1e-6 : b.f[ax] < p - 1e-6) && Math.abs(overlap2(a, b, ax) - faceArea) < 1e-6);
      if (covered) hidden.push(`${a.c.name}.${d}`);
    }
  if (zf.size) warnings.push(`Z-fighting: facce coplanari sovrapposte fra ${[...zf].slice(0, 6).join(", ")}${zf.size > 6 ? "…" : ""}. Sposta di 1 unità o usa inflate minimo solo se lo stile lo consente.`);
  if (hidden.length) info.push(`Facce completamente nascoste (puoi metterle in hideFaces per risparmiare UV): ${hidden.slice(0, 10).join(", ")}${hidden.length > 10 ? "…" : ""}`);

  // parti fluttuanti
  if (boxes.length > 1) {
    const touches = (a: (typeof boxes)[0], b: (typeof boxes)[0]) => [0, 1, 2].every((k) => a.f[k] <= b.t[k] + 1e-6 && b.f[k] <= a.t[k] + 1e-6);
    const floating = boxes.filter((a) => !boxes.some((b) => b !== a && touches(a, b))).map((b) => b.c.name);
    if (floating.length) info.push(`Cubi che non toccano nessun altro cubo (verifica che non fluttuino): ${floating.slice(0, 8).join(", ")}`);
  }
}

export function validate(m: Model, profile: StyleProfile | null, notes: { category?: string } = {}): ValidationReport {
  const errors: string[] = [], warnings: string[] = [], info: string[] = [];
  const mm = modelMetrics(m);

  // --- tecnica
  if (mm.texelDensity.consistency < 0.9)
    warnings.push(`Densità texel incoerente: solo ${Math.round(mm.texelDensity.consistency * 100)}% delle facce è vicino a ${mm.texelDensity.median}px/unità (mixel).`);
  if (mm.nonIntegerCubes) warnings.push(`${mm.nonIntegerCubes} cubi hanno lati che non corrispondono a un numero intero di pixel → texel stirati.`);
  if (mm.overlappingUvFaces) errors.push(`${mm.overlappingUvFaces} facce hanno UV sovrapposte non intenzionali.`);
  if (mm.unsupportedElements) info.push(`${mm.unsupportedElements} elementi mesh/locator ignorati dall'analisi.`);
  geometryChecks(m, warnings, info);

  if (m.format === "java_block") {
    walkCubes(m.outline, (c) => {
      const axes = c.rotation.filter((r) => r !== 0);
      if (axes.length > 1) errors.push(`${c.name}: Java Edition permette la rotazione su un solo asse.`);
      if (axes.some((r) => !JAVA_ANGLES.includes(r))) errors.push(`${c.name}: angolo ${axes.join("/")}° non valido in Java (solo ±22.5, ±45).`);
      if ([...c.from, ...c.to].some((v) => v < -16 || v > 32)) errors.push(`${c.name}: coordinate fuori dal limite Java (-16..32).`);
      if (c.inflate) warnings.push(`${c.name}: inflate non esiste nei modelli Java (verrà perso all'export).`);
    });
    if (mm.groups) info.push("Formato java_block: i gruppi servono solo come organizzazione, non vengono esportati.");
  }

  // --- stile
  if (profile && profile.references > 0) {
    const sp = profile;
    if (sp.texelDensity.median && Math.abs(mm.texelDensity.median - sp.texelDensity.median) > sp.texelDensity.median * 0.15)
      errors.push(`Densità texel ${mm.texelDensity.median}px/unità diversa dallo stile (${sp.texelDensity.median}).`);
    const cat = notes.category && sp.cubesPerCategory[notes.category] ? sp.cubesPerCategory[notes.category] : sp.cubesPerModel;
    if (mm.cubes > cat.p90 * 1.5 && mm.cubes > 8) warnings.push(`${mm.cubes} cubi: molti più del tipico per lo stile (${cat.median}, p90 ${cat.p90}). Semplifica o giustifica.`);
    if (mm.cubes < cat.p10 / 2 && cat.p10 > 4) info.push(`${mm.cubes} cubi: meno del tipico (${cat.median}). Forse mancano dettagli di forma.`);

    const c = mm.colors, sc = sp.colors;
    if (outside(c.noise, sc.noise, 0.015)) warnings.push(`Rumore superficie ${c.noise} fuori dal range dello stile (${sc.noise.p10}–${sc.noise.p90}). ${c.noise > sc.noise.p90 ? "Riduci noise/dettaglio casuale." : "Aggiungi variazione/texture."}`);
    if (outside(c.meanSaturation, sc.saturation, 0.08)) warnings.push(`Saturazione ${c.meanSaturation} fuori stile (${sc.saturation.p10}–${sc.saturation.p90}).`);
    if (outside(c.meanValue, sc.value, 0.08)) warnings.push(`Luminosità ${c.meanValue} fuori stile (${sc.value.p10}–${sc.value.p90}).`);
    if (outside(c.contrast, sc.contrast, 0.08)) warnings.push(`Contrasto ${c.contrast} fuori stile (${sc.contrast.p10}–${sc.contrast.p90}).`);
    if (c.uniqueColors > sc.uniqueColorsPerModel.p90 * 1.5 && c.uniqueColors > 32) warnings.push(`${c.uniqueColors} colori unici: troppi per lo stile (tipico ${sc.uniqueColorsPerModel.median}). Riduci a rampe più corte.`);
    if (sc.hueShift.median > 4 && c.hueShift < 1) info.push(`Lo stile usa hue shifting (${sc.hueShift.median}°) ma questo modello no (${c.hueShift}°).`);
    if (Math.abs(c.edgeDarkening - sc.edgeDarkening.median) > 0.4) info.push(`Bordi faccia scuriti: ${c.edgeDarkening} contro ${sc.edgeDarkening.median} dello stile.`);
    const pc = paletteConformance(m, sc.palette.map((p) => p.hex), 14);
    if (pc.share < 0.75)
      warnings.push(`Solo ${Math.round(pc.share * 100)}% dei pixel è vicino alla palette dello stile. Colori più lontani: ${pc.offenders.map((o) => `${o.hex}→${o.nearest} (ΔE ${o.dE})`).join(", ")}.`);
    else info.push(`Conformità palette: ${Math.round(pc.share * 100)}%.`);
  } else info.push("Nessun profilo di stile: controlli solo tecnici.");

  info.push(`Copertura UV della texture: ${Math.round(mm.uvCoverage * 100)}%.`);
  const score = Math.max(0, 100 - errors.length * 20 - warnings.length * 7);
  const { values: _v, ...td } = mm.texelDensity;
  return { score: round(score, 0), errors, warnings, info, metrics: { ...mm, texelDensity: td } };
}
