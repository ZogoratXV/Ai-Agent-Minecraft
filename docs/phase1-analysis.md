# AgentCraft Server — Fase 1: analisi e piano

Stato: **proposta, in attesa di approvazione**. Nessun codice è stato scritto.
Data: 2026-11-11.

---

## 1. Cosa ho studiato di agentcraft (MIT)

Repository: https://github.com/blendi-remade/agentcraft (MIT, © 2026 AgentCraft contributors).
Letti: `README.md`, `foreman/README.md`, `docs/protocol.md`, `foreman/src/*` (struttura e moduli chiave).

| Concetto agentcraft                                                                                                                                                                       | Dove                                         | Cosa riprendiamo                                                                                         | Cosa cambia per noi                                                                                                               |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Foreman = unico proprietario dello stato; il client (mod) è solo vista e controlli                                                                                                        | `foreman.ts`, `store.ts`                     | Identico: il plugin non tiene stato di business, solo stato "fisico" (NPC, job nel mondo, snapshot undo) | Il plugin **esegue** anche azioni (world tools), quindi il protocollo diventa bidirezionale con RPC                               |
| WebSocket `ws://127.0.0.1:7878`, envelope `{v, type, id?, ...}`, `hello → snapshot → upsert`, `ack {re, ok}`                                                                              | `server.ts`, `protocol.ts`                   | Envelope, snapshot+upsert, ack, rifiuto di `Origin` e di Host non locali                                 | Aggiungiamo **token condiviso** nell'handshake e una famiglia `tool.call`/`tool.result` (foreman→plugin)                          |
| Protocollo definito in zod, doc generata e verificata in CI (`npm run check`)                                                                                                             | `protocol.ts`, `scripts/gen-protocol-doc.ts` | Stesso approccio, ma in un package `/protocol` separato con export JSON Schema                           | Il lato Java viene verificato contro **fixture JSON generate** (contract test)                                                    |
| TaskGraph (`todo/doing/review/done/blocked/cancelled`, deps, priority)                                                                                                                    | `taskgraph.ts`                               | Stessi stati e semantica                                                                                 | Task con `kind` (code, qa, build, analysis, generic)                                                                              |
| DecisionQueue (`question/permission/merge`)                                                                                                                                               | `decisions.ts`                               | Stesso modello, opzioni etichettate                                                                      | Nuovi tipi: `deploy`, `build` (approvazione costruzione), `config` (proposta analyst)                                             |
| Worktree per task `agentcraft/<agente>/<task>`, merge solo su approvazione, checkout sporco ⇒ merge rifiutato                                                                             | `repos.ts`                                   | Identico                                                                                                 | —                                                                                                                                 |
| Git safety a livello di git (`GIT_ALLOW_PROTOCOL`, `protocol.allow=never`, `pushInsteadOf`, no signing, `GIT_CEILING_DIRECTORIES`)                                                        | `gitsafety.ts`                               | Stessa tecnica (reimplementata, con citazione)                                                           | —                                                                                                                                 |
| Policy shell (parser bash, allow/ask/deny, "always allow" con scope)                                                                                                                      | `policy.ts` (2150 righe)                     | Il **principio**: whitelist + richiesta in gioco + `git push` sempre negato                              | Versione più semplice e conservativa all'inizio (allowlist esplicita, tutto il resto chiede); la renderemo più fine solo se serve |
| Claude Agent SDK: un `query()` per turno, `settingSources: []`, MCP in-process (`createSdkMcpServer`), ogni tool passa da `canUseTool`, `resume` per sessione, `maxTurns`, `maxBudgetUsd` | `agents/claude/engine.ts`, `tools.ts`        | Stesso schema                                                                                            | Più ruoli (6), tool "mondo" inoltrati al plugin                                                                                   |
| Backend `sim` deterministico con git reale                                                                                                                                                | `agents/sim/`                                | Stesso concetto                                                                                          | Lo scenario sim pilota anche NPC e world tools (senza API)                                                                        |
| Memoria markdown shared + per agente                                                                                                                                                      | `memory.ts`                                  | Identico (`memory/shared/`, `memory/agents/<id>/`)                                                       | Mostrata in gioco come libri                                                                                                      |

Licenza: riprendiamo idee e architettura, non copiamo file. Dove un modulo segue da vicino l'originale (es. git safety), lo
diremo in un commento di testa. `NOTICE` e README citeranno agentcraft e la licenza MIT.

---

## 2. Versioni verificate

Verificate leggendo direttamente i branch `ver/26.2` di Purpur e Paper su GitHub e Maven Central.

| Cosa                       | Valore                                                                                                                                                        | Fonte                                          |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| Purpur                     | branch `ver/26.2`, `mcVersion=26.2`, `apiVersion=26.2`, `channel=STABLE`, ultimo sync upstream 2026-09-11                                                     | `PurpurMC/Purpur@ver/26.2` `gradle.properties` |
| Paper                      | branch `ver/26.2`, `apiVersion=26.2`, `STABLE`                                                                                                                | `PaperMC/Paper@ver/26.2`                       |
| Java                       | **25** (toolchain `JavaLanguageVersion.of(25)`, `options.release = 25`)                                                                                       | build di Purpur e Paper                        |
| Formato versione artefatti | `26.2.build.<N>-stable` (es. `purpur-api:26.2.build.<N>-stable`)                                                                                              | `settings.gradle.kts` di entrambi              |
| Coordinate API             | `org.purpurmc.purpur:purpur-api` su `https://repo.purpurmc.org/snapshots`; `io.papermc.paper:paper-api` su `https://repo.papermc.io/repository/maven-public/` | build di Purpur                                |
| paperweight                | `2.0.0-beta.21` (usato da Purpur/Paper; serve solo se usiamo userdev)                                                                                         | `build.gradle.kts`                             |
| Entità Mannequin           | presente: `org.bukkit.entity.Mannequin extends LivingEntity` (profilo/skin, skin parts, descrizione, pose)                                                    | `paper-api` 26.2                               |
| MockBukkit                 | `org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.117.0` (Maven Central)                                                                                          | Maven Central                                  |
| JUnit                      | MockBukkit 26.2 dipende da **JUnit Jupiter 6.1.3**, non 5                                                                                                     | POM di MockBukkit                              |
| Claude Agent SDK (TS)      | `@anthropic-ai/claude-agent-sdk` 0.3.322 (npm)                                                                                                                | npm                                            |
| Modelli                    | `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5` (ID confermati)                                                                                    | riferimento API                                |
| Node                       | 22                                                                                                                                                            | richiesta del progetto                         |

**Non verificato** (rete di questa sessione bloccata verso `repo.purpurmc.org`, `repo.papermc.io`, `api.purpurmc.org`,
`maven.citizensnpcs.co`):

- il numero di build `<N>` esatto da mettere in `purpur-api:26.2.build.<N>-stable`;
- se Citizens ha una build compatibile con 26.2 (il branch `master` esiste ma non posso leggere le release).

Per la Fase 2 servono questi host nella rete dell'ambiente (o mi dai tu il numero di build e lavoro offline sul resto).
JDK 25 è installabile via apt (`openjdk-25-jdk-headless`), quindi non è un blocco.

### Conseguenze tecniche importanti

1. **Mannequin non è un `Mob`**: non ha il pathfinder vanilla. Proposta per `MannequinBody`:
   A* nostro su griglia di blocchi (budget di nodi limitato, calcolato a fette sul thread di regione), movimento a
   piccoli `teleport` per tick (il client interpola), `swingMainHand()` per l'animazione del braccio. `CitizensBody`
   usa invece il navigator di Citizens.
2. **Nessun NMS necessario**: compiliamo contro `purpur-api` (`compileOnly`), senza paperweight userdev. Build più
   semplice e veloce; userdev resta un'opzione se un giorno servisse.
3. **JUnit 6** invece di 5 (lo impone MockBukkit 26.2). API Jupiter quasi identica per noi.

---

## 3. Struttura del repository proposta

```
/plugin        Gradle Kotlin DSL, Java 25, compileOnly purpur-api, Spotless, MockBukkit + JUnit 6
  src/main/java/dev/agentcraft/server/
    AgentCraftPlugin.java
    bridge/      client WebSocket (java.net.http.WebSocket, thread dedicato), codec JSON, riconnessione con backoff
    protocol/    record Java dei messaggi (mirror di /protocol), validazione
    body/        AgentBody, MannequinBody, CitizensBody, pathfinding A*, nameplate/status
    world/       WorldTools: inspect, place (job a lotti per tick), undo (snapshot), preview (BlockDisplay)
    region/      regioni autorizzate (proprie + adapter WorldGuard), limiti, blacklist
    server/      command whitelist, log tail, metriche (TPS/MSPT, spark se presente), player.simulate (solo staging)
    command/     /agent (Brigadier via Paper lifecycle API), tab completion, permessi agentcraft.*
    gui/         GUI inventario
    audit/       audit log JSONL (scrittura asincrona)
  src/main/resources/paper-plugin.yml, config.yml (commentato)
/foreman       Node 22 + TS strict, zod, vitest, ESLint/Prettier
  src/ server.ts, foreman.ts, taskgraph.ts, decisions.ts, memory.ts, repos.ts, gitsafety.ts, policy.ts,
       budget.ts, store.ts, world/ (proxy dei tool mondo), agents/{claude,sim}/, roles/
  config.example.json (commentato via chiavi "//")
/protocol      package npm `@agentcraft-server/protocol`: schemi zod, export JSON Schema, doc generata, fixture JSON
/blueprints    blueprint.schema.json + esempi (torre, casa, ponte)
/staging       docker-compose (Purpur 26.2 + volume plugins) e script start/stop/wait-for-boot
/docs          architecture.md, protocol.md (generato), security.md, admin-guide.md
NOTICE, LICENSE (MIT), README.md, .github/workflows/ci.yml
```

npm workspaces alla radice (`foreman`, `protocol`); Gradle separato in `/plugin`.

---

## 4. Protocollo proposto (v1)

### Trasporto

- Il **foreman** è server WebSocket su `127.0.0.1:7878` (configurabile su IP privato); il **plugin** è client.
  Così il foreman può girare anche quando il server MC è spento, e può servire più server (produzione + staging).
- Un oggetto JSON per frame di testo: `{ "v": 1, "type": "...", "id"?: "...", ...payload }`.
- Handshake: header `Authorization: Bearer <token>` **e** primo messaggio `hello` con il token; connessioni con
  `Origin` presente o Host non consentito vengono rifiutate (HTTP 401). Token confrontato in tempo costante.
- Timestamp epoch ms, campi opzionali omessi (mai `null`), campi sconosciuti ignorati.
- Messaggi con `id` ricevono `ack { re, ok, error?, result? }`.

### Famiglie di messaggi

**Plugin → foreman**

| type                                                                                                        | contenuto                                                                                                                                                                                              |
| ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `hello`                                                                                                     | `token`, `pluginVersion`, `server { id, role: "production"\|"staging", mcVersion, software }`, `capabilities` (Citizens, WorldGuard, WorldEdit, spark, LuckPerms), `limits` (blocchi/tick, volume max) |
| `goal.submit`, `user.message`, `decision.answer`, `task.action`, `agent.action`, `diff.request`, `repo.add` | come agentcraft, più `actor { uuid, name }` per l'audit e il controllo permessi lato foreman                                                                                                           |
| `tool.result`                                                                                               | risposta a `tool.call`: `{ re, ok, result?, error { code, message }? }`                                                                                                                                |
| `job.progress`                                                                                              | `{ jobId, done, total, state: running\|paused\|done\|failed\|undone }`                                                                                                                                 |
| `world.event`                                                                                               | eventi utili agli agenti (giocatore entra in regione, errori in console, crash plugin) — filtrati                                                                                                      |

**Foreman → plugin**

| type                                                                                                                        | contenuto                                                                                                                               |
| --------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `snapshot`, `agent.upsert`, `task.upsert`, `decision.upsert`, `goal.upsert`, `feed.add`, `notify`, `foreman.status`, `diff` | come agentcraft; `agent` aggiunge `role` esteso (lead, developer, qa, architect, builder, analyst), `skin`, `body { target?, follow? }` |
| `tool.call`                                                                                                                 | `{ id, tool, args, agentId, taskId?, requestedBy, deadlineMs }`                                                                         |
| `job.control`                                                                                                               | `{ jobId, action: pause\|resume\|cancel }`                                                                                              |

### World tools (`tool.call.tool`)

| tool                                   | args principali                                        | dove                       | note                                                                                         |
| -------------------------------------- | ------------------------------------------------------ | -------------------------- | -------------------------------------------------------------------------------------------- |
| `world.inspect`                        | `region` (nome o box), `detail`                        | prod+staging               | output compatto: palette + RLE per layer, biomi, conteggi entità; limite di volume           |
| `world.preview`                        | `blueprint`, `anchor`, `rotation`, `viewers[]`         | prod+staging               | BlockDisplay visibili solo agli admin indicati, scade dopo N minuti                          |
| `world.place`                          | `blueprint` o `ops[]`, `region`, `jobId`, `approvalId` | prod+staging               | rifiutato senza decisione `build` approvata; snapshot prima di ogni lotto                    |
| `world.undo`                           | `jobId`                                                | prod+staging               | ripristina dallo snapshot                                                                    |
| `server.command`                       | `cmd`, `as: console\|fakeplayer`                       | prod (whitelist) / staging | whitelist per prefisso; deny fisso su op/deop/ban/pardon/whitelist/lp/permission/stop/reload |
| `server.logs`                          | `since`, `filter`, `limit`                             | prod+staging               | sola lettura, segreti mascherati                                                             |
| `server.metrics`                       | —                                                      | prod+staging               | TPS, MSPT, entità, chunk caricati, spark se presente                                         |
| `player.simulate`                      | `actions[]`                                            | **solo staging**           | rifiutato se `role=production`                                                               |
| `body.move`, `body.say`, `body.status` | target, testo, riga di stato                           | prod+staging               | movimento e comunicazione NPC                                                                |

Codici di errore standard: `forbidden`, `out_of_region`, `blacklisted_block`, `too_large`, `rate_limited`,
`not_staging`, `needs_approval`, `invalid_args`, `timeout`, `busy`.

### Sorgente unica dello schema

zod in `/protocol` → genera `docs/protocol.md`, `protocol/schema/*.json` e fixture `protocol/examples/*.json`.
Il plugin ha record Java scritti a mano + un test che deserializza e ri-serializza **ogni fixture** e confronta.
La CI fallisce se doc, schema o fixture non sono aggiornati.

---

## 5. Blueprint (v1, bozza)

```json
{
  "format": "agentcraft-blueprint",
  "version": 1,
  "name": "Torre di guardia",
  "size": [9, 18, 9],
  "anchor": [4, 0, 4],
  "rotation": 0,
  "palette": { "a": "minecraft:stone_bricks", "b": "minecraft:oak_stairs[facing=north,half=bottom]" },
  "modules": [
    { "id": "fondazione", "ops": [{ "op": "fill", "from": [0, 0, 0], "to": [8, 0, 8], "block": "a" }] },
    {
      "id": "muri",
      "dependsOn": ["fondazione"],
      "ops": [{ "op": "line", "from": [0, 1, 0], "to": [8, 1, 0], "block": "a" }]
    },
    {
      "id": "tetto",
      "dependsOn": ["muri"],
      "ops": [{ "op": "roof", "style": "gable", "from": [0, 12, 0], "to": [8, 12, 8], "block": "b" }]
    }
  ]
}
```

Op supportate: `set`, `fill` (pieno/vuoto/contorno), `line`, `stairs`, `roof`, `layers` (matrice di caratteri).
Ogni modulo diventa un task `build` separato. Validazione nel foreman (schema, stima blocchi) **e** nel plugin
(regione, blacklist, volume, container non vuoti).

---

## 6. Sicurezza: dove viene fatta rispettare ogni regola

| Regola                                                              | Foreman                 | Plugin                                                                    |
| ------------------------------------------------------------------- | ----------------------- | ------------------------------------------------------------------------- |
| Bridge solo locale + token, no Origin browser                       | ✔ (server)              | ✔ (invia token, non accetta altro)                                        |
| Costruire solo in regione, blacklist blocchi, volume max, container | pre-validazione         | **autoritativo**                                                          |
| Whitelist comandi, deny fisso op/ban/permessi                       | pre-filtro              | **autoritativo**                                                          |
| `player.simulate` e modifiche plugin solo su staging                | ✔                       | **autoritativo** (`role` da config locale, non dal foreman)               |
| Rate limit per agente                                               | ✔                       | ✔                                                                         |
| Niente `git push`, niente rete git                                  | ✔ (git safety + policy) | —                                                                         |
| Merge/deploy/costruzione solo con approvazione umana                | ✔ (decision queue)      | ✔ (`world.place` richiede `approvalId` valido, verificato con il foreman) |
| Audit JSONL                                                         | ✔ (azioni agenti)       | ✔ (azioni nel mondo/server)                                               |
| Segreti solo in env o file ignorati da git                          | ✔                       | ✔                                                                         |

Principio: il plugin non si fida mai del foreman né dell'LLM. Il ruolo del server (produzione/staging) è deciso
solo dal `config.yml` del plugin.

---

## 7. Threading (plugin)

- Bridge WebSocket, parsing JSON, audit e lettura log su thread asincroni; mai rete o I/O sul main thread.
- Ogni azione sul mondo è schedulata sul thread corretto (`RegionScheduler`/`EntityScheduler` di Paper: funzionano
  su Paper/Purpur e restano compatibili con Folia a costo quasi zero).
- `world.place` processa al massimo `blocksPerTick` (default 200) per tick, con pausa automatica se MSPT > soglia.

---

## 8. Roadmap confermata (con criteri di uscita)

| Fase                    | Uscita                                                                                                                             |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| 2. Scheletro            | `./gradlew build` del plugin, `npm test` del foreman e protocol, CI verde, schema+doc generati                                     |
| 3. Bridge + sim         | con `--backend sim` gli NPC compaiono su un server locale, camminano, cambiano stato; `/agent status/tasks/agents/feed` funzionano |
| 4. Agenti di coding     | goal reale → piano → worktree → build/test → decisione merge approvata in gioco                                                    |
| 5. Staging e QA         | deploy jar su staging, scenario in-game con fake player, report bug                                                                |
| 6. Building             | blueprint → anteprima → approvazione → costruzione visibile → undo                                                                 |
| 7. Analisi e rifiniture | metriche/spark, GUI completa, Discord, budget, documentazione completa                                                             |

---

## 9. Domande aperte (servono risposte prima della Fase 2)

1. **Plugin custom**: quali repository, linguaggio (Java/Kotlin), build (Gradle/Maven), test esistenti?
2. **Plugin sul server**: ci sono Citizens, WorldGuard, WorldEdit/FAWE, LuckPerms, spark? (Se Citizens c'è, che versione.)
3. **Hosting**: il server gira sulla stessa macchina del foreman? Docker, Pterodactyl, altro? Possiamo avere uno staging separato?
4. **Budget**: budget mensile API e quanti agenti contemporanei (default proposto: 3 worker).
5. **NPC**: nomi, skin e personalità (posso proporre un cast di 6 se preferisci).
6. **JUnit 6** al posto di 5 (imposto da MockBukkit 26.2): va bene?
7. **Rete per la Fase 2**: posso chiedere di aggiungere `repo.purpurmc.org`, `repo.papermc.io` (e `maven.citizensnpcs.co`) all'ambiente? In alternativa dimmi il numero di build di `purpur-api` che usate.
8. **Package Java**: va bene `dev.agentcraft.server` o preferite un vostro dominio?
