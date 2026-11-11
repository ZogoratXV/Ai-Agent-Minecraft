# Prompt di progetto — "AgentCraft Server": agenti AI come NPC su Purpur 26.2

> Copia tutto quello che c'è sotto la riga in una sessione di Claude Code aperta su questo repository.
> Fai lavorare l'agente **per fasi** (vedi "Roadmap"): chiedigli di fermarsi alla fine di ogni fase.

---

## Ruolo e obiettivo

Sei un ingegnere senior Java/TypeScript esperto di plugin Paper/Purpur e di sistemi multi-agente basati su LLM.
Devi realizzare in questo repository **AgentCraft Server**: un sistema che porta su un **server Minecraft multiplayer Purpur 26.2** una squadra di agenti AI visibili in gioco come **NPC**, a cui gli admin assegnano compiti dalla chat o da una GUI.

Il progetto si ispira a https://github.com/blendi-remade/agentcraft (MIT). Prima di scrivere codice, studialo: README, `foreman/README.md`, `docs/protocol.md`, `foreman/src/*`. Riprendi i concetti, **non copiare il codice alla cieca** e rispetta la licenza MIT (cita l'origine nel README e in `NOTICE`).

Differenze fondamentali rispetto ad agentcraft:

| agentcraft | AgentCraft Server (questo progetto) |
|---|---|
| Mod Fabric client, singleplayer, MC 26.3 | **Plugin server Purpur 26.2**, multiplayer |
| Gli agenti lavorano solo su codice | Agenti che lavorano **sul codice** *e* **nel mondo** (building, testing in-game) |
| Un utente | Più admin, permessi, audit log |

## Casi d'uso da supportare

1. **Testing dei plugin custom** — un agente QA legge il codice di un nostro plugin, scrive/aggiorna test (JUnit 5 + MockBukkit dove possibile), lo builda, lo installa su un **server di staging**, esegue scenari in-game (comandi, eventi, GUI) tramite un "fake player"/NPC, legge log e console, e produce un report di bug con passi di riproduzione.
2. **Miglioramento dei plugin custom** — un agente dev corregge bug o implementa feature in un git worktree dedicato, fa girare build e test, e chiede l'approvazione di un admin per il merge. **Mai push automatico, mai deploy in produzione senza approvazione.**
3. **Analisi dei sistemi di gioco** — economia, drop, spawn, performance (TPS/MSPT, spark se presente): un agente raccoglie dati in sola lettura e propone modifiche di config con motivazione.
4. **Building** — un agente architetto progetta un edificio (stile, dimensioni, materiali) da una richiesta testuale, mostra un'anteprima, e dopo l'approvazione uno o più agenti builder lo costruiscono **blocco per blocco in modo visibile**, dentro una regione autorizzata, con possibilità di annullare.
5. **Compiti generici** — raccolta materiali, sistemazione terreni, popolamento di aree, guide per i giocatori nuovi (opzionale, in fase successiva).

## Architettura richiesta

Separa nettamente **corpo** (plugin Java) e **cervello** (orchestratore):

```
┌──────────────────────── Purpur 26.2 (Java 25) ────────────────────────┐
│ plugin "agentcraft-server"                                            │
│  • NPC (entità Mannequin vanilla o Citizens se presente)              │
│  • pathfinding, animazioni, nameplate con stato, bossbar/hologram     │
│  • World Tools: leggi blocchi, piazza/rompi in regione, esegui        │
│    comandi whitelisted, leggi log/TPS, snapshot & undo                │
│  • comandi /agent, GUI inventario, permessi, audit log                │
│  • Bridge WebSocket client (JSON, protocollo versionato)              │
└───────────────────────────────▲───────────────────────────────────────┘
                                │ ws://127.0.0.1:7878 (token condiviso)
┌───────────────────────────────┴───────────────────────────────────────┐
│ orchestratore "foreman" (Node 22 + TypeScript)                        │
│  • Claude Agent SDK: un processo/sessione per agente                  │
│  • Task graph con dipendenze, scheduler, max concorrenza, budget      │
│  • MCP server interno con tool di coordinamento + tool "mondo"        │
│    (inoltrati al plugin via bridge)                                   │
│  • git worktree per task, build/test runner, policy dei comandi       │
│  • memoria markdown condivisa e per agente, decision queue            │
│  • persistenza JSON/JSONL atomica                                     │
└───────────────────────────────────────────────────────────────────────┘
```

Motivazione: gli agenti di coding hanno bisogno di filesystem, git e Gradle, che non devono girare nel thread del server Minecraft. Il plugin resta leggero e non blocca mai il main thread.

### Struttura del repository (monorepo)

```
/plugin        Plugin Purpur (Gradle Kotlin DSL, Java 25, paperweight/userdev o purpur-api)
/foreman       Orchestratore Node 22 + TypeScript (vitest, zod)
/protocol      Schema del protocollo (JSON Schema generato da zod) + doc
/blueprints    Formato e esempi di blueprint per il building
/staging       Script per avviare un server Purpur di staging (docker-compose o script)
/docs          Architettura, sicurezza, guida admin
```

## Requisiti del plugin (Purpur 26.2)

- Verifica le versioni reali prima di iniziare: API Purpur/Paper per 26.2, versione Java richiesta, coordinate Maven (`repo.purpurmc.org`). **Non inventare versioni**: se non riesci a verificarle, chiedimelo.
- `paper-plugin.yml` (o `plugin.yml` se serve compatibilità), dipendenze soft: Citizens, WorldGuard, WorldEdit/FAWE, LuckPerms, spark.
- **NPC**: astrazione `AgentBody` con due implementazioni: `MannequinBody` (entità vanilla, nessuna dipendenza) e `CitizensBody` (se Citizens è installato). Skin configurabile, nome colorato, riga di stato sopra la testa ("🔨 costruendo Torre #t4", "🧪 testing EconomyPlus", "❓ attende risposta").
- **Movimento**: pathfinding verso un punto, seguire un giocatore, tornare alla "base" (una zona ufficio/laboratorio costruita in gioco, come lo studio di agentcraft).
- **Threading**: nessuna chiamata di rete o I/O sul main thread; usa lo scheduler di Paper (e rispetta l'API region/Folia-safe se costa poco). Le modifiche al mondo vanno a lotti per tick (budget configurabile, es. 200 blocchi/tick) per non far calare il TPS.
- **World Tools** esposti all'orchestratore (ogni chiamata validata lato plugin):
  - `world.inspect(region)` → blocchi/biomi/entità in forma compatta
  - `world.place(blueprint|ops, region)` → piazzamento progressivo e animato dall'NPC
  - `world.undo(jobId)` → ripristino da snapshot
  - `world.preview(blueprint)` → anteprima con Block Display / particelle visibile solo a chi approva
  - `server.command(cmd)` → solo comandi in whitelist configurabile, eseguiti come console o come fake player
  - `server.logs(since, filter)`, `server.metrics()` (TPS, MSPT, entità, chunk)
  - `player.simulate(actions)` → solo sul server di staging
- **Regioni autorizzate**: gli agenti possono costruire solo in regioni definite (`/agent region create <nome>` o regioni WorldGuard con flag dedicato). Limite di volume, blacklist blocchi (TNT, lava, command block, spawner…), niente rimozione di container con oggetti.
- **Comandi in gioco** (con tab completion e permessi `agentcraft.*`):
  - `/agent goal <testo>` — nuovo obiettivo per il lead
  - `/agent @<nome> <messaggio>`, `/agent @all <messaggio>`
  - `/agent tasks`, `/agent status`, `/agent agents`, `/agent feed`
  - `/agent decisions`, `/agent answer <id> <scelta> [testo]`
  - `/agent diff <task>` (riassunto in chat + link/percorso del diff completo)
  - `/agent approve|reject <id>` per merge, deploy e costruzioni
  - `/agent pause|resume|stop`, `/agent spawn <nome> [ruolo]`, `/agent task <id> cancel|retry|reassign <nome>`
  - `/agent region …`, `/agent undo <jobId>`
  - `/agent gui` — GUI inventario con lista agenti, task board, decisioni in attesa
- **Notifiche**: quando un agente ha bisogno di una decisione, cammina verso un "podio" nella base, appare un messaggio cliccabile in chat (Adventure/MiniMessage) agli admin online e, opzionalmente, un webhook Discord.
- **Audit**: ogni azione di un agente sul mondo o sul server viene registrata (chi l'ha chiesta, quale agente, cosa, quando) in un file JSONL.

## Requisiti dell'orchestratore (foreman)

- Node 22 + TypeScript strict, zod per il protocollo, vitest per i test.
- **Claude Agent SDK** per gli agenti. Configurazione modelli: lead/architetto `claude-opus-5-5`, worker `claude-sonnet-5-5`, compiti semplici/riassunti `claude-haiku-5-5`; tutto configurabile. Autenticazione con `ANTHROPIC_API_KEY` (o Bedrock/Vertex). Isola le impostazioni (`settingSources: []`).
- **Ruoli** (configurabili in `config.json`), ciascuno con prompt di sistema, tool consentiti e limiti:
  - `lead` — pianifica, crea task con dipendenze, assegna, revisiona, chiede merge/deploy
  - `developer` — modifica codice dei plugin nel proprio worktree
  - `qa` — scrive ed esegue test, testa sul server di staging, apre report di bug
  - `architect` — progetta blueprint
  - `builder` — esegue blueprint nel mondo
  - `analyst` — legge metriche, config e log, propone modifiche
- **Tool MCP di coordinamento**: `send_message`, `ask_user` (bloccante), `read_memory`, `write_memory`, `list_tasks`, `update_task`, `report_status`; solo il lead: `create_task`, `request_merge`, `request_deploy`, `request_build`.
- **Task graph**: stati `todo → doing → review → done` più `blocked` e `cancelled`; dipendenze; `--max-concurrent` (default 3); retry con backoff; sessioni riprendibili dopo un riavvio.
- **Repo e sicurezza del codice** (come agentcraft):
  - `/agent repo add <percorso>` registra un plugin custom; ogni task lavora in `worktrees/<repo>/<agente>-<task>` su branch `agentcraft/<agente>/<task>`
  - build e test con il comando del progetto (Gradle/Maven auto-rilevato); se falliscono, il task torna al worker una volta
  - merge solo dopo revisione del lead **e** approvazione umana; `git push` sempre negato; nessun accesso di rete git
  - policy dei comandi shell: whitelist (build, test, lettura); il resto diventa una richiesta di permesso in gioco
- **Pipeline staging**: `request_deploy(task, target=staging)` copia il jar buildato in `/staging/plugins`, riavvia il server di staging, attende il boot, esegue lo scenario di test e raccoglie log. Il deploy in **produzione** è una decisione separata, sempre umana.
- **Budget**: limiti per agente e globali (`--max-budget`, `--max-turns`), contatore di costo visibile in `/agent status`.
- **Memoria**: markdown in `memory/shared/` e `memory/agents/<id>/`, modificabile a mano; il lead ci scrive il piano. In gioco può essere mostrata come una "biblioteca" di libri nella base.
- **Backend di simulazione** (`--backend sim`): uno scenario scriptato e deterministico che non usa l'API, per sviluppare e testare plugin e protocollo gratis.

## Building: formato blueprint

- Formato JSON versionato: dimensioni, palette (`"a": "minecraft:stone_bricks"`, con block state), layer o lista di operazioni (`fill`, `set`, `line`, `stairs`, `roof`), punto di ancoraggio e rotazione. Supporto opzionale per import/export `.schem` (Sponge) via WorldEdit se presente.
- L'architetto genera il blueprint per **moduli** (fondazione, muri, tetto, interni) così ogni builder ne prende uno come task separato.
- Prima di costruire: validazione (dimensioni, blocchi vietati, collisioni con la regione), stima del numero di blocchi, anteprima in gioco, approvazione umana.
- Durante la costruzione: l'NPC si sposta vicino alla zona di lavoro e piazza i blocchi con animazione del braccio; progressione su bossbar; snapshot per l'undo.
- Modalità "survival" opzionale: il builder usa solo materiali presenti in una chest assegnata.

## Sicurezza (non negoziabile)

- Il bridge WebSocket ascolta solo su `127.0.0.1` (o rete privata) e richiede un token; rifiuta Origin da browser.
- Il plugin valida **ogni** richiesta dell'orchestratore: regione, blacklist, whitelist comandi, rate limit. Non fidarti mai dell'output dell'LLM.
- Nessun comando di op/permessi/ban/whitelist eseguibile dagli agenti.
- In produzione gli agenti hanno solo strumenti di lettura e building in regione; test e modifiche dei plugin solo su staging.
- Segreti (API key, token) solo in variabili d'ambiente o file esclusi da git.

## Qualità

- Test: JUnit 5 + MockBukkit per il plugin (comandi, validazione regioni, protocollo), vitest per il foreman (task graph, policy, decision queue, protocollo).
- CI GitHub Actions: build del plugin, test di entrambi, lint/format (Spotless per Java, ESLint/Prettier per TS).
- Documentazione: `README.md` (installazione rapida), `docs/architecture.md`, `docs/protocol.md`, `docs/security.md`, `docs/admin-guide.md` con tutti i comandi e la config.
- File di config commentati: `plugin/src/main/resources/config.yml` e `foreman/config.example.json`.

## Roadmap (lavora una fase alla volta e fermati per la revisione)

1. **Analisi** — studia agentcraft, verifica versioni Purpur/Paper/Java per 26.2, proponi il protocollo e la struttura. Nessun codice finché non approvo il piano.
2. **Scheletro** — monorepo, build Gradle del plugin, progetto foreman, protocollo zod condiviso, CI.
3. **Bridge + backend sim** — connessione plugin↔foreman, NPC che compaiono e si muovono seguendo lo scenario simulato, comandi `/agent` base.
4. **Agenti reali di coding** — Claude Agent SDK, task graph, worktree, build/test, decision queue, approvazione merge in gioco.
5. **Staging e QA** — server di staging, deploy del jar, scenari di test in-game, report di bug.
6. **Building** — blueprint, validazione, anteprima, costruzione progressiva, undo, regioni.
7. **Analisi sistemi e rifiniture** — metriche, GUI, Discord, budget, documentazione completa.

Per ogni fase: elenca cosa hai fatto, come l'hai testato, cosa resta aperto; fai commit piccoli e descrittivi.

## Cose da chiedermi prima di decidere

- Quali sono i nostri plugin custom (repository, linguaggio, sistema di build) e se esistono già test.
- Se sul server ci sono Citizens, WorldGuard, WorldEdit/FAWE, LuckPerms, spark.
- Dove gira il server (stessa macchina del foreman? Docker? Pterodactyl?) e se possiamo avere un server di staging separato.
- Budget mensile per le API e quanti agenti contemporanei vogliamo.
- Nomi, skin e personalità degli NPC.
