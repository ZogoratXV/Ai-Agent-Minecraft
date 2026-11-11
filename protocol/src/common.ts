import { z } from 'zod';

/** Bumped only on breaking changes. Both sides refuse a peer with a different major version. */
export const PROTOCOL_VERSION = 1 as const;

export const Id = z
  .string()
  .min(1)
  .max(64)
  .regex(/^[A-Za-z0-9._:-]+$/)
  .describe('stable identifier, e.g. "t3", "d4", "bussola"');
export const Ts = z.number().int().nonnegative().describe('epoch milliseconds');
export const Color = z
  .string()
  .regex(/^#[0-9a-fA-F]{6}$/)
  .describe('"#RRGGBB"');
export const Uuid = z
  .string()
  .regex(/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/)
  .describe('Minecraft player UUID');

/** Block coordinates inside one world. */
export const Vec3 = z
  .tuple([z.number().int(), z.number().int(), z.number().int()])
  .describe('[x, y, z] block coordinates');

export const WorldPos = z.object({
  world: z.string().min(1).max(64),
  x: z.number(),
  y: z.number(),
  z: z.number(),
});

export const Box = z
  .object({
    world: z.string().min(1).max(64),
    min: Vec3,
    max: Vec3,
  })
  .describe('axis-aligned box, both corners inclusive');

export const RegionRef = z
  .union([z.object({ region: z.string().min(1).max(64).describe('name of an authorized region') }), Box])
  .describe('a named authorized region, or an explicit box (still checked against authorized regions)');

/** Common envelope fields. Every message is one JSON object per WebSocket text frame. */
export function message<T extends string, S extends z.ZodRawShape>(type: T, description: string, shape: S) {
  return z
    .object({
      v: z.literal(PROTOCOL_VERSION),
      type: z.literal(type),
      id: Id.optional().describe('correlation id; messages that carry one are answered with `ack`'),
    })
    .extend(shape)
    .describe(description);
}
