# Guida admin

Documento vivo: cresce a ogni fase. La versione completa arriva nella Fase 7.

## Comandi disponibili

| Comando                 | Permesso         | Cosa fa                                           |
| ----------------------- | ---------------- | ------------------------------------------------- |
| `/agent`, `/agent help` | `agentcraft.use` | elenco comandi                                    |
| `/agent status`         | `agentcraft.use` | ruolo del server, stato del foreman, integrazioni |

## Permessi

`agentcraft.use`, `agentcraft.goal`, `agentcraft.approve`, `agentcraft.manage`, `agentcraft.region`,
`agentcraft.admin` (tutti). Di default solo gli op; con LuckPerms assegnali ai gruppi staff.

## Configurazione

- Plugin: `plugins/AgentCraftServer/config.yml` (commentato, vedi `plugin/src/main/resources/config.yml`).
- Foreman: `foreman/config.json`, copiato da `foreman/config.example.json`.
- Segreti: `AGENTCRAFT_TOKEN` (stesso valore per server e foreman), `ANTHROPIC_API_KEY` (solo foreman).

## Budget consigliato

3 worker contemporanei, tetto di **15 $ al giorno**, **300 $ al mese**, **5 $ per task**. Raggiunto un
tetto, gli agenti si mettono in pausa e gli admin vengono avvisati. Tutto modificabile in `config.json`.
