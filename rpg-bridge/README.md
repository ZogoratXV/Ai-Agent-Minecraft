# RPG Bridge — RPG Series su Purpur 26.2

RPG Bridge fa girare **le mod originali** della RPG Series (Wizards, Paladins & Priests,
Rogues & Warriors, Archers, Runes), Spell Engine, Spell Power e Better Combat **dentro un server
Purpur 26.2 non modificato**, insieme ai normali plugin Bukkit/Paper/Purpur.
I giocatori entrano con il loro client Fabric e le stesse mod, come su un server Fabric.

## Come funziona
- **Fabric Loader** (Apache-2.0) avvia Purpur tramite un *game provider* dedicato
  (`rpgbridge-loader.jar`): le mod Fabric vengono caricate e i loro mixin applicati alle classi di Purpur.
- La mod **`rpgbridge-core.jar`** (GPL-3.0) rende compatibili Fabric API e le mod con le modifiche
  di Paper: disattiva o adatta automaticamente i mixin che non combaciano col codice di Paper e
  reimplementa quelli necessari (registri, comandi, salvataggio dati, danni, armi a distanza,
  configurazione di rete, entità modded viste da Bukkit, ...).
- I jar delle mod **non** fanno parte del bridge: l'admin usa gli stessi jar dei giocatori
  (scaricati da Modrinth). Niente viene ridistribuito.

## Installazione
1. Server con **Java 25**.
2. Nella cartella del server: `purpur.jar` (Purpur 26.2) e i file di `dist/out/`:
   `start.sh` (Linux) o `run.bat` (Windows), `rpgbridge-loader.jar`, `rpgbridge-log4j2.xml`.
3. In `mods/`: `rpgbridge-core.jar` + **le stesse mod dei giocatori** (versioni Fabric per 26.2):
   Fabric API, Spell Engine, Spell Power, Better Combat, Wizards, Paladins & Priests,
   Rogues & Warriors, Archers, Runes, Cloth Config, Player Animation Library,
   Armor Model API, Structure Pool API, Ranged Weapon API.
4. Avvio: `./start.sh nogui` su Linux, `run.bat nogui` (o doppio clic su `run.bat`) su Windows
   (variabili: `JAVA`, `MEMORY`, `JVM_ARGS`; su Windows serve `curl.exe`, incluso da Windows 10 1803).
   Il primo avvio fa applicare a paperclip le patch di Purpur e scarica Fabric Loader
   da `maven.fabricmc.net`. I plugin vanno in `plugins/` come sempre.

## Pelican / Pterodactyl
1. Crea (o usa) un server Minecraft Purpur/Paper e nella scheda **Startup** scegli come **Docker Image** quella con
   **Java 25**.
2. Con il **File Manager** (o SFTP) carica nella cartella principale: `purpur.jar` (Purpur 26.2), `start.sh`,
   `rpgbridge-loader.jar`, `rpgbridge-log4j2.xml`; in `mods/`: `rpgbridge-core.jar` + le mod dei giocatori.
   Se l'egg usa un altro nome per il jar del server (es. `server.jar`), rinominalo `purpur.jar`
   o aggiungi `PURPUR_JAR=server.jar` al comando qui sotto.
3. **Startup Command** (scheda Startup; se non è modificabile serve un admin del pannello):
   `MEMORY={{SERVER_MEMORY}}M bash start.sh nogui`
   (se il container viene chiuso per memoria esaurita, usa un valore più basso, es. `MEMORY=3500M` su 4096 MB).
4. Il primo avvio scarica Minecraft (paperclip) e Fabric Loader: il container deve poter uscire su internet.
   Lo script usa solo `bash`, `curl` e `find`, presenti nelle immagini Java di Pelican.

## Verifica
Dalla console: `rpgbridge selftest`. Un client Fabric simulato entra nel server, completa tutti gli
handshake (registry sync, Spell Engine, Better Combat, Fabric API), riceve una `wizards:wand_arcane`
lancia *Arcane Bolt* su uno zombie e ne colpisce un altro con un attacco Better Combat.
Risultato atteso nel log: `[SelfTest] PASSED: Fabric handshakes, modded item, wizards:arcane_bolt hit (...), Better Combat attack hit (...)`.

## Menu e inventari delle mod
I menu delle mod (es. Spell Binding Table) hanno per Bukkit una vista generica: in alto gli slot del menu
(tipo `CHEST`), in basso l'inventario del giocatore. Gli eventi Bukkit di apertura/chiusura/click funzionano.

## Limiti noti
- Server pensato per client **modded**: un client vanilla non può entrare (riceverebbe contenuti
  sconosciuti), esattamente come su un server Fabric con queste mod.
- Per Bukkit le entità delle mod hanno tipo `EntityType.UNKNOWN` (con la classe Craft più vicina:
  `Monster`, `Creature`, `Projectile`, ...). Plugin che enumerano `Material`/`EntityType` vedono solo
  i contenuti vanilla.
- Testato con un client simulato, non ancora con un client reale.
- Aggiornando Purpur o una mod, rieseguire `tools/audit.sh` e il self-test (vedi CLAUDE.md).

## Sviluppo
`./deps.sh` (dipendenze) → `./build.sh` (produce `dist/out/`) → `core/run-hybrid.sh nogui` (server di prova).
Dettagli tecnici in `CLAUDE.md`. Licenza: GPL-3.0-only.
