# Sicurezza

Principio: **il plugin non si fida mai del foreman né dell'output dell'LLM.** Ogni richiesta viene
validata nel plugin, che ha l'ultima parola.

## Già in vigore (Fasi 2-3)

| Regola                                                                                                                                                                                      | Dove                                      |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| Il foreman ascolta solo su loopback o rete privata                                                                                                                                          | `foreman/src/config.ts` (`isPrivateHost`) |
| Il plugin si connette solo a `ws://` loopback/privato, senza DNS                                                                                                                            | `plugin/.../config/SettingsLoader.java`   |
| Token condiviso di almeno 16 caratteri, solo da variabile d'ambiente o file fuori da git                                                                                                    | `resolveToken`, `bridge.token-file`       |
| Ruolo del server (production/staging) deciso solo dal `config.yml` del plugin                                                                                                               | `ServerRole`                              |
| Comandi: whitelist per parole iniziali + lista di divieti fissa (op, deop, ban, pardon, whitelist, lp/luckperms, permessi, stop, reload, execute, ...) anche con namespace (`minecraft:op`) | `CommandPolicy`                           |
| Caratteri di controllo e comandi oltre 256 caratteri rifiutati                                                                                                                              | `CommandPolicy`                           |
| Blacklist dei blocchi (TNT, lava, fuoco, command block, spawner, portali, bedrock, barrier, ...)                                                                                            | `config.yml` `world.block-blacklist`      |
| Audit JSONL di ogni azione, scritto fuori dal main thread                                                                                                                                   | `AuditLog`                                |
| Il protocollo rifiuta versioni diverse, tipi sconosciuti e tool sconosciuti                                                                                                                 | `protocol/`, `Envelope`                   |

## In arrivo

- Regioni autorizzate, limite di volume, protezione dei container (Fase 6).
- Git safety (niente push, niente rete git, niente firma) e policy dei comandi shell (Fase 4).
- Merge, deploy e costruzioni solo con approvazione umana con permesso `agentcraft.approve` (Fase 4-6).
- In produzione solo strumenti di lettura e costruzione in regione; test e modifiche dei plugin solo su staging (Fase 5).
