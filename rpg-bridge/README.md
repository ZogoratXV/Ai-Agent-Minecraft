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

## Comandi di prova (console)
- `rpgbridge selftest` — prova completa con un client simulato (vedi *Verifica*).
- `rpgbridge selftest <nome> stay` — come sopra, ma il bot resta connesso: puoi dargli oggetti
  (`give`, `mi give ...`), cambiargli classe (`mmocore admin class <nome> <classe>`) e poi
  `rpgbridge bot attack <nome>` (attacco Better Combat con l'oggetto in mano, riporta il danno) o
  `rpgbridge bot attack <nome> vanilla` (attacco vanilla); `rpgbridge bot leave <nome>` lo disconnette.

## Pelican / Pterodactyl
1. Crea (o usa) un server Minecraft Purpur/Paper e nella scheda **Startup** scegli come **Docker Image** quella con
   **Java 25**.
2. Con il **File Manager** (o SFTP) carica nella cartella principale: `purpur.jar` (Purpur 26.2), `start.sh`,
   `rpgbridge-loader.jar`, `rpgbridge-log4j2.xml`; in `mods/`: `rpgbridge-core.jar` + le mod dei giocatori.
   Se l'egg usa un altro nome per il jar del server (es. `server.jar`), rinominalo `purpur.jar`
   o aggiungi `PURPUR_JAR=server.jar` al comando qui sotto.
3. **Startup Command** (scheda Startup; se non è modificabile serve un admin del pannello):
   `PURPUR_JAR={{SERVER_JARFILE}} MEMORY=6500M bash start.sh --nogui`
   con `MEMORY` circa all'80% della memoria del server (es. 6500M su 8 GB, 3300M su 4 GB).
   In alternativa `start.sh` accetta la stessa riga di `java`: basta sostituire `java ... -jar {{SERVER_JARFILE}}`
   con `bash start.sh ... -jar {{SERVER_JARFILE}}` (con `-Xmx` o `-XX:MaxRAMPercentage` `MEMORY` non si applica).
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

## Plugin testati
ProtocolLib 5.5.0, MythicLib 1.7.1, MMOCore 1.13.1, MMOItems 6.10.1: si avviano e funzionano (oggetti MMOItems,
classi MMOCore, `required-class`: con la classe sbagliata MMOItems non applica le statistiche dell'arma, sia con
l'attacco vanilla sia con quello di Better Combat). MMOItems vede anche gli attributi delle mod (`spell-power:*`).
ItemsAdder 4.0.18 non parte su Purpur 26.2, **anche senza RPG Bridge** (`NoClassDefFoundError:
beer/devs/fastnbt/nms/Version`): serve una versione di ItemsAdder che supporti 26.2.

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
