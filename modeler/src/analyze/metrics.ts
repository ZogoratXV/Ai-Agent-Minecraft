import { deltaE, luma, rgbToHsv, toHex, type RGBA } from "../core/color";
import { median, quantile, round } from "../core/math";
import { allCubes, allGroups, outlineDepth, type Model } from "../bbmodel/model";
import { faceRects } from "../bbmodel/build";

export interface ModelMetrics {
  name: string;
  format: string;
  cubes: number;
  groups: number;
  depth: number;
  unsupportedElements: number;
  size: [number, number, number];
  textures: { name: string; width: number; height: number }[];
  /** densità texel (pixel per unità) */
  texelDensity: { median: number; consistency: number; values: number[] };
  rotation: { rotatedCubes: number; offGrid: number; angles: number[] };
  inflatedCubes: number;
  nonIntegerCubes: number;
  zeroThicknessCubes: number;
  /** % di texture coperta da UV */
  uvCoverage: number;
  overlappingUvFaces: number;
  colors: ColorMetrics;
}

export interface ColorMetrics {
  uniqueColors: number;
  palette: { hex: string; share: number }[];
  meanSaturation: number;
  meanValue: number;
  contrast: number;
  /** variazione media di luminanza fra pixel adiacenti della stessa faccia (0 = piatto, alto = rumoroso) */
  noise: number;
  /** spostamento medio di hue fra toni scuri e chiari della stessa famiglia (gradi, + = luci più calde) */
  hueShift: number;
  transparentShare: number;
  /** rapporto pixel dei bordi faccia più scuri dell'interno (stile "outline") */
  edgeDarkening: number;
}

/** Palette per median cut su un insieme di pixel opachi. */
export function medianCut(px: RGBA[], k: number): { color: RGBA; count: number }[] {
  if (!px.length) return [];
  let boxes: RGBA[][] = [px];
  while (boxes.length < k) {
    let bi = -1, bestRange = -1, bestCh = 0;
    boxes.forEach((b, i) => {
      if (b.length < 2) return;
      for (let ch = 0; ch < 3; ch++) {
        let lo = 255, hi = 0;
        for (const p of b) { lo = Math.min(lo, p[ch]); hi = Math.max(hi, p[ch]); }
        if (hi - lo > bestRange) { bestRange = hi - lo; bi = i; bestCh = ch; }
      }
    });
    if (bi < 0 || bestRange <= 2) break;
    const b = boxes[bi].sort((a, c) => a[bestCh] - c[bestCh]);
    const mid = b.length >> 1;
    boxes.splice(bi, 1, b.slice(0, mid), b.slice(mid));
  }
  return boxes
    .map((b) => {
      const s = [0, 0, 0];
      for (const p of b) for (let i = 0; i < 3; i++) s[i] += p[i];
      return { color: [s[0] / b.length, s[1] / b.length, s[2] / b.length, 255].map(Math.round) as RGBA, count: b.length };
    })
    .sort((a, b) => b.count - a.count);
}

export function colorMetrics(m: Model): ColorMetrics {
  const px: RGBA[] = [];
  const unique = new Set<number>();
  let transparent = 0, total = 0, neighborDiff = 0, neighborN = 0, edgeDark = 0, edgeN = 0;
  const rects = faceRects(m);
  const seen = new Set<string>();
  for (const r of rects) {
    const t = m.textures[r.tex];
    const x0 = Math.floor(r.rect.x), y0 = Math.floor(r.rect.y), x1 = Math.ceil(r.rect.x + r.rect.w), y1 = Math.ceil(r.rect.y + r.rect.h);
    const key = `${r.tex}:${x0},${y0},${x1},${y1}`;
    if (seen.has(key)) continue;
    seen.add(key);
    let inner = 0, innerN = 0, border = 0, borderN = 0;
    for (let y = y0; y < y1; y++)
      for (let x = x0; x < x1; x++) {
        if (!t.img.inside(x, y)) continue;
        const c = t.img.get(x, y);
        total++;
        if (c[3] < 128) { transparent++; continue; }
        px.push(c);
        unique.add((c[0] << 16) | (c[1] << 8) | c[2]);
        const l = luma(c);
        if (x + 1 < x1 && t.img.inside(x + 1, y)) { const n = t.img.get(x + 1, y); if (n[3] >= 128) { neighborDiff += Math.abs(l - luma(n)); neighborN++; } }
        if (y + 1 < y1 && t.img.inside(x, y + 1)) { const n = t.img.get(x, y + 1); if (n[3] >= 128) { neighborDiff += Math.abs(l - luma(n)); neighborN++; } }
        const isEdge = x === x0 || y === y0 || x === x1 - 1 || y === y1 - 1;
        if (isEdge) { border += l; borderN++; } else { inner += l; innerN++; }
      }
    if (borderN && innerN && x1 - x0 >= 4 && y1 - y0 >= 4) {
      edgeN++;
      if (border / borderN < inner / innerN - 0.06) edgeDark++;
    }
  }
  const hsv = px.map(rgbToHsv);
  const lum = px.map(luma);

  // hue shift: per famiglia di hue (bin da 30°) confronta hue medio dei toni scuri e chiari
  const fam = new Map<number, { h: number; l: number }[]>();
  hsv.forEach(([h, s], i) => {
    if (s < 0.12) return;
    const k = Math.round(h / 30) % 12;
    (fam.get(k) ?? fam.set(k, []).get(k)!).push({ h, l: lum[i] });
  });
  const shifts: number[] = [];
  for (const arr of fam.values()) {
    if (arr.length < 20) continue;
    const lo = quantile(arr.map((a) => a.l), 0.25), hi = quantile(arr.map((a) => a.l), 0.75);
    if (hi - lo < 0.05) continue;
    const meanH = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;
    const dark = meanH(arr.filter((a) => a.l <= lo).map((a) => a.h)), light = meanH(arr.filter((a) => a.l >= hi).map((a) => a.h));
    // positivo se le luci vanno verso il giallo (60°) rispetto alle ombre
    const towardYellow = (h: number) => -Math.abs(((h - 60 + 540) % 360) - 180);
    shifts.push(towardYellow(light) - towardYellow(dark));
  }

  const pal = medianCut(px, 16);
  return {
    uniqueColors: unique.size,
    palette: pal.map((p) => ({ hex: toHex(p.color), share: round(p.count / Math.max(1, px.length), 3) })),
    meanSaturation: round(hsv.reduce((a, h) => a + h[1], 0) / Math.max(1, hsv.length)),
    meanValue: round(hsv.reduce((a, h) => a + h[2], 0) / Math.max(1, hsv.length)),
    contrast: round(quantile(lum, 0.95) - quantile(lum, 0.05)),
    noise: round(neighborN ? neighborDiff / neighborN : 0, 3),
    hueShift: round(shifts.length ? median(shifts) : 0, 1),
    transparentShare: round(transparent / Math.max(1, total)),
    edgeDarkening: round(edgeN ? edgeDark / edgeN : 0),
  };
}

export function modelMetrics(m: Model): ModelMetrics {
  const cubes = allCubes(m.outline);
  const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
  for (const c of cubes) for (let i = 0; i < 3; i++) { min[i] = Math.min(min[i], c.from[i], c.to[i]); max[i] = Math.max(max[i], c.from[i], c.to[i]); }

  const rects = faceRects(m);
  const dens: number[] = [];
  for (const r of rects) {
    const [w, h] = r.size;
    if (w >= 0.5 && r.rect.w > 0) dens.push(r.rect.w / w);
    if (h >= 0.5 && r.rect.h > 0) dens.push(r.rect.h / h);
  }
  const med = median(dens);
  const consistent = dens.filter((d) => Math.abs(d - med) <= med * 0.15).length;

  let covered = 0, texArea = 0, overlaps = 0;
  m.textures.forEach((t, ti) => {
    const mask = new Uint8Array(t.img.width * t.img.height);
    texArea += mask.length;
    const seen = new Set<string>();
    for (const r of rects.filter((r) => r.tex === ti)) {
      const k = `${Math.floor(r.rect.x)},${Math.floor(r.rect.y)},${Math.ceil(r.rect.w)},${Math.ceil(r.rect.h)}`;
      const dup = seen.has(k);
      seen.add(k);
      let hit = false;
      for (let y = Math.floor(r.rect.y); y < Math.ceil(r.rect.y + r.rect.h); y++)
        for (let x = Math.floor(r.rect.x); x < Math.ceil(r.rect.x + r.rect.w); x++) {
          if (x < 0 || y < 0 || x >= t.img.width || y >= t.img.height) continue;
          const i = y * t.img.width + x;
          if (mask[i] && !dup) hit = true;
          mask[i] = 1;
        }
      if (hit) overlaps++;
    }
    covered += mask.reduce((a, b) => a + b, 0);
  });

  const angles = new Set<number>();
  let rotated = 0, offGrid = 0;
  for (const c of cubes)
    if (c.rotation.some((r) => r !== 0)) {
      rotated++;
      c.rotation.forEach((r) => r && angles.add(r));
      if (c.rotation.some((r) => r % 22.5 !== 0)) offGrid++;
    }
  for (const g of allGroups(m.outline)) g.rotation.forEach((r) => r && angles.add(r));

  const isInt = (x: number) => Math.abs(x * med - Math.round(x * med)) < 0.05;
  return {
    name: m.name,
    format: m.format,
    cubes: cubes.length,
    groups: allGroups(m.outline).length,
    depth: outlineDepth(m.outline),
    unsupportedElements: m.unsupportedElements,
    size: [0, 1, 2].map((i) => round(isFinite(max[i]) ? max[i] - min[i] : 0)) as [number, number, number],
    textures: m.textures.map((t) => ({ name: t.name, width: t.img.width, height: t.img.height })),
    texelDensity: { median: round(med), consistency: round(dens.length ? consistent / dens.length : 1), values: dens.map((d) => round(d)) },
    rotation: { rotatedCubes: rotated, offGrid, angles: [...angles].sort((a, b) => a - b).slice(0, 16) },
    inflatedCubes: cubes.filter((c) => c.inflate).length,
    nonIntegerCubes: med ? cubes.filter((c) => [0, 1, 2].some((i) => !isInt(c.to[i] - c.from[i]))).length : 0,
    zeroThicknessCubes: cubes.filter((c) => [0, 1, 2].some((i) => c.to[i] === c.from[i])).length,
    uvCoverage: round(texArea ? covered / texArea : 0),
    overlappingUvFaces: overlaps,
    colors: colorMetrics(m),
  };
}

/** Quanta parte dei pixel del modello è vicina (ΔE) alla palette dello stile. */
export function paletteConformance(m: Model, palette: string[], threshold = 12): { share: number; offenders: { hex: string; nearest: string; dE: number }[] } {
  const ref = palette.map((h) => ({ hex: h, rgb: [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)] }));
  if (!ref.length) return { share: 1, offenders: [] };
  const counts = new Map<number, number>();
  for (const r of faceRects(m)) {
    const t = m.textures[r.tex];
    for (let y = Math.floor(r.rect.y); y < Math.ceil(r.rect.y + r.rect.h); y++)
      for (let x = Math.floor(r.rect.x); x < Math.ceil(r.rect.x + r.rect.w); x++) {
        if (!t.img.inside(x, y)) continue;
        const c = t.img.get(x, y);
        if (c[3] < 128) continue;
        const k = (c[0] << 16) | (c[1] << 8) | c[2];
        counts.set(k, (counts.get(k) ?? 0) + 1);
      }
  }
  let ok = 0, tot = 0;
  const offenders: { hex: string; nearest: string; dE: number; n: number }[] = [];
  for (const [k, n] of counts) {
    const rgb = [(k >> 16) & 255, (k >> 8) & 255, k & 255];
    let best = Infinity, bestHex = "";
    for (const r of ref) { const d = deltaE(rgb, r.rgb); if (d < best) { best = d; bestHex = r.hex; } }
    tot += n;
    if (best <= threshold) ok += n;
    else offenders.push({ hex: toHex([rgb[0], rgb[1], rgb[2], 255]), nearest: bestHex, dE: round(best, 1), n });
  }
  offenders.sort((a, b) => b.n - a.n);
  return { share: round(tot ? ok / tot : 1), offenders: offenders.slice(0, 8).map(({ hex, nearest, dE }) => ({ hex, nearest, dE })) };
}
