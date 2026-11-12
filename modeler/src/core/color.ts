export type RGBA = [number, number, number, number];

export function parseHex(hex: string): RGBA {
  let h = hex.trim().replace(/^#/, "");
  if (h.length === 3 || h.length === 4) h = [...h].map((c) => c + c).join("");
  if (!/^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(h)) throw new Error(`Colore non valido: ${hex}`);
  const n = (i: number) => parseInt(h.slice(i, i + 2), 16);
  return [n(0), n(2), n(4), h.length === 8 ? n(6) : 255];
}

export function toHex([r, g, b, a]: RGBA): string {
  const p = (x: number) => Math.round(Math.max(0, Math.min(255, x))).toString(16).padStart(2, "0");
  return `#${p(r)}${p(g)}${p(b)}${a < 255 ? p(a) : ""}`;
}

/** h in [0,360), s,v in [0,1] */
export function rgbToHsv([r, g, b]: RGBA | number[]): [number, number, number] {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  let h = 0;
  if (d) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return [h, max ? d / max : 0, max];
}

export function hsvToRgb(h: number, s: number, v: number, a = 255): RGBA {
  h = ((h % 360) + 360) % 360;
  s = Math.max(0, Math.min(1, s));
  v = Math.max(0, Math.min(1, v));
  const c = v * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = v - c;
  const [r, g, b] =
    h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [(r + m) * 255, (g + m) * 255, (b + m) * 255, a].map(Math.round) as RGBA;
}

/** Luminanza percettiva 0..1 */
export const luma = ([r, g, b]: RGBA | number[]) => (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;

function srgbToLin(c: number) {
  c /= 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

export function rgbToLab([r, g, b]: RGBA | number[]): [number, number, number] {
  const R = srgbToLin(r), G = srgbToLin(g), B = srgbToLin(b);
  const x = (R * 0.4124 + G * 0.3576 + B * 0.1805) / 0.95047;
  const y = R * 0.2126 + G * 0.7152 + B * 0.0722;
  const z = (R * 0.0193 + G * 0.1192 + B * 0.9505) / 1.08883;
  const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))];
}

/** Distanza percettiva (CIE76). <2.3 ≈ indistinguibile, <10 simile. */
export function deltaE(a: RGBA | number[], b: RGBA | number[]): number {
  const A = rgbToLab(a), B = rgbToLab(b);
  return Math.hypot(A[0] - B[0], A[1] - B[1], A[2] - B[2]);
}

function hueToward(h: number, target: number, amount: number) {
  let d = target - h;
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return h + Math.sign(d) * Math.min(Math.abs(d), amount);
}

export interface RampOptions {
  steps?: number;
  /** gradi di hue shift per step (ombre verso il blu/viola, luci verso il giallo). */
  hueShift?: number;
  /** variazione di value per step (0..1). */
  contrast?: number;
  /** variazione di saturazione per step. */
  saturationShift?: number;
}

/**
 * Genera una rampa di colori dal più scuro al più chiaro attorno a `base`,
 * con hue shifting come fanno i pixel artist (ombre fredde, luci calde).
 */
export function makeRamp(base: string, o: RampOptions = {}): string[] {
  const steps = o.steps ?? 5, hueShift = o.hueShift ?? 6, contrast = o.contrast ?? 0.12, satShift = o.saturationShift ?? 0.04;
  const [h, s, v] = rgbToHsv(parseHex(base));
  const mid = Math.floor((steps - 1) / 2);
  const out: string[] = [];
  for (let i = 0; i < steps; i++) {
    const k = i - mid; // <0 ombra, >0 luce
    const hh = k < 0 ? hueToward(h, 250, -k * hueShift) : hueToward(h, 60, k * hueShift);
    const ss = s + (k < 0 ? -k * satShift : -k * satShift * 1.5);
    const vv = v + k * contrast;
    out.push(toHex(hsvToRgb(hh, ss, vv)));
  }
  return out;
}

/** Schiarisce/scurisce un colore con hue shift: amount>0 luce, <0 ombra. */
export function shadeColor(c: RGBA, amount: number, hueShift = 8): RGBA {
  const [h, s, v] = rgbToHsv(c);
  const hh = amount < 0 ? hueToward(h, 250, -amount * hueShift * 4) : hueToward(h, 60, amount * hueShift * 4);
  const ss = s + (amount < 0 ? -amount * 0.25 : -amount * 0.35);
  return hsvToRgb(hh, ss, v + amount, c[3]);
}
