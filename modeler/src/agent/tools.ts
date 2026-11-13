import fs from "node:fs";
import path from "node:path";
import { z } from "zod";
import { tool, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { ModelSpec, PaintOp, RampSpec } from "../bbmodel/spec";
import { listStyles, loadReference, readIndex, readProfile, styleDir } from "../analyze/style";
import { validate } from "../analyze/validate";
import { buildProject, describeModel, exportProject, loadPaint, loadSpec, projectDir, savePaint, saveSpec } from "../project";
import { renderCompare, renderInHand, renderTexture, renderViews, STANDARD_VIEWS } from "../render/render";

type Content = { type: "text"; text: string } | { type: "image"; data: string; mimeType: string };
const text = (t: string): { content: Content[] } => ({ content: [{ type: "text", text: t }] });
const img = (buf: Buffer): Content => ({ type: "image", data: buf.toString("base64"), mimeType: "image/png" });
const fail = (e: unknown) => ({ content: [{ type: "text" as const, text: `ERRORE: ${(e as Error).message}` }], isError: true });
const readOpt = (p: string) => (fs.existsSync(p) ? fs.readFileSync(p, "utf8") : "");

export function artistTools() {
  return [
    tool("list_styles", "Elenca gli stili disponibili (cartelle con riferimenti).", {}, async () =>
      text(listStyles().map((s) => `- ${s}: ${readIndex(s).length} riferimenti`).join("\n") || "Nessuno stile."),
    ),

    tool(
      "get_style",
      "Legge la guida dello stile: regole dell'autore (notes.md, priorità massima), lezioni apprese (lessons.md) e profilo statistico (STYLE.md). Va letta PRIMA di progettare.",
      { style: z.string() },
      async ({ style }) => {
        const d = styleDir(style);
        if (!fs.existsSync(d)) return fail(new Error(`Stile '${style}' inesistente`));
        return text(
          [
            "## REGOLE DELL'AUTORE (notes.md) — vincolanti",
            readOpt(path.join(d, "notes.md")) || "(vuoto)",
            "## LEZIONI APPRESE (lessons.md)",
            readOpt(path.join(d, "lessons.md")) || "(vuoto)",
            "## PROFILO AUTOMATICO (STYLE.md)",
            readOpt(path.join(d, "STYLE.md")) || "(non ancora generato: chiedere all'utente di eseguire `npm run artist -- style build <stile>`)",
          ].join("\n\n"),
        );
      },
    ),

    tool(
      "list_references",
      "Elenca i modelli di riferimento di uno stile con categoria e metriche chiave. Filtra per categoria o testo.",
      { style: z.string(), category: z.string().optional(), query: z.string().optional() },
      async ({ style, category, query }) => {
        const rows = readIndex(style)
          .filter((e) => !e.error)
          .filter((e) => !category || e.category === category)
          .filter((e) => !query || e.id.toLowerCase().includes(query.toLowerCase()));
        if (!rows.length) return text("Nessun riferimento trovato.");
        return text(
          rows
            .map((e) =>
              e.metrics
                ? `${e.id} [${e.category}] ${e.metrics.format}, ${e.metrics.cubes} cubi, size ${e.metrics.size.join("x")}, tex ${e.metrics.textures.map((t) => `${t.width}x${t.height}`).join("+")}, ${e.metrics.colors.uniqueColors} colori`
                : `${e.id} [${e.category}] immagine ${e.image?.width}x${e.image?.height}`,
            )
            .join("\n"),
        );
      },
    ),

    tool(
      "view_reference",
      "Guarda un riferimento: render 3D, texture e metriche; con structure=true anche la struttura (bone, cubi, misure) per capire COME è costruito. Studia 2-4 riferimenti simili prima di modellare.",
      { style: z.string(), id: z.string(), structure: z.boolean().default(true) },
      async ({ style, id, structure }) => {
        try {
          const { entry, model } = loadReference(style, id);
          const d = styleDir(style);
          const content: Content[] = [];
          if (entry.kind === "image") content.push(img(fs.readFileSync(path.join(d, entry.file))));
          for (const p of [entry.preview, entry.texturePreview]) if (p && fs.existsSync(path.join(d, p))) content.push(img(fs.readFileSync(path.join(d, p))));
          if (model && !entry.preview) content.push(img(await renderViews(model, { views: [STANDARD_VIEWS[4], STANDARD_VIEWS[5]], cols: 2, cell: 384 })));
          const m = entry.metrics;
          let t = `Riferimento ${entry.id} (${entry.category})\n`;
          if (m)
            t += `Densità ${m.texelDensity.median}px/u (consistenza ${Math.round(m.texelDensity.consistency * 100)}%), colori unici ${m.colors.uniqueColors}, saturazione ${m.colors.meanSaturation}, contrasto ${m.colors.contrast}, rumore ${m.colors.noise}, hue shift ${m.colors.hueShift}°, bordi scuriti ${m.colors.edgeDarkening}\nPalette: ${m.colors.palette.slice(0, 12).map((p) => p.hex).join(" ")}\n`;
          if (model && structure !== false) t += "\n" + describeModel(model);
          content.push({ type: "text", text: t });
          return { content };
        } catch (e) {
          return fail(e);
        }
      },
    ),

    tool(
      "set_model",
      "Crea o sostituisce la geometria del progetto. Le UV vengono generate automaticamente (isole a croce, densità uniforme) e la texture viene ridipinta col programma di pittura esistente. Restituisce il layout UV: per ogni cubo/faccia il rettangolo [x,y,w,h] in pixel.",
      { project: z.string(), spec: ModelSpec },
      async ({ project, spec }) => {
        try {
          const parsed = ModelSpec.parse(spec);
          saveSpec(project, parsed);
          const st = buildProject(project);
          const lay = Object.entries(st.layout)
            .map(([c, fs]) => `${c}: ${Object.entries(fs).map(([d, r]) => `${d}[${r!.x},${r!.y},${r!.w}x${r!.h}]${r!.mirrored ? "↔" : ""}`).join(" ")}`)
            .join("\n");
          return text(`OK. Texture ${st.texture.width}x${st.texture.height}.\n${[...st.warnings, ...st.paint.warnings].map((w) => "⚠ " + w).join("\n")}\nLayout UV (coordinate locali delle op di pittura partono da 0,0 in alto a sinistra di ogni faccia):\n${lay}`);
        } catch (e) {
          return fail(e);
        }
      },
    ),

    tool(
      "paint",
      "Dipinge la texture con operazioni pixel-art deterministiche. mode='replace' sostituisce tutto il programma di pittura, 'append' aggiunge op in coda. Definisci palette e rampe (con hue shift) e riferisciti ai colori per nome (es. 'wood0' = più scuro). Ordine consigliato: fill base → shade/gradient → noise leggero → dettagli (pattern/pixels/line) → highlight/bordi.",
      {
        project: z.string(),
        mode: z.enum(["replace", "append"]).default("replace"),
        palette: z.record(z.string(), z.string()).optional(),
        ramps: z.record(z.string(), RampSpec).optional(),
        ops: z.array(PaintOp),
      },
      async ({ project, mode, palette, ramps, ops }) => {
        try {
          if (!loadSpec(project)) throw new Error("Prima crea il modello con set_model");
          const prev = loadPaint(project);
          const next =
            mode === "append"
              ? { palette: { ...prev.palette, ...palette }, ramps: { ...prev.ramps, ...ramps }, ops: [...prev.ops, ...ops] }
              : { palette: palette ?? {}, ramps: ramps ?? {}, ops };
          savePaint(project, next as never);
          const st = buildProject(project);
          return text(
            `OK: ${next.ops.length} operazioni totali, ${st.paint.painted} pixel scritti.\nPalette risolta: ${Object.entries(st.palette).map(([k, v]) => `${k}=${v}`).join(" ")}\n${st.paint.warnings.map((w) => "⚠ " + w).join("\n")}`,
          );
        } catch (e) {
          return fail(e);
        }
      },
    ),

    tool(
      "render",
      "Renderizza il progetto: 8 viste (fronte=nord, lati, retro, 3/4, alto) con shading Minecraft, la texture con le isole UV e, per gli item java_block, una vista approssimata IN MANO a un giocatore 1:1 (alto 2 blocchi) con la scala/traslazione di display.thirdperson_righthand: usala per giudicare proporzioni e se la mano cade sull'impugnatura. GUARDA le immagini con occhio critico dopo ogni modifica importante.",
      { project: z.string(), texture: z.boolean().default(true) },
      async ({ project, texture }) => {
        try {
          const st = buildProject(project);
          const views = await renderViews(st.model);
          const dir = path.join(projectDir(project), "renders");
          fs.mkdirSync(dir, { recursive: true });
          fs.writeFileSync(path.join(dir, "views.png"), views);
          const content: Content[] = [img(views)];
          if (st.model.format === "java_block") {
            const hand = await renderInHand(st.model);
            fs.writeFileSync(path.join(dir, "inhand.png"), hand);
            content.push(img(hand));
          }
          if (texture !== false) {
            const t = await renderTexture(st.model);
            if (t) {
              fs.writeFileSync(path.join(dir, "texture.png"), t);
              content.push(img(t));
            }
          }
          return { content };
        } catch (e) {
          return fail(e);
        }
      },
    ),

    tool(
      "validate",
      "Controlla tecnica (densità texel, mixel, z-fighting, facce nascoste, vincoli Java) e conformità allo stile (palette, rumore, saturazione, contrasto, numero di cubi). Punta a 0 errori e nessun warning ingiustificato.",
      { project: z.string(), style: z.string().optional(), category: z.string().optional() },
      async ({ project, style, category }) => {
        try {
          const st = buildProject(project);
          const r = validate(st.model, style ? readProfile(style) : null, { category });
          const m = r.metrics;
          return text(
            `Punteggio ${r.score}/100\n` +
              (r.errors.length ? `ERRORI:\n${r.errors.map((e) => "✗ " + e).join("\n")}\n` : "") +
              (r.warnings.length ? `WARNING:\n${r.warnings.map((e) => "⚠ " + e).join("\n")}\n` : "") +
              `INFO:\n${r.info.map((e) => "· " + e).join("\n")}\n` +
              `Metriche: ${m.cubes} cubi, densità ${m.texelDensity.median}px/u, colori unici ${m.colors.uniqueColors}, sat ${m.colors.meanSaturation}, val ${m.colors.meanValue}, contrasto ${m.colors.contrast}, rumore ${m.colors.noise}, hue shift ${m.colors.hueShift}°, bordi scuriti ${m.colors.edgeDarkening}`,
          );
        } catch (e) {
          return fail(e);
        }
      },
    ),

    tool(
      "compare_with_references",
      "Mette il tuo modello accanto a 1-4 riferimenti dello stile (o a tuoi progetti precedenti con id 'project:<nome>', es. per i tier di uno stesso set) ALLA STESSA SCALA (fronte, lato e 3/4). Usalo dopo il blockout e dopo ogni passata di texture: proporzioni, spessori, sfaccettature, schema dei colori e contrasto tra materiali devono sembrare dello stesso autore. Elenca le differenze che vedi e correggile.",
      { project: z.string(), style: z.string(), ids: z.array(z.string()).min(1).max(4) },
      async ({ project, style, ids }) => {
        try {
          const st = buildProject(project);
          const items = [{ model: st.model, label: `TUO: ${project}` }];
          for (const id of ids) {
            if (id.startsWith("project:")) {
              const other = id.slice("project:".length);
              items.push({ model: buildProject(other).model, label: other });
              continue;
            }
            const { model } = loadReference(style, id);
            if (model) items.push({ model, label: id.replace(/^.*__/, "") });
          }
          const buf = await renderCompare(items);
          fs.mkdirSync(path.join(projectDir(project), "renders"), { recursive: true });
          fs.writeFileSync(path.join(projectDir(project), "renders", "compare.png"), buf);
          return { content: [img(buf)] };
        } catch (e) {
          return fail(e);
        }
      },
    ),

    tool("show_project", "Mostra spec.json e paint.json correnti del progetto.", { project: z.string() }, async ({ project }) =>
      text(`spec.json:\n${JSON.stringify(loadSpec(project))}\n\npaint.json:\n${JSON.stringify(loadPaint(project))}`),
    ),

    tool(
      "export",
      "Esporta il lavoro finito: .bbmodel (texture incorporata, apribile in Blockbench), .png e, per java_block, il modello JSON per il resource pack.",
      { project: z.string() },
      async ({ project }) => {
        try {
          return text("File esportati:\n" + exportProject(project).join("\n"));
        } catch (e) {
          return fail(e);
        }
      },
    ),

    tool(
      "record_lesson",
      "Salva una lezione generale e riutilizzabile sullo stile (es. feedback dell'utente: 'i metalli qui hanno sempre un highlight di 1px sul bordo superiore'). Non salvare dettagli specifici di un singolo modello.",
      { style: z.string(), lesson: z.string() },
      async ({ style, lesson }) => {
        const p = path.join(styleDir(style), "lessons.md");
        if (!fs.existsSync(styleDir(style))) return fail(new Error("Stile inesistente"));
        fs.appendFileSync(p, `- ${lesson.trim().replace(/\n+/g, " ")} _(${new Date().toISOString().slice(0, 10)})_\n`);
        return text("Lezione salvata.");
      },
    ),
  ];
}

export const TOOL_NAMES = ["list_styles", "get_style", "list_references", "view_reference", "set_model", "paint", "render", "validate", "compare_with_references", "show_project", "export", "record_lesson"];

export const createArtistServer = () => createSdkMcpServer({ name: "artist", version: "1.0.0", tools: artistTools() });
