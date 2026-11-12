# Blockbench Artist

Un agente AI che lavora come un artista Blockbench professionista per il tuo server: modella e
texturizza blocchi, item, arredi e mob nello **stile che gli insegni tu** con i tuoi modelli di riferimento.

## Come "impara" (in breve, e onestamente)

Il modello AI non viene riaddestrato. L'agente impara il tuo stile come un artista assunto da poco:

1. **Studia i riferimenti** che gli dai. `style build` misura ogni modello: densità dei pixel, dimensioni
   delle texture, numero di cubi per categoria, palette, saturazione, contrasto, rumore, hue shifting, bordi.
   Ne ricava un profilo (`profile.json`) e una guida leggibile (`STYLE.md`). Mentre lavora, l'agente apre i
   riferimenti più simili e ne guarda render, texture e struttura (bone, cubi, misure).
2. **Segue le tue regole scritte** (`notes.md`), che hanno la precedenza su tutto.
3. **Accumula esperienza.** Il feedback che gli dai a fine lavoro, se vale in generale, finisce in
   `lessons.md` e viene riletto a ogni nuovo incarico.
4. **Si autocritica.** Dopo ogni modifica renderizza il modello da 8 angolazioni e guarda le immagini. Poi esegue un
   validatore tecnico (mixel, z-fighting, vincoli Java) e di stile (palette, rumore, contrasto…) e corregge.

Più riferimenti buoni e coerenti gli dai, più il profilo è preciso.

## Installazione

Richiede Node 22+ e un browser Chromium per Playwright (`npx playwright install chromium` se non è già presente).

```bash
cd modeler
npm install
export ANTHROPIC_API_KEY=...   # oppure un login Claude Code già configurato
```

## Uso

```bash
# 1. crea uno stile
npm run artist -- style init medievale

# 2. copia i riferimenti in styles/medievale/references/
#    sottocartelle = categorie (es. armi/, mob/, arredi/), usate per confrontare il numero di cubi
#    e scrivi le regole in styles/medievale/notes.md

# 3. analizza i riferimenti (rifallo ogni volta che ne aggiungi)
npm run artist -- style build medievale
npm run artist -- style show medievale

# 4. commissiona un lavoro
npm run artist -- make medievale spada_runica "Spada a due mani con rune azzurre sulla lama, item in mano, stile delle armi esistenti"
#    alla fine l'artista consegna e chiede feedback; rispondi per farlo correggere (invio vuoto per chiudere)

# utilità
npm run artist -- render spada_runica            # projects/spada_runica/renders/
npm run artist -- validate spada_runica --style medievale
npm run artist -- export spada_runica            # .bbmodel (+ .json per java_block)
npm run artist -- lesson medievale "Le lame hanno sempre un filo chiaro di 1px sul bordo tagliente"
```

Opzioni di `make`: `--model` (default `claude-opus-5-5`), `--effort low|medium|high|xhigh|max` (default `high`),
`--budget <usd>` (tetto di spesa per la sessione), `--turns <n>`, `--once` (niente richiesta di feedback).

Il risultato è in `projects/<progetto>/export/`. Il `.bbmodel` si apre direttamente in Blockbench (texture
incorporata) per eventuali ritocchi a mano. Esempio già pronto: `projects/demo_crate/`.

## Riferimenti supportati

| File | Cosa ne ricava |
|---|---|
| `.bbmodel` (Blockbench 4.x e 5.x, cubi) | tutto: struttura, misure, UV, texture, colori, render |
| modello Java `.json` + `.png` nella stessa cartella (o in `textures/`) | come sopra |
| immagini `.png` (screenshot, concept, texture sciolte) | palette e riferimento visivo |
| mesh (OBJ/glTF importati in Blockbench) | ignorate nell'analisi, per ora |

### Da dove prendere i riferimenti

- **I tuoi modelli**, o quelli fatti su commissione per il tuo server: sono la fonte migliore.
- **Modelli acquistati** (es. MCModels e altri marketplace): usali solo se la licenza che hai comprato lo
  consente, e solo in locale. Non vanno ridistribuiti né messi in un repository pubblico.
- **Sketchfab**: solo modelli scaricabili con licenza aperta (CC0/CC-BY…), tramite il download ufficiale.
  Conserva l'attribuzione (es. un `CREDITS.md` nella cartella references). Molti modelli Sketchfab sono mesh
  low-poly e non cubi Blockbench: per quelli usa uno screenshot `.png` come riferimento visivo.
- Non fare scraping massivo dei siti: violi i loro termini e i diritti degli autori.

L'agente è istruito a usare i riferimenti per imparare lo stile, non a copiare asset esistenti.

## Formati di output

- `java_block`: blocchi e item per il resource pack Java (coordinate -16..32, rotazioni ±22.5/45 su un asse).
  Export `.json` + `.png` + `.bbmodel`.
- `bedrock` / `free`: entità con bone e pivot, pronte da animare in Blockbench e da usare con
  ModelEngine, BetterModel o GeckoLib (verifica le convenzioni sui nomi dei bone del plugin che usi).

## Struttura

```
modeler/
  knowledge/pro-guidelines.md   regole professionali generali (modificabili, entrano nel prompt)
  styles/<stile>/
    references/                 i tuoi modelli (sottocartelle = categorie)
    notes.md                    le tue regole: vincolanti
    lessons.md                  lezioni dal feedback (crescono nel tempo)
    profile.json, STYLE.md      profilo misurato (generato)
    index.json, previews/       metriche e render di ogni riferimento (generati)
  projects/<progetto>/
    spec.json                   geometria (bone + cubi) scritta dall'agente
    paint.json                  "programma" della texture (palette, rampe, operazioni pixel-art)
    renders/, export/
  src/
    bbmodel/   lettura/scrittura .bbmodel e Java JSON, spec, UV automatiche
    texture/   pittura pixel-art deterministica (rampe con hue shift, dithering, pattern ASCII…)
    render/    renderer three.js headless (shading stile Minecraft) + foglio UV
    analyze/   metriche, profilo di stile, validatore
    agent/     tool MCP, prompt di sistema, runner (Claude Agent SDK)
```

La geometria usa UV automatiche: isole "a croce", densità uniforme, potenze di 2. La texture è un programma
di operazioni, quindi quando la geometria cambia viene ridipinta da sola, senza rompersi.

## Test

```bash
npm test          # pipeline completa: build, pittura, render, round-trip formati, stile, strumenti dell'agente
npm run typecheck
```

## Prossimi passi possibili

- Animazioni (idle/walk/attack) per entità con bone.
- Plugin Blockbench con server MCP, per far lavorare l'agente dal vivo dentro Blockbench aperto.
- Analisi delle mesh e import dei glTF.
- Display settings degli item (mano, GUI, terra) e generazione del resource pack completo.
- Integrazione in AgentCraft (`../PROMPT.md`) come ruolo `modeler`.
