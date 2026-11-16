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

## Blocco degli oggetti per classe (CastigoBellica o MMOCore)
RPG Bridge può riservare oggetti a determinate classi. La classe del giocatore arriva da **CastigoBellica** (servizio
`classi()` di CastigoCore 2.1.0-beta.63 o successivo, letto per riflessione) oppure, se CastigoBellica non c'è, da MMOCore. Un oggetto riservato a un'altra classe:
- non attacca (attacco normale e Better Combat): il colpo non parte;
- non si usa (tasto destro in aria, su blocchi ed entità: archi, scudi, bacchette, armature col tasto destro);
- non lancia spell (Spell Engine);
- non resta indossato né in mano secondaria: entro mezzo secondo torna nell'inventario (a terra se pieno).
Il giocatore vede il motivo nella barra delle azioni. Tutti i controlli sono lato server.

Configurazione: `config/rpgbridge/classi.yml` (creato al primo avvio, commentato). Esempio:
```yaml
attivo: true
messaggio: "&cQuesto oggetto e' riservato a: &f{classi}"
mmoitems-classe-richiesta: true      # usa anche la "Required Class" di MMOItems come blocco vero
regole:                              # vale la PRIMA regola che corrisponde
  - oggetti: ["wizards:*", "castamir:*"]
    classi: [MAGO_NERO]
  - oggetti: ["paladins:*_mace", "paladins:*_kite_shield"]
    classi: [DIFENSORE]
  - oggetti: ["itemsadder:castigo:spada_drago"]
    classi: [GUERRIERO]
```
Oggetti: id del registro (`wizards:wand_arcane`, `wizards:*` per tutta la mod), `mmoitems:TIPO:ID`,
`itemsadder:namespace:id`, `rpgbridge_id` dei datapack (es. `castamir:*`: custom_data `{rpgbridge_id:"castamir:..."}`, prende il posto
dell'id di registro, vedi `examples/mago-nero`); `*` = qualsiasi testo. Classi: id o nome (CastigoBellica: MAGO_BIANCO,
MAGO_NERO, DIFENSORE, GUERRIERO, ASSASSINO, ARCIERE; MMOCore: i suoi id), maiuscole/minuscole indifferenti.
Gli oggetti di **CastigoEquipaggiamento** portano le loro classi nei dati (`castigoequipaggiamento:classi`) e non hanno
bisogno di regole; le regole di classi.yml hanno comunque la precedenza.
**Livello minimo per tier** (con CastigoBellica): gli oggetti di CastigoEquipaggiamento portano anche il tier
(`castigoequipaggiamento:tier`) e si usano/indossano solo con il blocco (sigillo) e il livello richiesti. Predefinito (sezione
`tier` di classi.yml, valori usati anche se la sezione manca): T1 blocco 1 (Novizio), T2 blocco 2 (Esperto), T3 blocco 3 (Maestro),
T4 blocco 4 (Leggenda), T5 blocco 4 e livello 100. Messaggio `tier.messaggio` con `{tier}` e `{requisito}`; `tier.attivo: false`
lo spegne. Il bypass vale anche qui.
Comandi (console o OP): `rpgbridge ricarica` (rilegge classi.yml, tomi.yml e armi.yml), `rpgbridge classi controlla
<giocatore>` (classe e fornitore, CastigoBellica o MMOCore, e per ogni slot compresi quelli di Trinkets: identificativi, classi, proprietario del
tomo, bonus e se è consentito).
Permesso per ignorare il blocco: `rpgbridge.classi.bypass`, **default false anche per gli OP** (assegnarlo con
LuckPerms). Senza CastigoBellica né MMOCore il blocco è inattivo. Anche gli slot di Trinkets sono controllati.

## Tomi legati al giocatore
Gli Spell Book (configurabile: `config/rpgbridge/tomi.yml`) si legano a chi li **equipaggia per primo** (slot "spell
book" di Trinkets, o mano secondaria). Il proprietario è scritto nell'oggetto, con la riga "Legato a <nome>" nella
descrizione. Gli altri giocatori non possono usarlo, equipaggiarlo o lanciarne le spell (torna nell'inventario entro
mezzo secondo); possono tenerlo nell'inventario o nei bauli. Staff: `rpgbridge tomo slega <giocatore>` scioglie il
tomo che il giocatore ha in mano; permesso `rpgbridge.tomi.bypass` (default false anche per gli OP).

## Bonus delle mod su oggetti MMOItems / ItemsAdder
In `config/rpgbridge/armi.yml` si scelgono uno per uno i bonus della RPG Series da dare a un oggetto:
```yaml
armi:
  - oggetti: ["mmoitems:STAFF:BASTONE_DEL_FUOCO"]
    attributi:                       # sommati con l'oggetto nello slot (predefinito: mano principale)
      "spell_power:fire": 4
      "spell_power:critical_chance": 5   # punti percentuali: 5 = +5% (anche haste)
    stile-attacco: bettercombat:staff   # animazioni, combo e portata di Better Combat
    spell: [wizards:fireball]           # spell di Spell Engine lanciabili con l'oggetto in mano
```
Opzioni: `operazione` (add_value, add_multiplied_base, add_multiplied_total), `slot` (mainhand, offhand, hand, head,
chest, legs, feet, armor, any), `tipo-spell` (MAGIC o ARCHERY). Il bridge scrive i bonus nell'oggetto entro un secondo
da quando è nell'inventario di un giocatore e li riscrive se MMOItems lo ricostruisce o se la regola cambia; il resto
dell'oggetto (modello, nome, statistiche MMOItems, classe richiesta) resta com'è. MMOItems nasconde gli attributi nel
tooltip: i bonus agiscono ma non compaiono nella descrizione (aggiungili alla lore MMOItems se vuoi mostrarli).

## Comandi di prova (console)
- `rpgbridge selftest` — prova completa con un client simulato (vedi *Verifica*).
- `rpgbridge selftest <nome> stay` — come sopra, ma il bot resta connesso: puoi dargli oggetti
  (`give`, `mi give ...`), cambiargli classe (`mmocore admin class <nome> <classe>`) e poi
  `rpgbridge bot attack <nome>` (attacco Better Combat con l'oggetto in mano, riporta il danno) o
  `rpgbridge bot attack <nome> vanilla` (attacco vanilla); `rpgbridge bot leave <nome>` lo disconnette.
  Durante la prova di base il bot è escluso dal blocco per classe; con `stay` poi vi è soggetto (per provare le regole).
  `rpgbridge bot cast <nome> <spell>` lancia una spell su un husk e riporta il danno; `rpgbridge bot use <nome>` fa
  tasto destro con l'oggetto in mano (es. equipaggia uno Spell Book in Trinkets).

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
ItemsAdder 4.0.18 parte (al primo avvio scarica le sue librerie, anche da `repo.alessiodp.com`).
Mod client che parlano con plugin tramite plugin channel (Fabric networking ↔ `Player.sendPluginMessage` /
listener Bukkit) funzionano nei due sensi; supportato anche il modern forwarding di Velocity.

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
