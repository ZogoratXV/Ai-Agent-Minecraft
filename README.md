# AgentCraft Server

Agenti AI visibili come **NPC** su un server **Purpur 26.2**: testano e migliorano i plugin custom,
analizzano i sistemi di gioco e costruiscono edifici blocco per blocco. Gli admin assegnano i compiti
dalla chat (`/agent ...`) o da una GUI, e approvano ogni merge, deploy e costruzione.

Ispirato ad [AgentCraft](https://github.com/blendi-remade/agentcraft) (MIT), vedi [NOTICE](NOTICE).

> **Stato: Fase 3.** Plugin e foreman sono collegati; con il backend simulato gli NPC compaiono,
> camminano tra le stazioni, parlano e chiedono decisioni agli admin con messaggi cliccabili.
> Gli agenti Claude veri arrivano nella Fase 4. Piano completo: [docs/phase1-analysis.md](docs/phase1-analysis.md).

## Come è fatto

```
Purpur 26.2 (Java 25)                         foreman (Node 22 + TypeScript)
plugin "agentcraft-server"  ── ws://127.0.0.1:7878 + token ──  agenti Claude, task graph,
NPC, world tools, /agent, audit                                worktree git, decisioni, memoria
```

| Cartella                    | Contenuto                                                                                   |
| --------------------------- | ------------------------------------------------------------------------------------------- |
| [`plugin/`](plugin)         | plugin Purpur (Gradle Kotlin DSL, Java 25, `purpur-api 26.2.build.2633-stable`)             |
| [`foreman/`](foreman)       | orchestratore (Node 22, TypeScript strict, zod, vitest)                                     |
| [`protocol/`](protocol)     | schemi zod del bridge (fonte unica), JSON Schema, fixture, [doc generata](docs/protocol.md) |
| [`staging/`](staging)       | script per un server Purpur di staging                                                      |
| [`blueprints/`](blueprints) | formato dei progetti di costruzione (Fase 6)                                                |
| [`docs/`](docs)             | architettura, sicurezza, protocollo, guida admin                                            |

## Requisiti

- Java 25 (JDK per buildare il plugin), Node 22+, git
- Server Purpur 26.2. Opzionali: Citizens, WorldGuard, WorldEdit/FAWE, LuckPerms, spark

## Build e test

```sh
npm ci && npm run check              # protocol + foreman: typecheck, lint, format, test
cd plugin && ./gradlew build         # plugin: compilazione, Spotless, JUnit 6 + MockBukkit
# jar: plugin/build/libs/agentcraft-server-<versione>.jar
ACCEPT_EULA=true staging/e2e-sim.sh  # prova completa su un vero Purpur 26.2 (accetta l'EULA di Minecraft)
```

Dopo aver cambiato `protocol/src`, rigenera schema, fixture e doc con `npm run gen:protocol`
(la CI fallisce se non sono aggiornati).

## Installazione rapida (oggi)

1. Serve Node 22+ sulla macchina del server: il foreman è dentro il jar e lo avvia il plugin,
   generando da solo il token.
2. Copia il jar in `plugins/` e avvia il server: viene creato `plugins/AgentCraftServer/config.yml`.
   Imposta `server.role` (`production` o `staging`) e riavvia.
3. `/agent status` deve dire `Foreman: connesso`. Poi `/agent base set base` dove vuoi la base,
   e `/agent goal prova` per vedere la squadra simulata al lavoro.

Guida rapida di configurazione: [docs/guida-rapida.md](docs/guida-rapida.md). Tutti i comandi: [docs/admin-guide.md](docs/admin-guide.md).

## Squadra predefinita

| NPC          | Ruolo                  |     | NPC          | Ruolo                           |
| ------------ | ---------------------- | --- | ------------ | ------------------------------- |
| **Bussola**  | lead, caposquadra      |     | **Mattone**  | builder, capomastro             |
| **Lucciola** | developer              |     | **Cazzuola** | builder (fuori turno all'avvio) |
| **Briciola** | qa, cacciatrice di bug |     | **Civetta**  | analyst (fuori turno all'avvio) |
| **Matita**   | architect              |     |              |                                 |

Nomi, colori, personalità e skin si cambiano in `foreman/config.json` (vedi `config.example.json`).

## Licenza

[MIT](LICENSE). AgentCraft, da cui prendiamo spunto, è anch'esso MIT: vedi [NOTICE](NOTICE).
