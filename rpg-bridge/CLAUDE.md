# RPG Bridge — note per lo sviluppo

Obiettivo: client Fabric 26.2 con RPG Series + Better Combat giocano su **Purpur 26.2**, senza abbandonare Purpur.
Uso utente in `README.md`. Lingua del progetto: italiano.

## Architettura (decisa nella sessione 2, dopo i tentativi plugin+dump e Ignite)
**Ibrido Purpur + Fabric Loader.** Le mod originali (incluse quelle All Rights Reserved, fornite
dall'admin come jar) girano nel server; il bridge contiene solo codice nostro (GPL-3.0) e porzioni
adattate di Fabric API (Apache-2.0), Spell Engine (GPL-3.0), Ranged Weapon API (MIT), con attribuzione.
- `loader/` — `PurpurGameProvider` estende `MinecraftGameProvider` di Fabric Loader 0.19.5:
  stesso gioco (`net.minecraft.server.Main`), lancio da `org.bukkit.craftbukkit.Main`;
  `PurpurEntrypointPatch` mette `Hooks.startServer` prima di `"server.properties"` in
  `Main.main(OptionSet)` e `setGameInstance` dopo `new DedicatedServer`. Avvio con
  `-Dfabric.skipMcProvider=true -Dfabric.gameJarPath=versions/26.2/purpur-26.2.jar`, classpath:
  librerie del loader + `libraries/` di Purpur **senza** `org/ow2/asm` (Paper ha ASM 9.9, Fabric 9.10)
  e `-Dlog4j.configurationFile=rpgbridge-log4j2.xml` (la config di Paper richiede jline nel system CL).
- `core/` — mod Fabric `rpgbridge`:
  - `compat/MixinBlocker` (preLaunch): rimuove mixin interi elencati in `rpgbridge-mixin-compat.properties`
    (cerca anche le config già consumate in `MixinProcessor.configs/pendingConfigs`).
  - `compat/MixinPatcher`: lavora sui `MixinInfo.state.classNode` (NON `getClassNode()`, che è una copia)
    e aggiorna il `ClassInfo` del mixin. Operazioni:
    - `rpgbridge-mixin-patches.properties`: `<mixin>#<handler>=<motivo>` → toglie l'annotazione
      dell'iniettore (o `@Invoker/@Accessor`); `=args+:<tipi>` / `=args^:<tipi>` aggiunge parametri
      in coda/in testa all'handler (sposta i local, azzera la signature generica);
      `=retype:<i>:<tipo>` cambia il tipo di un parametro che l'handler non legge (Yumi `Main.main(String[])`
      → `main(OptionSet)`); `=method:<selettore>` riscrive il `method` dell'iniettore quando il selettore per
      solo nome prende l'overload sbagliato (Yumi `bootStrap` → `bootStrap(Ljava/lang/Runnable;)V`).
    - automatico: `@Inject` per nome con overload esteso di Paper (stessi argomenti + parametri in coda)
      → retarget all'overload esteso + `args+`; se Paper ha dato un valore di ritorno: CI → CIR.
    - automatico: `@Shadow` di campo il cui tipo Paper ha ristretto (es. `LevelChunk.level`: ServerLevel).
  - `compat/MixinCompat`: error handler; `-Drpgbridge.mixinSurvey=true` scrive tutti i fallimenti non
    critici in `rpgbridge-mixin-report.txt`.
  - `mixin/replace/**`: reimplementazioni per Paper (registry freeze con `bootStrap(Runnable)` di Paper,
    comandi nel costruttore a 3 argomenti, attachment in `addAdditionalSaveData(ValueOutput, boolean)`,
    reload datapack `reloadRecipes()`, danni/scudo Spell Engine su `actuallyHurt(...EntityDamageEvent)`
    e `applyItemBlocking(...,boolean)`, `shoot(...,float drawStrength)`, danno frecce RWA su
    `spawnProjectile(Projectile, ServerLevel, ItemStack)`).
  - `mixin/paper/**`: Moonrise `isHardColliding` calcolato prima di `entityData` (rinviato se fallisce);
    `completeTask` dei task di configurazione Fabric spostato sul thread del server (Paper lancia eventi
    Bukkit sincroni quando riprende la configurazione).
  - `mixin/bukkit/**` + `bukkit/ModdedEntities`: tipi entità modded → `EntityType.UNKNOWN`, wrapper Craft
    concreto più vicino alla gerarchia NMS.
  - `mixin/bukkit/AbstractContainerMenuMixin` + `bukkit/ModdedMenus`: CraftBukkit rende astratto
    `AbstractContainerMenu.getBukkitView()`; @Overwrite con corpo di default (i menu vanilla lo sovrascrivono):
    `CraftInventoryView` con in alto un Container vivo sugli slot non del giocatore. `TrinketInventoryMixin`
    (@Pseudo su `TrinketInventoryImpl`): metodi Container di CraftBukkit mancanti (getContents, onOpen, ...).
    Scan delle classi delle mod che estendono AbstractContainerMenu / implementano Container: ripeterlo per mod nuove.
  - Rete Fabric API ↔ Paper/Bukkit (mod client che parlano con plugin, Velocity):
    `paper/LoginQueryAnswerMixin` (risposte login query di Fabric → `QueryAnswerPayload` per Velocity modern
    forwarding, letta dall'inizio: Fabric la passa col readerIndex in fondo); `paper/RawPluginMessageMixin`
    (`DiscardedPayload` scritto come byte grezzi anche se una mod ha un codec per quel canale, es. `minecraft:register`);
    `replace/networking/PaperCommonCustomPayloadMixin` (sostituisce l'handler comune di Fabric che lanciava
    "Unknown addon" per i payload di gioco che Paper passa a super; inoltra a Paper (`readChannelIdentifier`, sul
    thread del server) i canali di `minecraft:register/unregister` che Fabric decodifica come `RegistrationPayload`
    e consuma, altrimenti `Player.sendPluginMessage` verso le mod client veniva scartato) +
    `PaperGameChannelRegistrationMixin` (stesso inoltro in gioco, priorità 900 prima di Fabric).
  - `selftest/SelfTestBot` + comando `rpgbridge selftest`: client nella JVM del server (usa i registri del
    server = quelli che il client ricostruisce col registry sync), fa da client Fabric (risponde a
    `minecraft:register`, `c:version`, `c:register`, `fabric:registry/sync`, `fabric:accepted_attachments_v1`,
    `fabric:custom_ingredient_sync`, ack Spell Engine/Better Combat), entra, riceve la bacchetta, lancia
    Arcane Bolt (serve `runes:arcane_stone`) e controlla proiettile + danno, attacco Better Combat, poi piazza
    e usa lo Spell Binding Table (pacchetto use-item-on) e chiude il menu. Inoltre: risponde al modern forwarding di
    Velocity se attivo in `paper-global.yml` (segreto letto dalla config), riceve un `minecraft:register` grezzo,
    manda un payload su un canale plugin, verifica i canali noti a Bukkit e i plugin message nei due sensi (usa
    il primo plugin caricato). Il packet limiter di Paper è disattivato sulla connessione del bot (lato client).
- `tools/MixinAudit.java` (`tools/audit.sh`): confronta i mixin di tutte le mod con Purpur e vanilla
  (MISSING, NOPOINT, ORDINAL, SHADOW, INVOKER, DESCRIPTOR). I `[also absent in vanilla]` sono spesso falsi
  positivi del matcher, ma non sempre (RWA `spawnProjectile`): verificare.
- `loader/.../LaunchArgs`: scrive l'argfile di avvio (`.rpgbridge/launch.txt`): librerie del loader, poi SOLO le
  librerie di `META-INF/libraries.list` di purpur.jar (mai tutto `libraries/`: il library loader di Paper ci scarica
  anche le librerie dei plugin, es. netty 4.1 di ItemsAdder → NoSuchMethodError al secondo avvio), senza ASM di Paper.
  Usato da start.sh e run.bat. (`core/run-hybrid.sh`, solo sviluppo, usa ancora `find libraries`.)
- `dist/start.sh`: launcher per l'admin (paperclip patch-only, download Fabric Loader, classpath, avvio);
  librerie del loader elencate a mano (niente python: i container Pelican non lo hanno), servono bash/curl/find.
  `dist/run.bat`: stesso launcher per Windows (librerie del loader 0.19.5 elencate a mano, classpath in un
  argfile Java una riga per jar con `\` di continuazione: le variabili cmd sono limitate a 8191 caratteri).
  Non provato su Windows reale. Il `.bat` deve restare con fine riga CRLF (`.gitattributes`).

## Ambiente di sviluppo (sessioni cloud)
- Ambiente "Ai-Agent-Minecraft": host aperti per Purpur/Paper, Modrinth (api/cdn), maven.fabricmc.net,
  piston-data.mojang.com; Maven Central e github.com (git) raggiungibili.
- JDK 25: `apt-get install -y openjdk-25-jdk-headless` → `/usr/lib/jvm/java-25-openjdk-amd64`.
  Nei comandi di build `unset JAVA_TOOL_OPTIONS` evita il rumore; per avviare server/paperclip che
  scaricano qualcosa lasciarlo (contiene il proxy).
- `./deps.sh` prepara `deps/` e `jars/` (in .gitignore). `run-hybrid/` = server di prova
  (`core/run-hybrid.sh`), `run-dist/` = prova del launcher da zero.
- Sorgenti di riferimento: GPL/Apache/MIT clonati o scaricati (Spell Engine/Spell Power branch 26.2 su
  GitHub, sorgenti Fabric API/Loader da maven.fabricmc.net). Le mod ARR hanno repo pubblici ma vanno solo
  lette, mai copiate. La decompilazione è stata bloccata dal classificatore: non serve.

## Stato (fine sessione 2)
- Mod extra supportate oltre al modpack di `deps.sh`: Trinkets Updated 4.1.1 (con Yumi MC Foundation 1.1.1)
  e Bundle API 4.0.0 (`deps/extra/`, non scaricate da `deps.sh`); con queste 68 mod self-test PASSED e sync
  Trinkets (`trinkets:sync_slots/inventory`) verso il client.
- Purpur 26.2 build 2633 parte con 63 mod; ricette/loot/tag/registri dinamici delle mod caricati nativamente.
- Self-test: PASSED (handshake completi, `wizards:wand_arcane`, Arcane Bolt colpisce, attacco Better Combat
  con `C2S_AttackRequest` colpisce).
- Verificati da console: entità modded (Spirit Wolf), blocchi + block entity (Spell Binding), effetti
  (Frozen), attributi (Spell Power).

- Testato da un utente con client reale su Windows (68 mod): login ok; crash all'apertura dello Spell Binding
  (getBukkitView astratto) risolto, ora coperto dal self-test.

- Plugin (jar dell'utente, non nel repo, in `run-dist/plugins`): ProtocolLib 5.5.0, MythicLib 1.7.1, MMOCore 1.13.1,
  MMOItems 6.10.1 ok; `rpgbridge selftest <nome> stay` + `rpgbridge bot attack <nome> [vanilla]` per provare classi
  e oggetti dalla console. ItemsAdder 4.0.18 fallisce anche su Purpur liscio (FastNbt `nms/Version` non caricata).

- Server reale dell'utente: Pelican, dietro Velocity, plugin TAB/voicechat/MMO/ItemsAdder, mod client proprie
  "Castigo*" (solo client, Fabric networking su canali `castigo...` verso plugin Bukkit): coperte dai fix di rete.
- Noto: MythicLib/MMOItems NPE su `ItemStack.getType()` null per oggetti delle mod (Material assente) negli eventi
  di danno; il danno passa comunque.

## Prossimi passi
- Test con un **client reale** (log client `.minecraft/logs/latest.log`).
- Better Combat: combo multiple, armi a due mani/dual wield, attacco a più bersagli.
- Self-test per le altre classi (Paladin heal, Rogue stealth, Archer bow) e per blocchi/menu
  (Spell Binding table, rune crafting): apertura menu e interazioni.
- Material/ItemType di Bukkit per item modded: verificare eventi inventario con plugin comuni.
- Strutture/villaggi delle mod in un mondo generato non piatto; villager professions.
- Permission API / eventi Fabric disattivati: riattivarli con sostituti se una mod futura li usa
  (controllare con lo scan dei riferimenti `net/fabricmc/fabric/api` nei jar delle mod).
