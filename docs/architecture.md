# Architettura

Corpo e cervello sono separati.

- **Plugin** (`plugin/`, nel server Purpur): NPC, movimento, world tools, comandi `/agent`, GUI,
  regioni, audit. Non tiene stato di business e non fa mai rete o I/O sul main thread.
- **Foreman** (`foreman/`, processo Node separato): agenti (Claude Agent SDK), task graph,
  decisioni, memoria, worktree git, build/test, budget. Possiede tutto lo stato e lo salva su disco.
- **Protocollo** (`protocol/`): schemi zod da cui vengono generati JSON Schema, fixture e
  [docs/protocol.md](protocol.md). Il plugin ha un test di contratto che li legge.

## Flusso

1. Il plugin si connette al foreman (`ws://127.0.0.1:7878`) con il token, invia `hello` con ruolo
   del server (production/staging), integrazioni e limiti.
2. Il foreman risponde con `snapshot` e poi con aggiornamenti (`agent.upsert`, `task.upsert`, ...).
3. I comandi degli admin diventano messaggi (`goal.submit`, `decision.answer`, ...) con l'`actor`.
4. Quando un agente deve agire nel mondo, il foreman invia `tool.call`; il plugin valida tutto
   (regione, blacklist, whitelist, limiti), esegue sul thread giusto, scrive l'audit e risponde
   con `tool.result`. I lavori lunghi (costruzioni) mandano `job.progress`.

## Threading nel plugin

- Bridge, parsing JSON, audit, lettura log: thread dedicati.
- Modifiche al mondo: scheduler di regione/entità di Paper (compatibili Folia), a lotti di
  `world.blocks-per-tick` blocchi per tick, in pausa se l'MSPT supera la soglia.

## Fasi

Vedi [phase1-analysis.md](phase1-analysis.md#8-roadmap-confermata-con-criteri-di-uscita).
