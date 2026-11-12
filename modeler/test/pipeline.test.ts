import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterAll, describe, expect, it } from "vitest";

const HOME = fs.mkdtempSync(path.join(os.tmpdir(), "artist-"));
process.env.ARTIST_HOME = HOME;

const { buildModel } = await import("../src/bbmodel/build");
const { ModelSpec, PaintProgram } = await import("../src/bbmodel/spec");
const { paint } = await import("../src/texture/paint");
const { writeBBModel, writeJavaModel } = await import("../src/bbmodel/write");
const { readBBModel, readJavaModel } = await import("../src/bbmodel/read");
const { allCubes } = await import("../src/bbmodel/model");
const { validate } = await import("../src/analyze/validate");
const { buildStyle, initStyle, styleDir, readProfile } = await import("../src/analyze/style");
const { artistTools } = await import("../src/agent/tools");
const { closeRenderer } = await import("../src/render/render");
const { makeRamp, rgbToHsv, parseHex } = await import("../src/core/color");

afterAll(async () => {
  await closeRenderer();
  fs.rmSync(HOME, { recursive: true, force: true });
});

const lantern = () =>
  ModelSpec.parse({
    name: "lantern",
    format: "java_block",
    cubes: [
      { name: "base", from: [4, 0, 4], to: [12, 2, 12] },
      { name: "glass", from: [5, 2, 5], to: [11, 9, 11], hideFaces: ["up", "down"] },
      { name: "cap", from: [4, 9, 4], to: [12, 11, 12] },
    ],
  });

const lanternPaint = () =>
  PaintProgram.parse({
    ramps: { iron: { base: "#5a5f6b" }, glow: { base: "#ffb24a", steps: 3 } },
    ops: [
      { op: "fill", target: { cubes: "*" }, color: "iron2" },
      { op: "shade", target: { cubes: "*", faces: "sides" } },
      { op: "fill", target: { cubes: "glass" }, color: "glow1" },
      { op: "border", target: { cubes: "glass" }, color: "iron0" },
    ],
  });

describe("build + paint", () => {
  it("genera UV a densità uniforme e dipinge tutte le facce", () => {
    const b = buildModel(lantern());
    expect(b.warnings).toEqual([]);
    expect(b.layout.glass.up).toBeUndefined();
    expect(b.layout.glass.north).toMatchObject({ w: 6, h: 7 });
    const r = paint(b.model.textures[0].img, b.layout, lanternPaint());
    expect(r.warnings).toEqual([]);
    const v = validate(b.model, null);
    expect(v.errors).toEqual([]);
    expect(v.metrics.texelDensity.median).toBe(1);
    expect(v.metrics.texelDensity.consistency).toBe(1);
  });

  it("uvFrom + mirror riusa le stesse UV specchiate", () => {
    const b = buildModel(
      ModelSpec.parse({
        name: "legs",
        format: "bedrock",
        bones: [{ name: "body", pivot: [0, 12, 0] }],
        cubes: [
          { name: "leg_r", bone: "body", from: [-4, 0, -2], to: [0, 12, 2] },
          { name: "leg_l", bone: "body", from: [0, 0, -2], to: [4, 12, 2], uvFrom: "leg_r", mirrorUv: true },
        ],
      }),
    );
    const cubes = allCubes(b.model.outline);
    const r = cubes.find((c) => c.name === "leg_r")!.faces.north!.uv;
    const l = cubes.find((c) => c.name === "leg_l")!.faces.north!.uv;
    expect(l).toEqual([r[2], r[1], r[0], r[3]]);
  });

  it("segnala misure che producono texel stirati", () => {
    const b = buildModel(ModelSpec.parse({ name: "x", format: "free", cubes: [{ name: "a", from: [0, 0, 0], to: [2.5, 2, 2] }] }));
    expect(b.warnings.join()).toMatch(/non intero/);
  });

  it("rampe con hue shift: ombre più fredde, luci più calde", () => {
    const ramp = makeRamp("#a0602a", { steps: 5, hueShift: 8 });
    const [hd] = rgbToHsv(parseHex(ramp[0])), [hl] = rgbToHsv(parseHex(ramp[4])), [hb] = rgbToHsv(parseHex(ramp[2]));
    expect(rgbToHsv(parseHex(ramp[0]))[2]).toBeLessThan(rgbToHsv(parseHex(ramp[4]))[2]);
    expect(Math.abs(hl - 60)).toBeLessThan(Math.abs(hb - 60));
    expect(hd).not.toBeCloseTo(hb, 0);
  });
});

describe("formati", () => {
  it("round-trip .bbmodel", () => {
    const b = buildModel(lantern());
    paint(b.model.textures[0].img, b.layout, lanternPaint());
    const back = readBBModel(JSON.parse(JSON.stringify(writeBBModel(b.model))));
    expect(allCubes(back.outline).map((c) => c.faces.north?.uv)).toEqual(allCubes(b.model.outline).map((c) => c.faces.north?.uv));
    expect(Buffer.from(back.textures[0].img.data).equals(Buffer.from(b.model.textures[0].img.data))).toBe(true);
  });

  it("round-trip modello Java con texture su disco", () => {
    const b = buildModel(lantern());
    paint(b.model.textures[0].img, b.layout, lanternPaint());
    const dir = fs.mkdtempSync(path.join(HOME, "java-"));
    fs.writeFileSync(path.join(dir, "lantern.png"), b.model.textures[0].img.toPng());
    const json = writeJavaModel(b.model, "minecraft:block/lantern");
    const back = readJavaModel(JSON.parse(JSON.stringify(json)), "lantern", [dir]);
    expect(back.textures).toHaveLength(1);
    expect(allCubes(back.outline)).toHaveLength(3);
    expect(validate(back, null).metrics.texelDensity.median).toBe(1);
  });

  it("legge il formato 5.x con array groups e il box UV", () => {
    const json = {
      meta: { format_version: "5.0", model_format: "bedrock", box_uv: true },
      resolution: { width: 64, height: 64 },
      elements: [{ name: "head", type: "cube", uuid: "c1", from: [-4, 24, -4], to: [4, 32, 4], origin: [0, 24, 0], box_uv: true, uv_offset: [0, 0] }],
      groups: [{ uuid: "g1", name: "head", origin: [0, 24, 0], rotation: [0, 0, 0] }],
      outliner: [{ uuid: "g1", isOpen: true, children: ["c1"] }],
      textures: [],
    };
    const m = readBBModel(json);
    expect(m.outline[0]).toMatchObject({ kind: "group", group: { name: "head" } });
    const c = allCubes(m.outline)[0];
    expect(c.faces.north!.uv).toEqual([8, 8, 16, 16]);
    expect(c.faces.east!.uv).toEqual([0, 8, 8, 16]);
  });
});

describe("validazione", () => {
  it("rileva z-fighting e rotazioni non valide in Java", () => {
    const b = buildModel(
      ModelSpec.parse({
        name: "bad",
        format: "java_block",
        cubes: [
          { name: "a", from: [0, 0, 0], to: [8, 8, 8] },
          { name: "b", from: [0, 0, 0], to: [4, 4, 4] },
          { name: "c", from: [8, 0, 0], to: [10, 2, 2], rotation: [10, 20, 0] },
        ],
      }),
    );
    paint(b.model.textures[0].img, b.layout, PaintProgram.parse({ ops: [{ op: "fill", target: { cubes: "*" }, color: "#808080" }] }));
    const r = validate(b.model, null);
    expect(r.warnings.join()).toMatch(/Z-fighting/);
    expect(r.errors.join()).toMatch(/un solo asse/);
  });
});

describe("stile + strumenti dell'agente", () => {
  it("costruisce il profilo dai riferimenti e gli strumenti funzionano end-to-end", async () => {
    initStyle("demo");
    const refDir = path.join(styleDir("demo"), "references", "arredi");
    fs.mkdirSync(refDir, { recursive: true });
    const b = buildModel(lantern());
    paint(b.model.textures[0].img, b.layout, lanternPaint());
    fs.writeFileSync(path.join(refDir, "lanterna.bbmodel"), JSON.stringify(writeBBModel(b.model)));
    fs.writeFileSync(path.join(styleDir("demo"), "references", "palette.png"), b.model.textures[0].img.toPng());

    const profile = await buildStyle("demo");
    expect(profile.references).toBe(1);
    expect(profile.images).toBe(1);
    expect(profile.categories).toMatchObject({ arredi: 1 });
    expect(profile.texelDensity.median).toBe(1);
    expect(fs.readFileSync(path.join(styleDir("demo"), "STYLE.md"), "utf8")).toMatch(/Densità texel/);
    expect(fs.existsSync(path.join(styleDir("demo"), "previews", "arredi__lanterna.png"))).toBe(true);

    const tools = Object.fromEntries(artistTools().map((t) => [t.name, t]));
    const call = async (name: string, args: unknown) => (await tools[name].handler(args as never, {})) as { content: { type: string; text?: string }[]; isError?: boolean };

    expect((await call("get_style", { style: "demo" })).content[0].text).toMatch(/REGOLE DELL'AUTORE/);
    expect((await call("list_references", { style: "demo" })).content[0].text).toMatch(/arredi__lanterna/);
    const view = await call("view_reference", { style: "demo", id: "arredi__lanterna", structure: true });
    expect(view.content.filter((c) => c.type === "image").length).toBeGreaterThan(0);
    expect(view.content.at(-1)!.text).toMatch(/■ glass size\[6,7,6\]/);

    const set = await call("set_model", { project: "test_lantern", spec: lantern() });
    expect(set.isError).toBeFalsy();
    expect(set.content[0].text).toMatch(/glass: east/);
    const p = await call("paint", { project: "test_lantern", mode: "replace", ...lanternPaint() });
    expect(p.isError).toBeFalsy();
    const bad = await call("paint", { project: "test_lantern", mode: "append", ops: [{ op: "fill", target: { cubes: "*" }, color: "nonesiste" }] });
    expect(bad.isError).toBe(true);
    await call("paint", { project: "test_lantern", mode: "replace", ...lanternPaint() });

    const rend = await call("render", { project: "test_lantern" });
    expect(rend.content.filter((c) => c.type === "image")).toHaveLength(2);
    const val = await call("validate", { project: "test_lantern", style: "demo" });
    expect(val.content[0].text).toMatch(/Punteggio 100\/100/);
    const exp = await call("export", { project: "test_lantern" });
    expect(exp.content[0].text).toMatch(/lantern\.bbmodel/);
    expect(exp.content[0].text).toMatch(/lantern\.json/);
    await call("record_lesson", { style: "demo", lesson: "Le lanterne hanno sempre il vetro caldo." });
    expect(fs.readFileSync(path.join(styleDir("demo"), "lessons.md"), "utf8")).toMatch(/vetro caldo/);
    expect(readProfile("demo")).not.toBeNull();
  }, 120000);
});
