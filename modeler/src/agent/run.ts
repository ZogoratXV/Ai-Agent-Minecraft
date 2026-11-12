import readline from "node:readline/promises";
import { query, type Options } from "@anthropic-ai/claude-agent-sdk";
import { ROOT } from "../analyze/style";
import { closeRenderer } from "../render/render";
import { systemPrompt } from "./prompt";
import { createArtistServer, TOOL_NAMES } from "./tools";

export interface RunOptions {
  style?: string;
  project: string;
  brief: string;
  model?: string;
  effort?: "low" | "medium" | "high" | "xhigh" | "max";
  maxTurns?: number;
  maxBudgetUsd?: number;
  interactive?: boolean;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
function logMessage(msg: any) {
  if (msg.type === "assistant") {
    for (const b of msg.message.content ?? []) {
      if (b.type === "text" && b.text.trim()) console.log(`\n🎨 ${b.text.trim()}`);
      if (b.type === "tool_use") {
        const name = String(b.name).replace("mcp__artist__", "");
        const brief = name === "set_model" ? `${b.input?.spec?.cubes?.length ?? "?"} cubi` : name === "paint" ? `${b.input?.ops?.length ?? 0} op (${b.input?.mode ?? "replace"})` : JSON.stringify(b.input ?? {}).slice(0, 90);
        console.log(`   ↳ ${name} ${brief}`);
      }
    }
  } else if (msg.type === "result") {
    console.log(`\n— fine turno: ${msg.subtype}, ${msg.num_turns} turni, costo $${(msg.total_cost_usd ?? 0).toFixed(2)}`);
  }
}

export async function runArtist(o: RunOptions) {
  const server = createArtistServer();
  const options: Options = {
    model: o.model ?? "claude-opus-5-5",
    effort: o.effort ?? "high",
    systemPrompt: systemPrompt(),
    mcpServers: { artist: server },
    tools: ["Read"],
    allowedTools: ["Read", ...TOOL_NAMES.map((t) => `mcp__artist__${t}`)],
    permissionMode: "dontAsk",
    settingSources: [],
    cwd: ROOT,
    maxTurns: o.maxTurns ?? 120,
    ...(o.maxBudgetUsd ? { maxBudgetUsd: o.maxBudgetUsd } : {}),
  };

  let prompt =
    `Progetto: "${o.project}"${o.style ? `\nStile della casa: "${o.style}" (usa get_style e i riferimenti)` : "\nNessuno stile assegnato: usa le linee guida generali e chiedi conferma delle scelte di stile nel report."}\n\nBrief:\n${o.brief}`;
  let sessionId: string | undefined;
  const rl = o.interactive ? readline.createInterface({ input: process.stdin, output: process.stdout }) : null;

  try {
    for (;;) {
      try {
        for await (const msg of query({ prompt, options: sessionId ? { ...options, resume: sessionId } : options })) {
          if ((msg as any).session_id) sessionId = (msg as any).session_id;
          logMessage(msg);
        }
      } catch (e) {
        // limite di turni/budget raggiunto o errore API: il lavoro salvato finora resta in projects/
        console.error(`\n⚠ Sessione interrotta: ${(e as Error).message}`);
        if (!rl) break;
      }
      if (!rl) break;
      const fb = (await rl.question("\n💬 Feedback per l'artista (invio vuoto per terminare): ")).trim();
      if (!fb) break;
      prompt = `Feedback del cliente: ${fb}\nApplica le modifiche, ri-valida, ri-esporta. Se il feedback vale in generale per lo stile, salvalo con record_lesson.`;
    }
  } finally {
    rl?.close();
    await closeRenderer();
  }
}
