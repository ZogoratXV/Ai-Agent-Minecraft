import type { ToolInvocation } from '@agentcraft-server/protocol';
import type { ToolPort } from '../src/backend.js';
import type { ToolOutcome } from '../src/bridge.js';
import type { Clock } from '../src/clock.js';
import { realClock } from '../src/clock.js';
import { TeamState } from '../src/state.js';

export const actor = { uuid: '00000000-0000-0000-0000-000000000000', name: 'Console' };

export class FakeTools implements ToolPort {
  readonly calls: Array<{ agentId: string; call: ToolInvocation }> = [];
  async callTool(agentId: string, call: ToolInvocation): Promise<ToolOutcome> {
    this.calls.push({ agentId, call });
    return { ok: true };
  }
}

export function newState(clock: Clock = realClock): TeamState {
  return new TeamState(clock, { version: 'test', backend: 'sim', auth: 'ok', paused: false });
}
