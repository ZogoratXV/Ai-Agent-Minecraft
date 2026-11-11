# Guida rapida: AgentCraft Server sul vostro server

Stato al termine della Fase 3. Il plugin funziona con la **squadra simulata**: gli agenti Claude
veri (che lavorano su codice e mondo) arrivano dalla Fase 4. Riferimento completo:
[admin-guide.md](admin-guide.md).

## 1. Cosa serve

|           |                                                                                                                                                                                       |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Server    | Purpur **26.2** su Java **25**                                                                                                                                                        |
| Foreman   | Node **22** sulla stessa macchina del server (o nella stessa rete privata)                                                                                                            |
| Opzionale | **Citizens** (NPC con skin e pathfinding di Citizens; senza, si usano i Mannequin vanilla). WorldGuard, WorldEdit, LuckPerms e spark vengono rilevati; li useremo nelle prossime fasi |

## 2. Il token (obbligatorio)

Plugin e foreman si riconoscono con un segreto condiviso di almeno 16 caratteri. Generalo una volta:

```sh
openssl rand -hex 24
```

Dallo a **entrambi**, in uno di questi due modi (mai nei file versionati su git):

- **Variabile d'ambiente** `AGENTCRAFT_TOKEN`, sia nello script di avvio del server Minecraft sia
  dove avvii il foreman.
- **File**: se il pannello del server non permette variabili d'ambiente, metti il token in
  `plugins/AgentCraftServer/token.txt` e scrivi `token-file: token.txt` in `config.yml`. Per il
  foreman: `"tokenFile": "/percorso/token.txt"` in `foreman/config.json`.

Se il token manca il plugin si avvia lo stesso ma resta scollegato (avviso in console).

## 3. Avviare il foreman

```sh
git clone https://github.com/ZogoratXV/Ai-Agent-Minecraft && cd Ai-Agent-Minecraft
npm ci
cd foreman && npm start -- --backend sim
```

Ascolta su `ws://127.0.0.1:7878`, solo in locale. Per cambiare porta, velocità della squadra
simulata, nomi o colori degli NPC copia `foreman/config.example.json` in `foreman/config.json`.

## 4. Installare il plugin

1. Prendi il jar: dall'artefatto `agentcraft-server-plugin` della CI su GitHub, oppure
   `cd plugin && ./gradlew build` (esce in `plugin/build/libs/`).
2. Copialo in `plugins/` e avvia il server: viene creato `plugins/AgentCraftServer/config.yml`.
3. Modifica la configurazione (sezione 5) e **riavvia il server**: per ora non c'è un comando di reload.
4. In gioco, `/agent status` deve dire **Foreman: connesso**.

## 5. `config.yml`: cosa toccare

| Chiave                                       | Default                                                              | A cosa serve                                                                                                                                |
| -------------------------------------------- | -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `server.id`                                  | `staging`                                                            | nome del server, mostrato agli agenti                                                                                                       |
| `server.role`                                | `staging`                                                            | `staging` o `production`. Lo decide **solo** questo file: in produzione gli strumenti di test (simulazione giocatore) sono sempre rifiutati |
| `bridge.url`                                 | `ws://127.0.0.1:7878`                                                | indirizzo del foreman. Solo IP locali o privati (niente nomi di dominio)                                                                    |
| `bridge.token-file`                          | vuoto                                                                | file col token, in alternativa ad `AGENTCRAFT_TOKEN`                                                                                        |
| `bridge.reconnect-min/max-seconds`           | 2 / 60                                                               | attesa tra i tentativi di riconnessione                                                                                                     |
| `world.blocks-per-tick`                      | 200                                                                  | velocità delle costruzioni (usata dalla Fase 6); abbassala se cala il TPS                                                                   |
| `world.pause-when-mspt-above`                | 45                                                                   | le costruzioni si fermano se il server è sotto sforzo                                                                                       |
| `world.max-job-volume`, `max-inspect-volume` | 200000 / 65536                                                       | limiti di blocchi per lavoro e per ispezione                                                                                                |
| `world.protect-containers`                   | `true`                                                               | le casse con oggetti non vengono mai toccate                                                                                                |
| `world.block-blacklist`                      | TNT, lava, fuoco, command block, spawner, portali, bedrock, barrier… | blocchi che gli agenti non possono mai piazzare                                                                                             |
| `commands.whitelist`                         | `list`, `tps`, `mspt`, `time query`, `weather query`, `say`          | comandi che gli agenti possono usare. op, ban, lp, permessi, stop, reload, execute… sono **sempre vietati**, anche se li aggiungi qui       |
| `limits.tool-calls-per-minute`               | 60                                                                   | quante azioni nel mondo può chiedere ogni agente al minuto                                                                                  |
| `audit.file`                                 | `audit.jsonl`                                                        | registro di ogni azione degli agenti, nella cartella del plugin                                                                             |
| `npc.backend`                                | `auto`                                                               | `auto` = Citizens se c'è, altrimenti Mannequin; oppure `citizens` / `mannequin`                                                             |

## 6. Permessi (LuckPerms)

| Permesso             | Cosa permette                                                          |
| -------------------- | ---------------------------------------------------------------------- |
| `agentcraft.use`     | vedere stato, agenti, task, attività, decisioni                        |
| `agentcraft.goal`    | dare obiettivi e scrivere agli agenti                                  |
| `agentcraft.approve` | rispondere alle decisioni, approvare o rifiutare (ricevere gli avvisi) |
| `agentcraft.manage`  | pausa/stop/turno degli agenti, gestione task, stazioni della base      |
| `agentcraft.region`  | regioni di costruzione e annullamento (Fase 6)                         |
| `agentcraft.admin`   | tutto                                                                  |

Di default li hanno solo gli op. Esempio: `lp group staff permission set agentcraft.admin true`.

## 7. Preparare la base

Gli NPC si muovono tra le **stazioni** di una base. Vai dove le vuoi e digita:

```
/agent base set base        punto centrale (se non lo imposti: spawn del primo mondo)
/agent base set desk        scrivanie (programmazione)
/agent base set library     biblioteca (lettura, revisioni)
/agent base set terminal    terminale (build e test)
/agent base set testbench   banco di prova (QA)
/agent base set podium      podio: qui vengono quando aspettano una tua decisione
/agent base set worksite    cantiere
/agent base set lounge      riposo (agenti fuori turno)
```

Le stazioni non impostate vengono messe attorno alla base. Sono salvate in `stations.yml`.

## 8. Cosa fa oggi

- **NPC della squadra**: Bussola (caposquadra), Lucciola (sviluppatrice), Briciola (QA),
  Matita (architetta), Mattone (capomastro); Cazzuola e Civetta sono fuori turno all'avvio.
  Sopra la testa hanno il nome colorato e una riga di stato (`🧪 testing…`, `⌨ editing…`,
  `❓ attende risposta`, `⏸ in pausa`, `💤 fuori turno`). Camminano tra le stazioni, muovono il
  braccio mentre lavorano e "parlano" in chat ai giocatori vicini.
- **Obiettivi**: `/agent goal <testo>`. Bussola divide il lavoro in task con dipendenze e li
  assegna; la squadra li esegue (per ora in modo simulato).
- **Decisioni**: quando un agente ha bisogno di te va al podio e ricevi un messaggio con pulsanti
  cliccabili e un suono (domande, approvazione di un merge). Chi entra nel server trova un
  promemoria se ci sono decisioni aperte.
- **Controllo della squadra**: pausa, ripresa, fuori turno, rientro in turno, annullare,
  riprovare o riassegnare un task.
- **Sicurezza e audit**: ogni richiesta del foreman viene controllata dal plugin (tool
  sconosciuti, tool di test in produzione, limite al minuto, argomenti sbagliati) e registrata
  in `audit.jsonl` con agente, richiedente ed esito.

## 9. Comandi

| Comando                                           | Cosa fa                                                                           |
| ------------------------------------------------- | --------------------------------------------------------------------------------- |
| `/agent status`                                   | collegamento, ruolo del server, integrazioni, backend, budget, obiettivi in corso |
| `/agent agents` · `tasks` · `feed` · `decisions`  | squadra, task, ultime attività, decisioni in attesa                               |
| `/agent goal <testo>`                             | nuovo obiettivo                                                                   |
| `/agent @<nome> <messaggio>` · `@all`             | scrivi a un agente o a tutti                                                      |
| `/agent answer <id> <numero> [testo]`             | rispondi a una decisione (o clicca i pulsanti in chat)                            |
| `/agent approve <id>` · `reject <id>`             | approva (prima opzione) o rifiuta                                                 |
| `/agent pause [nome\|all]` · `resume [nome\|all]` | pausa e ripresa (senza nome: tutti)                                               |
| `/agent stop <nome>` · `spawn <nome>`             | togli dal turno o rimetti in turno                                                |
| `/agent task <id> cancel\|retry\|reassign <nome>` | gestisci un task                                                                  |
| `/agent base set <stazione>`                      | posiziona una stazione dove sei                                                   |

Tutti hanno il completamento con Tab.

## 10. Cosa arriva dopo

| Fase | Novità                                                                                                        |
| ---- | ------------------------------------------------------------------------------------------------------------- |
| 4    | agenti Claude veri sui vostri plugin: worktree git, build e test, merge solo dopo la tua approvazione, budget |
| 5    | deploy sul server di staging, scenari di test in gioco, report dei bug                                        |
| 6    | costruzioni: progetto, anteprima, approvazione, costruzione blocco per blocco, annulla, regioni               |
| 7    | analisi di TPS/economia/drop, GUI, Discord, rifiniture                                                        |

## Problemi comuni

| Sintomo                               | Causa probabile                                                                                                    |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `Foreman: non connesso`               | foreman spento, porta diversa in `bridge.url`, o token diverso tra i due (in console: "foreman refused the token") |
| "No bridge token" in console          | manca `AGENTCRAFT_TOKEN` o `token-file` nell'ambiente del server                                                   |
| Il plugin si disattiva all'avvio      | `config.yml` non valido: la console elenca ogni errore                                                             |
| NPC "di legno" senza skin di Citizens | Citizens assente o in errore: in console "using Mannequin bodies"                                                  |
