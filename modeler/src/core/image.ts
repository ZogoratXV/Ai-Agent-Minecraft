import { PNG } from "pngjs";
import type { RGBA } from "./color";

/** Immagine RGBA in memoria, coordinate in pixel con origine in alto a sinistra. */
export class Img {
  readonly data: Uint8ClampedArray;
  constructor(readonly width: number, readonly height: number, data?: Uint8ClampedArray) {
    this.data = data ?? new Uint8ClampedArray(width * height * 4);
  }

  static fromPng(buf: Buffer): Img {
    const png = PNG.sync.read(buf);
    return new Img(png.width, png.height, new Uint8ClampedArray(png.data));
  }

  static fromDataUri(uri: string): Img {
    const m = /^data:image\/png;base64,(.*)$/s.exec(uri);
    if (!m) throw new Error("Texture non PNG/base64");
    return Img.fromPng(Buffer.from(m[1], "base64"));
  }

  toPng(): Buffer {
    const png = new PNG({ width: this.width, height: this.height });
    png.data = Buffer.from(this.data);
    return PNG.sync.write(png);
  }

  toDataUri(): string {
    return "data:image/png;base64," + this.toPng().toString("base64");
  }

  clone(): Img {
    return new Img(this.width, this.height, new Uint8ClampedArray(this.data));
  }

  inside(x: number, y: number) {
    return x >= 0 && y >= 0 && x < this.width && y < this.height;
  }

  get(x: number, y: number): RGBA {
    const i = (y * this.width + x) * 4;
    return [this.data[i], this.data[i + 1], this.data[i + 2], this.data[i + 3]];
  }

  set(x: number, y: number, c: RGBA) {
    if (!this.inside(x, y)) return;
    const i = (y * this.width + x) * 4;
    this.data[i] = c[0];
    this.data[i + 1] = c[1];
    this.data[i + 2] = c[2];
    this.data[i + 3] = c[3];
  }

  /** Composita `c` sopra il pixel esistente (alpha "over"). */
  blend(x: number, y: number, c: RGBA) {
    if (!this.inside(x, y)) return;
    if (c[3] >= 255) return this.set(x, y, c);
    const d = this.get(x, y), a = c[3] / 255, da = d[3] / 255, oa = a + da * (1 - a);
    if (oa <= 0) return this.set(x, y, [0, 0, 0, 0]);
    const mix = (i: number) => (c[i] * a + d[i] * da * (1 - a)) / oa;
    this.set(x, y, [mix(0), mix(1), mix(2), oa * 255].map(Math.round) as RGBA);
  }
}
