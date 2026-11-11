# Staging

A separate Purpur 26.2 server where agents may install builds of your plugins, run in-game test
scenarios and use staging-only tools (`player.simulate`). Production never gets these.

| Script                              | What it does                                                                                        |
| ----------------------------------- | --------------------------------------------------------------------------------------------------- |
| `fetch-purpur.sh [version] [build]` | downloads Purpur (default 26.2, latest build) into `staging/server/` and checks its MD5             |
| `run-local.sh`                      | builds the plugin, copies it into `staging/server/plugins/` and starts the server in the foreground |
| `run-local.sh --smoke`              | same, but stops as soon as the server is up and checks that AgentCraft enabled                      |
| `docker-compose.yml`                | the same server in a container, bound to `127.0.0.1:25566`                                          |

The Minecraft EULA must be accepted by the server operator: `ACCEPT_EULA=true staging/run-local.sh`
or write `eula=true` in `staging/server/eula.txt` (see https://aka.ms/MinecraftEULA).

The plugin's `config.yml` must say `server.role: staging` on this server. Phase 5 adds the
deploy pipeline (copy jar, restart, wait for boot, run scenario, collect logs).
