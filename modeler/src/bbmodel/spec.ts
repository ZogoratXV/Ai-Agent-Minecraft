import { z } from "zod";

const vec3 = z.tuple([z.number(), z.number(), z.number()]);
const faceDir = z.enum(["north", "east", "south", "west", "up", "down"]);

export const BoneSpec = z.object({
  name: z.string().describe("nome del bone/gruppo (per ModelEngine/BetterModel usa i loro prefissi/tag se servono)"),
  parent: z.string().optional().describe("nome del bone padre"),
  pivot: vec3.describe("punto di rotazione/animazione del bone"),
  rotation: vec3.optional(),
});

export const CubeSpec = z.object({
  name: z.string().describe("nome univoco del cubo"),
  bone: z.string().optional().describe("bone di appartenenza"),
  from: vec3,
  to: vec3,
  pivot: vec3.optional().describe("origine della rotazione del cubo (default: centro)"),
  rotation: vec3.optional().describe("gradi [x,y,z]; per java_block un solo asse, multipli di 22.5 fra -45 e 45"),
  inflate: z.number().optional(),
  hideFaces: z.array(faceDir).optional().describe("facce non visibili da non texturizzare (risparmiano spazio UV)"),
  uvFrom: z.string().optional().describe("riusa le UV di un altro cubo della stessa dimensione (es. gamba sinistra = destra)"),
  mirrorUv: z.boolean().optional().describe("con uvFrom: specchia la texture orizzontalmente"),
});

export const ModelSpec = z.object({
  name: z.string(),
  format: z.enum(["java_block", "bedrock", "free"]).describe("java_block: blocchi/item resource pack; bedrock/free: entità con bone (ModelEngine, BetterModel, GeckoLib)"),
  pixelsPerUnit: z.number().positive().default(1).describe("densità texel: 1 = 16px per blocco (vanilla)"),
  texture: z
    .object({ width: z.number().int().positive(), height: z.number().int().positive() })
    .optional()
    .describe("dimensione minima della texture; viene ingrandita (potenze di 2) se le UV non ci stanno"),
  bones: z.array(BoneSpec).default([]),
  cubes: z.array(CubeSpec).min(1),
  display: z
    .record(
      z.enum(["thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "gui", "ground", "fixed", "head"]),
      z.object({ rotation: vec3.optional(), translation: vec3.optional(), scale: vec3.optional() }),
    )
    .optional()
    .describe(
      "solo item (java_block): come appare in mano/GUI/a terra. Il gioco ruota/scala attorno al centro del blocco (8,8,8) e lo mette nella mano: l'impugnatura va centrata su x=8,z=8 vicino a y=8, oppure compensata con translation. Controlla sempre il render 'in mano'.",
    ),
});

export type ModelSpec = z.infer<typeof ModelSpec>;
export type CubeSpec = z.infer<typeof CubeSpec>;

const color = z.string().describe("'#rrggbb', '#rrggbbaa' o nome della palette (es. 'wood2')");
const target = z
  .object({
    cubes: z.union([z.string(), z.array(z.string())]).optional().describe("nome/i dei cubi, '*' = tutti, 'prefix*' = per prefisso"),
    faces: z.union([z.enum(["all", "sides"]), z.array(faceDir)]).optional().describe("default 'all'"),
    rect: z.tuple([z.number(), z.number(), z.number(), z.number()]).optional().describe("in alternativa: rettangolo [x,y,w,h] in pixel della texture"),
  })
  .describe("dove dipingere; le coordinate locali delle operazioni partono dall'angolo in alto a sinistra di ogni faccia");

export const PaintOp = z.discriminatedUnion("op", [
  z.object({ op: z.literal("fill"), target, color }),
  z.object({
    op: z.literal("gradient"),
    target,
    colors: z.array(color).min(2),
    direction: z.enum(["vertical", "horizontal"]).default("vertical"),
    dither: z.boolean().default(false).describe("transizione con dithering ordinato invece di bande nette"),
  }),
  z.object({
    op: z.literal("noise"),
    target,
    colors: z.array(color).min(1),
    density: z.number().min(0).max(1).default(0.25),
    seed: z.number().int().default(1),
  }),
  z.object({
    op: z.literal("shade"),
    target,
    strength: z.number().default(0.12).describe("quanto scurire in basso / schiarire in alto (0..0.4)"),
    light: z.enum(["top", "top-left", "top-right"]).default("top"),
  }),
  z.object({
    op: z.literal("border"),
    target,
    color,
    width: z.number().int().default(1),
    sides: z.array(z.enum(["top", "bottom", "left", "right"])).optional(),
  }),
  z.object({ op: z.literal("rect"), target, x: z.number().int(), y: z.number().int(), w: z.number().int(), h: z.number().int(), color }),
  z.object({ op: z.literal("line"), target, from: z.tuple([z.number().int(), z.number().int()]), to: z.tuple([z.number().int(), z.number().int()]), color }),
  z.object({
    op: z.literal("pixels"),
    target,
    points: z.array(z.tuple([z.number().int(), z.number().int()])),
    color,
  }),
  z.object({
    op: z.literal("pattern"),
    target,
    rows: z.array(z.string()).describe("pixel art ASCII: ogni carattere è un pixel, '.' o ' ' = non toccare"),
    key: z.record(z.string(), color).describe("carattere -> colore"),
    x: z.number().int().default(0),
    y: z.number().int().default(0),
    tile: z.boolean().default(false).describe("ripeti il pattern su tutta la faccia"),
    anchor: z.enum(["top-left", "center", "bottom-left", "top-right", "bottom-right"]).default("top-left"),
  }),
  z.object({ op: z.literal("erase"), target, x: z.number().int().optional(), y: z.number().int().optional(), w: z.number().int().optional(), h: z.number().int().optional() }),
]);
export type PaintOp = z.infer<typeof PaintOp>;

export const RampSpec = z.object({
  base: z.string(),
  steps: z.number().int().min(2).max(9).default(5),
  hueShift: z.number().default(6),
  contrast: z.number().default(0.12),
  saturationShift: z.number().default(0.04),
});

export const PaintProgram = z.object({
  palette: z.record(z.string(), z.string()).default({}).describe("nome -> '#hex'"),
  ramps: z.record(z.string(), RampSpec).default({}).describe("nome -> rampa; genera nome0 (più scuro) ... nomeN (più chiaro)"),
  ops: z.array(PaintOp).default([]),
});
export type PaintProgram = z.infer<typeof PaintProgram>;
