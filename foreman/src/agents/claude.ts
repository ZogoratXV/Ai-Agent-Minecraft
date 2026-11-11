import type * as SdkModule from '@anthropic-ai/claude-agent-sdk';
import type { Runner, TurnResult, TurnSpec } from './runner.js';
import { MCP_SERVER } from './runner.js';

type Sdk = typeof SdkModule;

/**
 * One Claude Agent SDK `query()` per turn. Isolated from any local Claude Code settings
 * (`settingSources: []`); team tools come from an in-process MCP server; every other tool call
 * goes through `canUseTool` (the AgentCraft policy). The SDK is loaded lazily so the sim backend
 * never needs it.
 */
export class ClaudeRunner implements Runner {
  private sdk?: Sdk;

  private async load(): Promise<Sdk> {
    this.sdk ??= await import('@anthropic-ai/claude-agent-sdk');
    return this.sdk;
  }

  async run(spec: TurnSpec): Promise<TurnResult> {
    const sdk = await this.load();
    const server = sdk.createSdkMcpServer({
      name: MCP_SERVER,
      version: '0.1.0',
      instructions: 'AgentCraft team tools: coordinate with the team and the admins, keep the task board up to date.',
      tools: spec.tools.map((t) =>
        sdk.tool(t.name, t.description, t.shape, async (args: Record<string, unknown>) => ({
          content: [{ type: 'text' as const, text: await t.handler(args) }],
        })),
      ),
    });
    const abort = new AbortController();
    spec.signal.addEventListener('abort', () => abort.abort(), { once: true });
    const q = sdk.query({
      prompt: spec.prompt,
      options: {
        cwd: spec.cwd,
        model: spec.model,
        effort: spec.effort,
        maxTurns: spec.maxTurns,
        ...(spec.maxBudgetUsd !== undefined ? { maxBudgetUsd: spec.maxBudgetUsd } : {}),
        ...(spec.resume ? { resume: spec.resume } : {}),
        env: spec.env,
        settingSources: [],
        systemPrompt: { type: 'preset', preset: 'claude_code', append: spec.systemPrompt },
        mcpServers: { [MCP_SERVER]: server },
        disallowedTools: ['Task', 'Agent'],
        permissionMode: 'default',
        abortController: abort,
        canUseTool: async (toolName, input, opts) => {
          if (toolName.startsWith(`mcp__${MCP_SERVER}__`) && opts.mcpServer?.source === 'sdk') {
            return { behavior: 'allow', updatedInput: input };
          }
          const p = await spec.canUseTool(toolName, input);
          return p.allow ? { behavior: 'allow', updatedInput: input } : { behavior: 'deny', message: p.message };
        },
      },
    });

    let sessionId: string | undefined;
    let text = '';
    try {
      for await (const m of q) {
        if ('session_id' in m && typeof m.session_id === 'string') sessionId = m.session_id;
        if (m.type === 'assistant') {
          for (const block of m.message.content) {
            if (block.type === 'tool_use')
              spec.onActivity({ tool: block.name, input: block.input as Record<string, unknown> });
            else if (block.type === 'text' && block.text.trim()) spec.onActivity({ text: block.text });
          }
        } else if (m.type === 'result') {
          const cost = m.total_cost_usd ?? 0;
          if (m.subtype === 'success' && !m.is_error) {
            text = m.result;
            return { ok: true, sessionId, costUsd: cost, text };
          }
          const errors = 'errors' in m ? m.errors.join('; ') : '';
          return { ok: false, sessionId, costUsd: cost, text, error: `${m.subtype}${errors ? `: ${errors}` : ''}` };
        }
      }
      return { ok: false, sessionId, costUsd: 0, text, error: 'the session ended without a result' };
    } catch (e) {
      if (spec.signal.aborted) return { ok: false, sessionId, costUsd: 0, text, error: 'aborted' };
      return { ok: false, sessionId, costUsd: 0, text, error: e instanceof Error ? e.message : String(e) };
    }
  }
}
