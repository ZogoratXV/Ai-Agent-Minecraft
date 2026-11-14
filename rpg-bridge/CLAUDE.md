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
      in coda/in testa all'handler (sposta i local, azzera la signature generica).
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
  - `selftest/SelfTestBot` + comando `rpgbridge selftest`: client nella JVM del server (usa i registri del
    server = quelli che il client ricostruisce col registry sync), fa da client Fabric (risponde a
    `minecraft:register`, `c:version`, `c:register`, `fabric:registry/sync`, `fabric:accepted_attachments_v1`,
    `fabric:custom_ingredient_sync`, ack Spell Engine/Better Combat), entra, riceve la bacchetta, lancia
    Arcane Bolt (serve `runes:arcane_stone`) e controlla proiettile + danno.
- `tools/MixinAudit.java` (`tools/audit.sh`): confronta i mixin di tutte le mod con Purpur e vanilla
  (MISSING, NOPOINT, ORDINAL, SHADOW, INVOKER, DESCRIPTOR). I `[also absent in vanilla]` sono spesso falsi
  positivi del matcher, ma non sempre (RWA `spawnProjectile`): verificare.
- `dist/start.sh`: launcher per l'admin (paperclip patch-only, download Fabric Loader, classpath, avvio).

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
- Purpur 26.2 build 2633 parte con 63 mod; ricette/loot/tag/registri dinamici delle mod caricati nativamente.
- Self-test: PASSED (handshake completi, `wizards:wand_arcane`, Arcane Bolt colpisce).
- Verificati da console: entità modded (Spirit Wolf), blocchi + block entity (Spell Binding), effetti
  (Frozen), attributi (Spell Power).

## Prossimi passi
- Test con un **client reale** (log client `.minecraft/logs/latest.log`).
- Better Combat: self-test di `bettercombat:attack_request` (combo, danno).
- Self-test per le altre classi (Paladin heal, Rogue stealth, Archer bow) e per blocchi/menu
  (Spell Binding table, rune crafting): apertura menu e interazioni.
- Material/ItemType di Bukkit per item modded: verificare eventi inventario con plugin comuni.
- Strutture/villaggi delle mod in un mondo generato non piatto; villager professions.
- Permission API / eventi Fabric disattivati: riattivarli con sostituti se una mod futura li usa
  (controllare con lo scan dei riferimenti `net/fabricmc/fabric/api` nei jar delle mod).
