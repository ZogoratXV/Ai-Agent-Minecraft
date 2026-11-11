# Guida admin

Documento vivo: cresce a ogni fase. La versione completa arriva nella Fase 7.

## Avvio (oggi: backend simulato)

```sh
export AGENTCRAFT_TOKEN=$(openssl rand -hex 24)   # stesso valore per foreman e server Minecraft
cd foreman && npm start -- --backend sim          # foreman su ws://127.0.0.1:7878
# poi avvia il server Purpur con il plugin, con la stessa AGENTCRAFT_TOKEN nell'ambiente
```

Il plugin si collega da solo e si ricollega se il foreman si riavvia. `/agent status` mostra
`Foreman: connesso`. Il backend `sim` è una squadra scriptata che non usa le API: serve a provare
NPC, comandi e decisioni gratis.

## Comandi

| Comando                                                 | Permesso             | Cosa fa                                                                                                         |
| ------------------------------------------------------- | -------------------- | --------------------------------------------------------------------------------------------------------------- |
| `/agent`, `/agent help`                                 | `agentcraft.use`     | elenco comandi                                                                                                  |
| `/agent status`                                         | `agentcraft.use`     | ruolo del server, foreman, integrazioni, backend, budget, obiettivi attivi                                      |
| `/agent agents`                                         | `agentcraft.use`     | squadra con ruolo, stato e task                                                                                 |
| `/agent tasks`                                          | `agentcraft.use`     | task con stato, assegnatario e dipendenze                                                                       |
| `/agent feed`                                           | `agentcraft.use`     | ultime attività                                                                                                 |
| `/agent decisions`                                      | `agentcraft.use`     | decisioni in attesa, con pulsanti cliccabili                                                                    |
| `/agent goal <testo>`                                   | `agentcraft.goal`    | nuovo obiettivo per Bussola, il caposquadra                                                                     |
| `/agent @<nome> <messaggio>`, `/agent @all <messaggio>` | `agentcraft.goal`    | messaggio a un agente o a tutti                                                                                 |
| `/agent answer <id> <n\|opzione> [testo]`               | `agentcraft.approve` | risponde a una decisione (`-` al posto del numero per rispondere solo con testo)                                |
| `/agent approve <id>`, `/agent reject <id>`             | `agentcraft.approve` | prima opzione / Reject                                                                                          |
| `/agent pause [nome\|all]`, `/agent resume [nome\|all]` | `agentcraft.manage`  | mette in pausa / riprende (senza nome: tutti)                                                                   |
| `/agent stop <nome>`, `/agent spawn <nome>`             | `agentcraft.manage`  | toglie dal turno / rimette in turno                                                                             |
| `/agent task <id> cancel\|retry\|reassign <nome>`       | `agentcraft.manage`  | gestione task                                                                                                   |
| `/agent base set <stazione>`                            | `agentcraft.manage`  | mette una stazione dove sei: `base`, `desk`, `library`, `terminal`, `testbench`, `podium`, `worksite`, `lounge` |

Tutti i comandi hanno il completamento con Tab (agenti, task, decisioni aperte, opzioni, stazioni).

## NPC

- Con **Citizens** installato gli agenti sono NPC Citizens (pathfinding e skin di Citizens), in un
  registro in memoria: non finiscono nei salvataggi di Citizens. Senza Citizens, o se Citizens dà
  errore, si usano le entità vanilla **Mannequin** con un pathfinding A* del plugin.
  Scelta forzabile con `npc.backend: citizens|mannequin` in `config.yml`.
- Sopra la testa: nome colorato e riga di stato (`🔨 costruendo…`, `🧪 testing…`, `❓ attende risposta`).
- Gli agenti camminano tra le stazioni della base. Le stazioni non impostate vengono messe attorno
  alla `base` (di default lo spawn del primo mondo); sistemale con `/agent base set`.
- Quando un agente ha bisogno di una decisione va al podio, e gli admin con `agentcraft.approve`
  ricevono un messaggio con pulsanti e un suono. Chi entra trova un promemoria se ci sono decisioni aperte.

## Permessi

`agentcraft.use`, `agentcraft.goal`, `agentcraft.approve`, `agentcraft.manage`, `agentcraft.region`,
`agentcraft.admin` (tutti). Di default solo gli op; con LuckPerms assegnali ai gruppi staff.

## Configurazione

- Plugin: `plugins/AgentCraftServer/config.yml` (commentato, vedi `plugin/src/main/resources/config.yml`),
  stazioni in `plugins/AgentCraftServer/stations.yml`, audit in `plugins/AgentCraftServer/audit.jsonl`.
- Foreman: `foreman/config.json`, copiato da `foreman/config.example.json`. `sim.stepMs` regola la
  velocità della squadra simulata.
- Segreti: `AGENTCRAFT_TOKEN` (stesso valore per server e foreman), `ANTHROPIC_API_KEY` (solo foreman).

## Budget consigliato

3 worker contemporanei, tetto di **15 $ al giorno**, **300 $ al mese**, **5 $ per task**. Raggiunto un
tetto, gli agenti si mettono in pausa e gli admin vengono avvisati (Fase 4). Tutto modificabile in `config.json`.

## Prove automatiche

`ACCEPT_EULA=true staging/e2e-sim.sh` avvia foreman (sim) e un server Purpur 26.2 con il plugin,
gioca un obiettivo intero dalla console e controlla task, decisioni, movimenti e audit.
