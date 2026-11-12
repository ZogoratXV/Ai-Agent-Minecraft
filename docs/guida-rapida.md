# Guida rapida: AgentCraft Server sul vostro server

Versione 0.3.1. Il plugin funziona con la **squadra simulata** (gratis, per provare) e con gli
**agenti Claude veri** (codice dei plugin, sezione 11; costruzioni, sezione 12). Si avvia su
**Windows** e su **Pelican/Pterodactyl** (sezione 13). Riferimento completo:
[admin-guide.md](admin-guide.md).

## 1. Cosa serve

|           |                                                                                                                                                                                       |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Server    | Purpur **26.2** su Java **25**                                                                                                                                                        |
| Foreman   | niente: è nel jar; Node 22 lo scarica il plugin se manca (sezione 13)                                                                                                                 |
| Opzionale | **Citizens** (NPC con skin e pathfinding di Citizens; senza, si usano i Mannequin vanilla). WorldGuard, WorldEdit, LuckPerms e spark vengono rilevati; li useremo nelle prossime fasi |

## 2. Il foreman parte da solo

Il foreman (il "cervello" degli agenti) è dentro il jar. Con `foreman.managed: true` (default) il
plugin, all'avvio del server:

1. usa il **Node 22+** della macchina; se manca (es. container Pelican) scarica Node 22.23.4 in
   `plugins/AgentCraftServer/runtime/`, una volta sola, controllandone l'impronta ufficiale;
2. genera un token segreto in `plugins/AgentCraftServer/token.txt`, se non ce n'è già uno;
3. avvia il foreman, lo riavvia se si ferma e lo spegne insieme al server.

File e log del foreman: `plugins/AgentCraftServer/foreman/` (`foreman.log`). Non c'è niente da
avviare a mano.

**Se preferisci avviarlo tu** (altra macchina, servizio di sistema): imposta
`foreman.managed: false`, dai lo stesso token a entrambi con la variabile `AGENTCRAFT_TOKEN` (o
`bridge.token-file` nel plugin e `"tokenFile"` in `foreman/config.json`) e avvia
`npm ci && cd foreman && npm start -- --backend sim`. Se un foreman è già acceso su `bridge.url`,
il plugin usa quello anche con `managed: true`.

## 4. Installare il plugin

1. Prendi il jar: dall'artefatto `agentcraft-server-plugin` della CI su GitHub, oppure
   `npm ci && cd plugin && ./gradlew build` (esce in `plugin/build/libs/`).
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
| `world.build-blocks-per-second`              | 10                                                                   | velocità delle costruzioni, per vedere i costruttori al lavoro (una casa di 300 blocchi ≈ 30 secondi)                                       |
| `world.blocks-per-tick`                      | 200                                                                  | tetto di blocchi per tick (anche velocità di `/agent undo`); abbassalo se cala il TPS                                                       |
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
  assegna; la squadra li esegue (simulata o con gli agenti veri, sezioni 11 e 12).
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
| 4 ✔  | agenti Claude veri sui vostri plugin: worktree git, build e test, merge solo dopo la tua approvazione, budget |
| 6 ✔  | costruzioni: progetto, anteprima, approvazione, costruzione blocco per blocco, annulla, regioni               |
| 5    | deploy sul server di staging, scenari di test in gioco, report dei bug                                        |
| 7    | analisi di TPS/economia/drop, GUI, Discord, rifiniture                                                        |

## Problemi comuni

| Sintomo                               | Causa probabile                                                                                                    |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `Foreman: non connesso`               | foreman spento, porta diversa in `bridge.url`, o token diverso tra i due (in console: "foreman refused the token") |
| "No bridge token" in console          | manca `AGENTCRAFT_TOKEN` o `token-file` nell'ambiente del server                                                   |
| Il plugin si disattiva all'avvio      | `config.yml` non valido: la console elenca ogni errore                                                             |
| NPC "di legno" senza skin di Citizens | Citizens assente o in errore: in console "using Mannequin bodies"                                                  |
| "a foreman is already running"        | è rimasto acceso un foreman vecchio: Windows `Get-Process node \| Stop-Process`, Linux `pkill -f foreman.mjs`      |
| Un agente "torna a posto" senza fare  | guarda `/agent feed` e `/agent tasks`: il motivo è scritto lì. `/agent task <id> retry` per riprovare              |
| Matita non costruisce                 | serve una regione di almeno 9×9×9 (`/agent region create`); il progetto vero richiede qualche minuto               |

## 11. Agenti veri sui plugin (Fase 4)

Con `foreman.backend: claude` gli agenti lavorano davvero sul codice dei vostri plugin.

**Cosa serve sulla macchina del server**

- Niente da installare a mano, anche nel container Java di **Pelican/Pterodactyl**: se mancano,
  il plugin scarica **Node 22.23.4** e **Maven 3.9.16** in `plugins/AgentCraftServer/runtime/`,
  controllandone l'impronta ufficiale, e installa il Claude Agent SDK con npm. Serve che il
  container possa raggiungere nodejs.org, repo1.maven.org e registry.npmjs.org (solo al primo avvio).
- **git** (c'è già nelle immagini Java di Pelican/Pterodactyl).
- **Memoria**: build Maven e agenti girano nello stesso container del server. Date al server di
  test almeno 2-3 GB in più del solito, o tenete `maxConcurrent` basso.
- Una **chiave API** di Claude (console.anthropic.com), meglio dedicata ad AgentCraft. Mettila in un
  file nella cartella del plugin, leggibile solo dall'utente del server (`chmod 600`), e scrivi
  `api-key-file: anthropic.key` sotto `foreman:`. La chiave va solo al foreman, non a tutto il server.
  Il login Claude personale dell'utente del server **non viene mai usato**: senza chiave gli agenti
  restano fermi e `/agent status` lo dice.
- In alternativa alla chiave, **solo per uso personale sul proprio PC**: `claude-token-file` con un
  token del proprio abbonamento Claude creato con `claude setup-token`. Gli agenti consumano i limiti
  del piano. Da non usare su un server dove altre persone danno lavoro agli agenti.
- Una **copia locale** della repo dei plugin (es. `git clone … /srv/agentcraft/repos/castigo-ecosistema`),
  sul branch base (`main`) e senza modifiche non salvate.

**Configurazione della repo**

In `plugins/AgentCraftServer/foreman/config.json` (vedi `foreman/config.example.json`):

```json
{
  "serverName": "Castamir",
  "repos": [
    {
      "path": "/srv/agentcraft/repos/castigo-ecosistema",
      "forbidden": ["CastigoClassi", "CastigoMagia", "CastigoMagic", "CastigoCombat"],
      "sparseAlways": ["scripts", "docs/plugins", "docs/collaborazione", "skills"]
    }
  ]
}
```

Oppure in gioco: `/agent repo add /srv/agentcraft/repos/castigo-ecosistema` (le esclusioni però si
impostano solo nel file).

**Come lavorano**

1. `/agent goal Correggi il bug del prelievo in CastigoBanca`: Bussola legge la repo (sola lettura,
   segue `AGENTS.md`) e crea i task.
2. Ogni worker lavora in una **copia sparse** tutta sua (`worktrees/`), su un branch
   `agentcraft/<agente>/<task>`. Per castigo-ecosistema crea una **nuova cartella di versione**
   (es. `CastigoBanca-2.7.0-alpha.2`): le cartelle esistenti si possono solo leggere e copiare.
3. Il foreman registra le modifiche e lancia `mvn -B -q test` nella cartella toccata. Se i test
   falliscono, il task torna al worker una volta.
4. Bussola rivede il diff e vi chiede l'approvazione in chat: `[1 Merge] [2 Request changes] [3 Reject]`.
   `/agent diff <task>` mostra il riepilogo e dove si trova il diff completo.
5. Con **Merge** il foreman unisce il branch nella copia locale, come vostro commit di merge.
   Se nella copia ci sono modifiche non salvate, il merge viene rifiutato e la decisione si ripresenta.
6. **`/agent push`** (permesso `agentcraft.approve`) pubblica il lavoro unito su GitHub. Lo può
   fare solo una persona: gli agenti non hanno alcun accesso alla rete git. Il push non è mai forzato.

**Configurare il push** (in `repos[]` di `foreman/config.json`):

```json
"push": { "remote": "origin", "branch": "agentcraft/lavori", "tokenFile": "/home/container/plugins/AgentCraftServer/github.token" }
```

- `tokenFile`: token GitHub _fine-grained_ limitato alla sola repo, permesso _Contents: Read and write_;
  oppure `sshKeyFile`: una _deploy key_ con scrittura. File leggibili solo dall'utente del server.
- `branch`: dove pubblicare. Le regole di castigo-ecosistema chiedono rami dedicati e PR: conviene
  un ramo come `agentcraft/lavori` da cui aprire la PR su GitHub, invece di `main`.

**Cosa non possono fare**: push o qualsiasi accesso di rete con git, firmare commit, modificare
fuori dalla propria copia, toccare i plugin esclusi o le cartelle di versione esistenti, usare la
rete o comandi sconosciuti senza il vostro permesso (vi arriva una decisione con
`Allow once / Always allow for this agent / Deny`), superare i tetti di spesa.

Nota tecnica: le copie sparse fanno impostare a git `extensions.worktreeConfig=true` nella config
della copia locale. È normale e innocuo.

## 12. Costruzioni (Fase 6)

Gli agenti costruiscono edifici nel mondo, ma **solo dentro una regione autorizzata** e solo dopo
che un admin ha visto l'anteprima e ha detto "Costruisci".

**1. Crea una regione** (permesso `agentcraft.region`). Vai su un angolo della zona, all'altezza del
terreno, e scrivi `/agent region pos1`; vai sull'angolo opposto, più in alto di quanto servirà
l'edificio, e scrivi `/agent region pos2`; poi `/agent region create piazza`. Dalla console:
`/agent region create piazza world x1 y1 z1 x2 y2 z2`. Elenco e rimozione: `/agent region list`,
`/agent region remove piazza`.

**2. Chiedi l'edificio**: `/agent goal costruisci una casa medievale nella piazza`.
Bussola affida il lavoro a **Matita** (architetta), che guarda la regione e disegna il progetto.

- Con la **squadra simulata** Matita usa sempre la stessa casa medievale d'esempio (gratis).
- Con gli **agenti veri** Matita disegna davvero quello che chiedete.

**3. Guarda l'anteprima.** Il progetto appare come **blocchi fantasma**, visibili solo a chi ha
`agentcraft.approve` ed è online: niente cambia davvero nel mondo. In chat arriva
"Costruire …?" con quanti blocchi, che misure e quali materiali:

- **Costruisci** → si parte;
- **Modifica** → scrivi cosa cambiare: `/agent answer <id> 2 il tetto più alto e in pietra`;
- **Rifiuta** → non si costruisce niente.

**4. La costruzione.** Mattone (e Cazzuola, se in turno con `/agent spawn cazzuola`) vanno al
cantiere e costruiscono **un modulo alla volta** (fondamenta, muri, tetto, interni), a
`world.build-blocks-per-second` blocchi al secondo (10 di default), con una barra di avanzamento
in alto. Se il server rallenta (MSPT oltre `world.pause-when-mspt-above`) il lavoro aspetta.
Il piazzamento non usa l'AI: costa solo il progetto.

**5. Annullare**: `/agent undo t3` rimette com'era tutto ciò che ha costruito il task t3 (anche a
metà). `/agent jobs` elenca i lavori.

**Cosa non possono fare mai**: costruire fuori dalla regione, usare blocchi vietati
(`world.block-blacklist`: TNT, lava, fuoco, command block, spawner, portali, bedrock, barriere…),
toccare contenitori con oggetti, piazzare senza la vostra approvazione. Anche se il foreman
sbagliasse, il plugin rifiuta da solo.

## 13. Avvio su Windows e su Pelican

Lo **stesso jar** va bene per entrambi. Il plugin trova o scarica da solo quello che serve
(Node 22, Maven, Claude Agent SDK) e avvia il foreman: non c'è nient'altro da installare.

**Windows (PC di prova)**

1. Java 25 e Purpur 26.2: la cartella `local-test/` del repository ha `start.bat` (doppio clic)
   che li prepara e avvia il server.
2. Copia `AgentCraftServer-<versione>.jar` in `plugins\`, avvia, poi chiudi con `stop`.
3. Per gli agenti veri con il tuo abbonamento: `claude setup-token`, salva il token in
   `plugins\AgentCraftServer\claude.token` (attenzione: Windows può aggiungere `.txt` da solo) e
   in `config.yml` metti `backend: claude` e `claude-token-file: claude.token`. Riavvia.
4. Se chiudi il server dalla X invece che con `stop`, il foreman si spegne da solo entro pochi
   secondi.

**Pelican / Pterodactyl (server di test condiviso)**

1. Egg **Paper/Purpur** con immagine **Java 25** (l'immagine `java_25` delle yolks).
   Carica Purpur 26.2 e il jar in `plugins/` dal file manager o via SFTP.
2. **Rete in uscita**: al primo avvio il container deve raggiungere `nodejs.org`,
   `registry.npmjs.org` e (per i plugin Maven) `repo1.maven.org`; con gli agenti veri anche
   `api.anthropic.com`. Il bridge usa `127.0.0.1:7878` **dentro** il container: non serve
   allocare porte e non va esposto.
3. **Memoria**: Node, agenti e build Maven girano nello stesso container, fuori dalla memoria di
   Java. Se il container ha 8 GB, non dare a Java più di 5-6 GB (`-Xmx`, o il campo memoria
   dell'egg con un po' di margine), altrimenti Pelican uccide il server per memoria esaurita.
4. **Disco**: circa 300 MB per Node e SDK in `plugins/AgentCraftServer/runtime/` e
   `foreman/`, più le copie di lavoro delle repository.
5. **Agenti veri**: su un server condiviso usa una **chiave API** (`api-key-file`), non il token
   dell'abbonamento personale. Crea il file della chiave dal file manager di Pelican nella
   cartella del plugin.
6. Il pulsante **Stop** del pannello spegne anche il foreman; se il container viene ucciso, il
   foreman se ne accorge e si chiude da solo.

Su Linux il plugin estrae Node e Maven in Java puro: non servono `tar`, `xz` o `unzip` nel
container. Verificato qui su Linux senza Node, npm e Maven installati; Windows è verificato dal vostro
server di prova (avvio, foreman, abbonamento), non da prove automatiche.
