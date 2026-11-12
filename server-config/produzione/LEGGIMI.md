# AgentCraft sul server principale (production)

File pronti da copiare sul server, con la stessa struttura di cartelle:

| File                                           | Cosa contiene                                                                                      |
| ---------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `plugins/AgentCraftServer/config.yml`          | `role: production`, agenti Claude con chiave API, comandi ridotti, costruzioni più lente e piccole |
| `plugins/AgentCraftServer/foreman/config.json` | 1 agente alla volta, budget 3 $/giorno, 40 $/mese, 1 $/task, nessuna repository di codice          |

## Passi

1. Ferma il server.
2. Copia `AgentCraftServer-<versione>.jar` in `plugins/` (togli le versioni vecchie).
3. Copia i due file qui sopra nelle stesse cartelle, sostituendo quelli esistenti.
4. **Chiave API**: crea `plugins/AgentCraftServer/anthropic.key` con dentro solo la chiave
   (console.anthropic.com, meglio una dedicata ad AgentCraft con un limite di spesa). Su Pelican:
   File Manager → cartella del plugin → New File. Non metterla in git e non incollarla in chat.
   Senza chiave il plugin funziona lo stesso: NPC e comandi ci sono, gli agenti restano fermi e
   `/agent status` lo dice. Per gli NPC senza AI metti `backend: sim`.
5. **Non** usare qui `claude-token-file` (il token del tuo abbonamento personale).
6. Avvia il server e controlla in console `AgentCraft ready: server 'castamir' (production)`.
7. Permessi con LuckPerms (di default solo gli op):

   ```
   lp group admin permission set agentcraft.admin true
   lp group staff permission set agentcraft.use true
   lp group staff permission set agentcraft.goal true
   ```

   `agentcraft.approve` (approvare costruzioni e decisioni), `agentcraft.manage` e
   `agentcraft.region` solo a chi è fidato.

8. In gioco, in una zona staff: `/agent base set base`, poi le altre stazioni (`desk`, `podium`,
   `worksite`, …) così gli NPC non girano tra i giocatori. Controlla con `/agent status`: deve dire
   `production`.
9. Costruzioni: crea le regioni solo dove si può costruire (`/agent region pos1`, `pos2`,
   `create <nome>`).

## Cosa possono fare gli agenti qui

- Leggere metriche, log (senza IP né segreti) e usare i comandi `list`, `tps`, `mspt`,
  `time query`, `weather query`.
- Costruire **solo** nelle regioni e **solo** dopo che hai approvato l'anteprima; annullabile.
- Rispondere e analizzare (es. `/agent goal analizza il lag di stasera`).

## Cosa non possono fare mai qui

Installare o sostituire plugin, riavviare il server, usare il giocatore di prova (rifiutati dal
plugin in production), comandi come op, ban, lp, stop, reload, execute, piazzare blocchi vietati o
toccare casse con oggetti.

Il lavoro sul codice dei plugin si fa dal server di prova o dal PC (`local-test/`), non da qui.
Pelican: lascia 1-2 GB di RAM del container fuori da `-Xmx` per Node e gli agenti.
