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

## Agenti veri (Fase 4)

| Regola                                                                                                                                                                                           | Dove                                             |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------ |
| Solo autenticazione API (chiave o Bedrock/Vertex): mai il login claude.ai di chi fa girare il server. Senza chiave nessun turno parte                                                            | `foreman/src/agents/auth.ts`, `TeamBackend.pump` |
| Gli agenti hanno una cartella di config Claude propria (`CLAUDE_CONFIG_DIR`) e nessuna impostazione locale (`settingSources: []`)                                                                | `withApiAuthOnly`, `ClaudeRunner`                |
| La chiave può stare in un file passato solo al processo del foreman (`foreman.api-key-file`)                                                                                                     | `ForemanLauncher`                                |
| git senza trasporti (niente push/fetch/clone), senza firme, senza hook, senza uscire dalla copia                                                                                                 | `gitsafety.ts`                                   |
| Policy dei comandi e dei file: lettura/scrittura solo nella propria copia; push, rete git, config git, plugin esclusi e cartelle di versione esistenti negati; il resto è una decisione in gioco | `policy.ts`                                      |
| Il caposquadra lavora in sola lettura nella vostra copia; scrive solo nei task                                                                                                                   | `policy.ts` (`writer: false`)                    |
| Merge solo dopo revisione del caposquadra **e** approvazione umana, rifiutato se la copia ha modifiche; il push resta umano                                                                      | `TeamBackend`, `RepoManager.merge`               |
| Tetti di spesa per task, giorno e mese; raggiunti, gli agenti si fermano                                                                                                                         | `TeamBackend`                                    |

## In arrivo

- Regioni autorizzate, limite di volume, protezione dei container (Fase 6).
- Deploy e costruzioni solo con approvazione umana con permesso `agentcraft.approve` (Fasi 5-6).
- In produzione solo strumenti di lettura e costruzione in regione; test e modifiche dei plugin solo su staging (Fase 5).
