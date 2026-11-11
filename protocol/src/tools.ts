import { z } from 'zod';
import { Box, Id, RegionRef, Uuid, Vec3, WorldPos } from './common.js';
import { Station } from './entities.js';

/**
 * World tools: the foreman asks, the plugin validates and executes. The plugin never trusts the
 * arguments: region, block blacklist, command whitelist, volume and rate limits are all checked there.
 */

export const Rotation = z.union([z.literal(0), z.literal(90), z.literal(180), z.literal(270)]);

export const BlueprintRef = z
  .record(z.string(), z.unknown())
  .describe('a blueprint document (see blueprints/blueprint.schema.json); validated again by the plugin');

export const BlockOp = z.discriminatedUnion('op', [
  z.object({ op: z.literal('set'), at: Vec3, block: z.string().max(256) }),
  z.object({
    op: z.literal('fill'),
    from: Vec3,
    to: Vec3,
    block: z.string().max(256),
    mode: z.enum(['solid', 'hollow', 'outline']).optional(),
  }),
  z.object({ op: z.literal('line'), from: Vec3, to: Vec3, block: z.string().max(256) }),
]);

const WorldInspect = z.object({
  tool: z.literal('world.inspect'),
  args: z.object({
    region: RegionRef,
    detail: z.enum(['summary', 'blocks']).describe('summary = counts per block type; blocks = palette + RLE layers'),
    includeEntities: z.boolean().optional(),
  }),
});

const WorldPreview = z.object({
  tool: z.literal('world.preview'),
  args: z.object({
    blueprint: BlueprintRef,
    anchor: WorldPos,
    rotation: Rotation,
    viewers: z.array(Uuid).min(1).max(16).describe('only these players see the preview'),
    ttlSeconds: z.number().int().min(10).max(3600),
  }),
});

const WorldPlace = z.object({
  tool: z.literal('world.place'),
  args: z.object({
    jobId: Id,
    approvalId: Id.describe('id of the answered `build` decision; the plugin verifies it'),
    region: z.string().min(1).max(64).describe('authorized region the whole job must fit in'),
    anchor: WorldPos,
    rotation: Rotation,
    blueprint: BlueprintRef.optional(),
    module: z.string().max(64).optional().describe('build only this blueprint module'),
    ops: z.array(BlockOp).max(10000).optional().describe('raw operations, alternative to blueprint'),
  }),
});

const WorldUndo = z.object({ tool: z.literal('world.undo'), args: z.object({ jobId: Id }) });

const ServerCommand = z.object({
  tool: z.literal('server.command'),
  args: z.object({
    command: z.string().min(1).max(256).describe('without the leading slash'),
    as: z.enum(['console', 'fakeplayer']),
  }),
});

const ServerLogs = z.object({
  tool: z.literal('server.logs'),
  args: z.object({
    sinceTs: z.number().int().nonnegative().optional(),
    filter: z.string().max(200).optional().describe('case-insensitive substring'),
    limit: z.number().int().min(1).max(500),
  }),
});

const ServerMetrics = z.object({ tool: z.literal('server.metrics'), args: z.object({}) });

export const SimAction = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('command'), command: z.string().min(1).max(256) }),
  z.object({ kind: z.literal('chat'), text: z.string().min(1).max(256) }),
  z.object({ kind: z.literal('move'), to: WorldPos }),
  z.object({ kind: z.literal('interact'), at: WorldPos, hand: z.enum(['left', 'right']) }),
  z.object({ kind: z.literal('click_slot'), slot: z.number().int().min(0).max(89) }),
  z.object({ kind: z.literal('wait'), ticks: z.number().int().min(1).max(1200) }),
]);

const PlayerSimulate = z.object({
  tool: z.literal('player.simulate'),
  args: z.object({ actions: z.array(SimAction).min(1).max(200) }).describe('staging only'),
});

const BodyMove = z.object({
  tool: z.literal('body.move'),
  args: z.object({
    to: z.union([z.object({ station: Station }), z.object({ pos: WorldPos }), z.object({ followPlayer: Uuid })]),
  }),
});

const BodySay = z.object({
  tool: z.literal('body.say'),
  args: z.object({ text: z.string().min(1).max(256) }),
});

export const ToolInvocation = z.discriminatedUnion('tool', [
  WorldInspect,
  WorldPreview,
  WorldPlace,
  WorldUndo,
  ServerCommand,
  ServerLogs,
  ServerMetrics,
  PlayerSimulate,
  BodyMove,
  BodySay,
]);
export type ToolInvocation = z.infer<typeof ToolInvocation>;
export type ToolName = ToolInvocation['tool'];

export const TOOL_NAMES = ToolInvocation.options.map((o) => o.shape.tool.value) as ToolName[];

/** Tools refused by the plugin when its configured role is `production`. */
export const STAGING_ONLY_TOOLS: readonly ToolName[] = ['player.simulate'];

export const ToolErrorCode = z.enum([
  'forbidden',
  'out_of_region',
  'blacklisted_block',
  'too_large',
  'rate_limited',
  'not_staging',
  'needs_approval',
  'invalid_args',
  'timeout',
  'busy',
  'not_found',
  'internal',
]);

export const ToolError = z.object({
  code: ToolErrorCode,
  message: z.string().max(1000),
});

export const JobState = z.enum(['queued', 'running', 'paused', 'done', 'failed', 'cancelled', 'undone']);

export { Box };
