import { z } from 'zod';

/**
 * Blueprint format v1: what the architect designs and the builders place. Coordinates are local
 * to the blueprint: x east, y up, z south, origin (0,0,0) at the bottom north-west corner, all
 * inside `size`. The plugin rotates it (0/90/180/270 clockwise, block states too) and moves it to
 * the anchor inside an authorized region. Every block is validated again by the plugin.
 */

export const LocalPos = z.tuple([z.number().int().min(0), z.number().int().min(0), z.number().int().min(0)]);

const PaletteKey = z.string().regex(/^[A-Za-z0-9]$/, 'one letter or digit');

export const BlueprintOp = z.discriminatedUnion('op', [
  z.object({ op: z.literal('set'), at: LocalPos, block: PaletteKey }),
  z.object({
    op: z.literal('fill'),
    from: LocalPos,
    to: LocalPos,
    block: PaletteKey,
    mode: z
      .enum(['solid', 'hollow', 'walls', 'outline'])
      .default('solid')
      .describe('hollow = box with air inside; walls = the four sides only; outline = edges only'),
  }),
  z.object({ op: z.literal('line'), from: LocalPos, to: LocalPos, block: PaletteKey }),
  z.object({
    op: z.literal('layers'),
    origin: LocalPos,
    rows: z
      .array(z.array(z.string().max(256)))
      .min(1)
      .max(256)
      .describe('rows[y][z] is a string, one character per x; "." or " " = leave as it is, "_" = air'),
  }),
]);

export const BlueprintModule = z.object({
  id: z.string().regex(/^[a-z0-9_-]{1,32}$/),
  name: z.string().max(64).optional().describe('Italian, for people, e.g. "Fondamenta"'),
  dependsOn: z.array(z.string()).default([]),
  ops: z.array(BlueprintOp).min(1).max(5000),
});

export const Blueprint = z
  .object({
    format: z.literal('agentcraft-blueprint'),
    version: z.literal(1),
    name: z.string().min(1).max(64),
    description: z.string().max(1000).optional(),
    size: z.tuple([
      z.number().int().min(1).max(256),
      z.number().int().min(1).max(256),
      z.number().int().min(1).max(256),
    ]),
    palette: z
      .record(PaletteKey, z.string().min(3).max(256))
      .describe(
        'letter -> block with optional states, e.g. "minecraft:oak_stairs[facing=north,half=bottom]"; "_" is always air',
      ),
    modules: z.array(BlueprintModule).min(1).max(32),
  })
  .superRefine((b, ctx) => {
    const ids = new Set(b.modules.map((m) => m.id));
    if (ids.size !== b.modules.length) ctx.addIssue({ code: 'custom', message: 'module ids must be unique' });
    for (const m of b.modules) {
      for (const d of m.dependsOn)
        if (!ids.has(d)) ctx.addIssue({ code: 'custom', message: `module ${m.id} depends on unknown ${d}` });
      for (const op of m.ops) {
        const keys = op.op === 'layers' ? [...new Set(op.rows.flat().join('').replace(/[. _]/g, ''))] : [op.block];
        for (const k of keys)
          if (!(k in b.palette))
            ctx.addIssue({ code: 'custom', message: `module ${m.id}: '${k}' is not in the palette` });
        const points = op.op === 'set' ? [op.at] : op.op === 'layers' ? [op.origin] : [op.from, op.to];
        for (const p of points) {
          if (p[0] >= b.size[0] || p[1] >= b.size[1] || p[2] >= b.size[2]) {
            ctx.addIssue({
              code: 'custom',
              message: `module ${m.id}: ${JSON.stringify(p)} is outside size ${JSON.stringify(b.size)}`,
            });
          }
        }
      }
    }
  });
export type Blueprint = z.infer<typeof Blueprint>;
