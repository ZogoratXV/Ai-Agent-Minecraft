export type Vec3 = [number, number, number];
/** Matrice 4x4 column-major (stessa convenzione di three.js). */
export type Mat4 = number[];

export const identity = (): Mat4 => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

export function multiply(a: Mat4, b: Mat4): Mat4 {
  const o = new Array(16).fill(0);
  for (let c = 0; c < 4; c++)
    for (let r = 0; r < 4; r++) {
      let s = 0;
      for (let k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
      o[c * 4 + r] = s;
    }
  return o;
}

export const translate = ([x, y, z]: Vec3): Mat4 => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1];

const rad = (d: number) => (d * Math.PI) / 180;

function rotX(d: number): Mat4 {
  const c = Math.cos(rad(d)), s = Math.sin(rad(d));
  return [1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1];
}
function rotY(d: number): Mat4 {
  const c = Math.cos(rad(d)), s = Math.sin(rad(d));
  return [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1];
}
function rotZ(d: number): Mat4 {
  const c = Math.cos(rad(d)), s = Math.sin(rad(d));
  return [c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
}

/** Rotazione Euler in gradi con ordine 'ZYX' (quello usato da Blockbench). */
export const euler = (r: Vec3): Mat4 => multiply(rotZ(r[2]), multiply(rotY(r[1]), rotX(r[0])));

/** Rotazione `r` attorno al pivot `o`. */
export const rotateAround = (o: Vec3, r: Vec3): Mat4 =>
  multiply(translate(o), multiply(euler(r), translate([-o[0], -o[1], -o[2]])));

export function apply(m: Mat4, [x, y, z]: Vec3): Vec3 {
  return [
    m[0] * x + m[4] * y + m[8] * z + m[12],
    m[1] * x + m[5] * y + m[9] * z + m[13],
    m[2] * x + m[6] * y + m[10] * z + m[14],
  ];
}

export function applyDir(m: Mat4, [x, y, z]: Vec3): Vec3 {
  return [m[0] * x + m[4] * y + m[8] * z, m[1] * x + m[5] * y + m[9] * z, m[2] * x + m[6] * y + m[10] * z];
}

export const isZero = (v: Vec3 | undefined) => !v || (v[0] === 0 && v[1] === 0 && v[2] === 0);

export function median(xs: number[]): number {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

export function quantile(xs: number[], q: number): number {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const i = (s.length - 1) * q;
  const lo = Math.floor(i), hi = Math.ceil(i);
  return s[lo] + (s[hi] - s[lo]) * (i - lo);
}

export const round = (x: number, d = 2) => Math.round(x * 10 ** d) / 10 ** d;

/** PRNG deterministico (mulberry32) per noise riproducibile. */
export function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
