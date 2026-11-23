# Tessera — server ibrido Fabric + Bukkit per Minecraft 26.2

Tessera è un server Minecraft **26.2** che carica nello stesso processo:

- **mod Fabric** dalla cartella `mods/`
- **plugin Bukkit / Spigot / Paper** dalla cartella `plugins/`

È costruito come Arclight: l'API Bukkit/Paper viene implementata sopra il server vanilla da una mod Fabric (`tessera-core`). Un **launcher** (`tessera-launcher.jar`) installa e aggiorna tutto e avvia il server.

> **Stato: prototipo.** Il ciclo di vita dei plugin, i comandi, gli eventi principali, lo scheduler, i permessi e la configurazione funzionano. Gran parte dell'API (inventari, entità diverse dal giocatore, scoreboard, metadati degli oggetti…) non è ancora implementata. Vedi [Cosa funziona](#cosa-funziona) e [Limiti](#limiti-noti).

## Avvio rapido

Requisito: **Java 25** ([Adoptium](https://adoptium.net/)). Minecraft 26.2 non parte con versioni precedenti.

```bash
cd tessera
./gradlew build            # produce tessera-launcher/build/libs/tessera-launcher-<versione>.jar
```

Copia `tessera-launcher-<versione>.jar` in una cartella vuota, rinominalo `tessera-launcher.jar` e avvialo:

```bash
java -jar tessera-launcher.jar
```

Al primo avvio il launcher:

1. scarica il server vanilla 26.2 da Mojang e ne verifica lo SHA-1;
2. scarica Fabric Loader e le sue librerie da `maven.fabricmc.net` (senza passare da meta.fabricmc.net);
3. mette in `mods/` Fabric API e la mod Tessera (inclusa nel launcher);
4. chiede di accettare la [EULA di Minecraft](https://aka.ms/MinecraftEULA);
5. avvia il server e lo riavvia se va in crash (al massimo 5 volte in un'ora).

Poi metti le mod in `mods/` e i plugin in `plugins/` e riavvia.

### Opzioni del launcher

| Opzione | Effetto |
|---|---|
| `--dir <percorso>` | cartella del server (predefinita: quella corrente) |
| `--install-only` | scarica/aggiorna i file e termina |
| `--accept-eula` | accetta la EULA senza chiedere |
| `--no-restart` | non riavvia dopo un crash |

Le impostazioni stanno in `tessera-launcher.properties`, creato al primo avvio. Ogni chiave si può sovrascrivere con una variabile d'ambiente `TESSERA_<CHIAVE>`:

| Chiave | Variabile | Predefinito |
|---|---|---|
| `memory.min` / `memory.max` | `TESSERA_MEMORY_MIN` / `TESSERA_MEMORY_MAX` | `1G` / `4G` |
| `jvm.args` | `TESSERA_JVM_ARGS` | flag G1GC |
| `server.args` | `TESSERA_SERVER_ARGS` | `nogui` |
| `auto-restart` | `TESSERA_AUTO_RESTART` | `true` |
| `restart.delay-seconds` | `TESSERA_RESTART_DELAY_SECONDS` | `10` |
| `restart.max-per-hour` | `TESSERA_RESTART_MAX_PER_HOUR` | `5` |
| `eula` | `TESSERA_EULA` | `false` |
| `pin-versions` | — | `false`: le versioni seguono quelle del launcher |

La mod ha la sua configurazione in `config/tessera.properties`:

- `plugins-dir`: cartella dei plugin (predefinita `plugins`);
- `strict-api`: se `true`, un metodo API non implementato lancia un'eccezione; se `false` (predefinito), registra un avviso una sola volta e restituisce un valore neutro;
- `bridge-join-quit-messages`: lascia ai plugin il controllo dei messaggi di entrata e uscita.

## Installazione per ambiente

### Linux (VPS / PC)

```bash
mkdir -p /opt/tessera && cp tessera-launcher.jar dist/linux/start.sh /opt/tessera/
cd /opt/tessera && ./start.sh
```

Per avviarlo come servizio usa `dist/linux/tessera.service`: le istruzioni sono nei commenti del file. In quel caso i riavvii li gestisce systemd.

### Windows

Metti `tessera-launcher.jar` e `dist/windows/start.bat` nella stessa cartella e fai doppio clic su `start.bat`.

### Docker

```bash
cd tessera && ./gradlew build
docker compose -f dist/docker/docker-compose.yml up -d
docker attach tessera          # console (staccati con Ctrl+P, Ctrl+Q)
```

I dati (mondi, `mods/`, `plugins/`) stanno in `dist/docker/data/`. Nel compose `TESSERA_EULA=true` indica che accetti la EULA.

### Pterodactyl / Pelican

1. Importa `dist/pterodactyl/egg-tessera.json` (Admin → Eggs → Import).
2. Pubblica `tessera-launcher.jar` a un URL raggiungibile (per esempio come asset di una release GitHub) e mettilo nella variabile **URL del launcher**. In alternativa caricalo a mano nella cartella del server.
3. Imposta **Accetta EULA** a `true`.

Il server riceve l'85% della memoria assegnata, il resto resta alla JVM del launcher. Il comando di stop è `stop`. L'egg usa l'immagine `ghcr.io/pterodactyl/yolks:java_25`: se il tuo pannello non ce l'ha, scegli un'immagine con Java 25.

## Comandi di Tessera

| Comando | Descrizione |
|---|---|
| `/plugins` (`/pl`) | elenco dei plugin (verde = attivo) e numero delle mod |
| `/tessera` | versione |
| `/tessera mods` | elenco delle mod Fabric |
| `/tessera unsupported` | metodi API che i plugin hanno chiamato ma Tessera non implementa ancora. È il modo più rapido per capire cosa manca a un plugin. |

## Cosa funziona

Verificato su un server 26.2 reale con `scripts/smoke-test.sh` e il plugin `test-plugin/`.

- **Plugin**: `plugin.yml` e i campi base di `paper-plugin.yml`, ordine per `depend` / `softdepend` / `loadbefore`, `onLoad` / `onEnable` / `onDisable`, classloader per plugin con accesso alle classi degli altri plugin, `libraries:` scaricate da Maven Central (senza dipendenze transitive).
- **Comandi**: comandi e alias da `plugin.yml`, forma `plugin:comando`, completamento, controllo dei permessi. I comandi entrano nell'albero Brigadier, quindi il client li completa e convivono con quelli vanilla e delle mod. `Server#dispatchCommand` passa anche ai comandi vanilla e delle mod.
- **Eventi**: `PlayerJoinEvent` e `PlayerQuitEvent` (con messaggio modificabile), `AsyncPlayerChatEvent` (formato, testo, destinatari, annullamento), `BlockBreakEvent`, `BlockPlaceEvent`, `PlayerInteractEvent` (click destro e sinistro su blocco, click destro in aria), `ServerLoadEvent`. Sono supportate priorità e `ignoreCancelled`.
- **Scheduler**: `runTask*`, `schedule*` e `BukkitRunnable`, sia sincroni sul thread del server sia asincroni, più `callSyncMethod`.
- **Permessi**: `permissions:` di `plugin.yml`, default op e non-op, attachment, op vanilla.
- **Giocatori**: nome, UUID, messaggi (testo legacy `§` e componenti Adventure), action bar, posizione, teletrasporto, modalità di gioco, vita, fame, kick, op.
- **Mondi e blocchi**: nomi in stile Bukkit (`world`, `world_nether`, `world_the_end`), `getBlockAt`, `getType` / `setType`, `getHighestBlockYAt`, spawn, seed, orario.
- **Material, BlockType, ItemType, ItemStack**: registri reali costruiti da quelli vanilla, quindi includono anche blocchi e oggetti delle mod. `ItemStack.of(...)` e `new ItemStack(...)` funzionano per tipo e quantità.
- **Config**: `getConfig`, `saveDefaultConfig` e YAML (SnakeYAML incluso).
- **Servizi**: `ServicesManager`, `Messenger` (solo la registrazione dei canali), `ServerBuildInfo` (risponde "compatibile" a chi controlla il brand Paper).

## Limiti noti

- **Niente NMS/CraftBukkit**: i plugin che usano classi interne del server (`net.minecraft.server.vX`, `org.bukkit.craftbukkit`) non funzionano. È così su tutti gli ibridi.
- **API parziale**: inventari, entità diverse dal giocatore, `ItemMeta` e incantesimi, scoreboard, boss bar, particelle, suoni e la maggior parte dei registri (tranne blocchi e oggetti) non sono ancora implementati. Con `strict-api=false` restituiscono valori neutri e compaiono in `/tessera unsupported`.
- **Pochi eventi**: oltre a quelli elencati sopra non viene lanciato nessun evento. Mancano movimento, danni, entità, inventari, comandi dei giocatori, redstone e altri.
- **Chat**: se un plugin cambia formato o destinatari, il messaggio viene inviato come messaggio di sistema, cioè senza la firma crittografica di Minecraft.
- **Paper plugin**: `bootstrapper`, `loader` e il ciclo di vita Paper (`LifecycleEventManager`, comandi Brigadier di Paper) non sono supportati.
- **Mod e plugin che toccano la stessa cosa** possono entrare in conflitto: questo vale per ogni ibrido.

## Architettura

```
tessera/
├── tessera-stubgen/    generatore di codice (eseguito durante la build)
├── tessera-core/       la mod Fabric: implementazione dell'API Bukkit/Paper
│   ├── stubs.txt       interfacce Bukkit per cui generare uno stub
│   └── src/main/java/dev/tessera/
│       ├── bukkit/     Server, Player, World, Block, ItemStack, console…
│       ├── plugin/     PluginManager, classloader dei plugin, librerie
│       ├── scheduler/  BukkitScheduler guidato dai tick
│       ├── command/    ponte SimpleCommandMap → Brigadier, /plugins, /tessera
│       ├── event/      eventi Fabric/vanilla → eventi Bukkit
│       ├── mixin/      join/quit e piazzamento blocchi
│       ├── registry/   registri BLOCK e ITEM
│       └── service/    servizi interni di Paper (ServiceLoader)
├── tessera-launcher/   installer + avvio + riavvio (Java puro, nessuna dipendenza)
├── test-plugin/        plugin Bukkit di prova
├── scripts/smoke-test.sh
└── dist/               linux/, windows/, docker/, pterodactyl/
```

**Stub generati.** `org.bukkit.entity.Player` da solo ha centinaia di metodi. Invece di scriverli a mano, `tessera-stubgen` legge per reflection le interfacce elencate in `stubs.txt` e genera classi `Stub*` (in `build/generated`) in cui ogni metodo chiama `Unsupported.hit(...)`. Le classi `Tessera*` estendono gli stub e sovrascrivono solo ciò che è implementato. Aggiornare l'API Paper a una nuova versione richiede quindi solo di rigenerare gli stub.

**Per estendere Tessera**: con `/tessera unsupported` vedi cosa chiama un plugin. Poi sovrascrivi quei metodi nella classe `Tessera*` corrispondente. Per un'interfaccia nuova aggiungila a `stubs.txt` e crea la sua classe. Per un evento nuovo aggiungi un callback Fabric o una mixin in `event/` e `mixin/`.

Minecraft 26.x non è più offuscato: le mixin e il codice usano direttamente i nomi ufficiali di Mojang, senza mappature.

## Versioni

Verificate il 2026-11-23:

| Componente | Versione |
|---|---|
| Minecraft | 26.2 (Java 25) |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.161.0+26.2 |
| Fabric Loom | 1.19.4 (Gradle 9.8.2) |
| Paper API | 26.2.build.135-stable |
| Adventure | 5.2.0 |

Le versioni si cambiano in `gradle.properties`.

## Test

```bash
./gradlew build
./scripts/smoke-test.sh     # avvia un vero server 26.2: accetti la EULA per il server di test
```

Lo smoke test installa il server con il launcher, carica `test-plugin` e lancia dalla console `tesseratest`, `hello`, `plugins` e `tessera unsupported`. Poi ferma il server e controlla il log. La CI (`.github/workflows/tessera.yml`) esegue la build e lo smoke test.
