import fs from "node:fs";
import path from "node:path";
import { buildStyle, initStyle, listStyles, readProfile, styleDir, ROOT } from "./analyze/style";
import { validate } from "./analyze/validate";
import { buildProject, exportProject, projectDir } from "./project";
import { closeRenderer, renderTexture, renderViews } from "./render/render";

const HELP = `blockbench-artist — agente artista Blockbench

Uso: npm run artist -- <comando> [argomenti]

  style init <stile>              crea styles/<stile>/ (references/, notes.md, lessons.md)
  style build <stile> [--fast]    analizza i riferimenti → profile.json, STYLE.md, previews/ (--fast: senza render)
  style show <stile>              mostra regole e profilo
  styles                          elenca gli stili

  make <stile|-> <progetto> "<brief>" [--model id] [--effort low|medium|high|xhigh|max]
                                  [--budget usd] [--turns n] [--once]
                                  fa lavorare l'artista; alla fine chiede feedback (salvo --once)

  render <progetto>               renderizza projects/<progetto>/renders/*.png
  validate <progetto> [--style s] report tecnico e di stile
  export <progetto>               esporta .bbmodel / .png / .json
  lesson <stile> "<testo>"        aggiunge una lezione a lessons.md
`;

function flag(args: string[], name: string): string | undefined {
  const i = args.indexOf(`--${name}`);
  if (i < 0) return undefined;
  const v = args[i + 1];
  args.splice(i, v && !v.startsWith("--") ? 2 : 1);
  return v && !v.startsWith("--") ? v : "true";
}

async function main() {
  const args = process.argv.slice(2);
  const cmd = args.shift();
  switch (cmd) {
    case "style": {
      const sub = args.shift(), style = args.shift();
      if (!style) throw new Error("Specifica il nome dello stile");
      if (sub === "init") {
        initStyle(style);
        console.log(`Creato ${path.relative(ROOT, styleDir(style))}/. Metti i modelli in references/ (sottocartelle = categorie) e scrivi le regole in notes.md.`);
      } else if (sub === "build") {
        const fast = flag(args, "fast");
        const p = await buildStyle(style, { previews: !fast, log: console.log });
        console.log(`\nProfilo: ${p.references} modelli, ${p.images} immagini → ${path.relative(ROOT, styleDir(style))}/STYLE.md`);
      } else if (sub === "show") {
        for (const f of ["notes.md", "lessons.md", "STYLE.md"]) {
          const p = path.join(styleDir(style), f);
          if (fs.existsSync(p)) console.log(fs.readFileSync(p, "utf8"));
        }
      } else throw new Error("Sottocomando style sconosciuto");
      break;
    }
    case "styles":
      console.log(listStyles().join("\n") || "Nessuno stile.");
      break;
    case "make": {
      const model = flag(args, "model"), effort = flag(args, "effort"), budget = flag(args, "budget"), turns = flag(args, "turns"), once = flag(args, "once");
      const [style, project, ...brief] = args;
      if (!style || !project || !brief.length) throw new Error('Uso: make <stile|-> <progetto> "<brief>"');
      const { runArtist } = await import("./agent/run");
      await runArtist({
        style: style === "-" ? undefined : style,
        project,
        brief: brief.join(" "),
        model,
        effort: effort as never,
        maxBudgetUsd: budget ? +budget : undefined,
        maxTurns: turns ? +turns : undefined,
        interactive: !once && process.stdin.isTTY,
      });
      break;
    }
    case "render": {
      const project = args[0];
      const st = buildProject(project);
      const dir = path.join(projectDir(project), "renders");
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, "views.png"), await renderViews(st.model));
      const t = await renderTexture(st.model);
      if (t) fs.writeFileSync(path.join(dir, "texture.png"), t);
      console.log(`Render in ${path.relative(ROOT, dir)}/`);
      break;
    }
    case "validate": {
      const style = flag(args, "style");
      const st = buildProject(args[0]);
      const r = validate(st.model, style ? readProfile(style) : null);
      console.log(JSON.stringify({ score: r.score, errors: r.errors, warnings: r.warnings, info: r.info }, null, 2));
      break;
    }
    case "export":
      console.log(exportProject(args[0]).join("\n"));
      break;
    case "lesson": {
      const [style, ...t] = args;
      fs.appendFileSync(path.join(styleDir(style), "lessons.md"), `- ${t.join(" ")} _(${new Date().toISOString().slice(0, 10)})_\n`);
      console.log("Lezione salvata.");
      break;
    }
    default:
      console.log(HELP);
  }
}

main()
  .catch((e) => {
    console.error("Errore:", e instanceof Error ? e.message : e);
    process.exitCode = 1;
  })
  .finally(() => closeRenderer());
