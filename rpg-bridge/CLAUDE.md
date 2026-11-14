# Progetto: RPG Series Bridge per Purpur 26.2

## Obiettivo
Plugin per **Purpur 26.2** che permetta a client Fabric 26.2 con le mod della RPG Series
(Wizards, Paladins & Priests, Rogues & Warriors, Archers) + Better Combat di giocare su Purpur,
senza abbandonare Purpur. I client tengono le mod originali; il server parla il loro protocollo.

Vincoli:
- Le mod di classe sono **All Rights Reserved**: non copiare né redistribuire il loro codice/asset.
  Il bridge reimplementa il protocollo (interoperabilità) e può leggere a runtime i JSON dai jar
  forniti dall'admin, senza ridistribuirli.
- **Spell Engine è GPL-3.0-only** → il bridge sarà GPL-3.0 se ne reimplementa la logica.
- Dal 26.1 il server non è offuscato: Purpur/Paper e le mod usano nomi Mojang, niente remapping.

## Versioni analizzate (jar in ./jars)
- wizards / paladins / rogues / archers `3.1.3.003+26.2`
- spell_engine `1.10.9+26.2`
- bettercombat `3.2.2` (26.2)
- Richiedono Java >= 25, fabricloader >= 0.19.3.

Dipendenze dichiarate non ancora analizzate (da procurare): fabric-api, spell_power (>=1.6.1+26.2),
runes (>=1.3.2+26.2), armor_model_api (>=1.1.0+26.2), ranged_weapon_api (>=4.0.0+26.2, Archers),
structure_pool_api, bundleapi (Archers), cloth-config (>=26.2.155), player_animation_library,
slot mod (trinkets_updated / accessories).

## Risultati dell'analisi
- Le 4 mod di classe **non hanno codice di rete**: solo contenuti + dati.
- Rete: tutto in **Spell Engine** (`net.spell_engine.network.Packets`) e **Better Combat**
  (`net.bettercombat.network.Packets`).

### Canali Spell Engine (`spell_engine:*`)
Configurazione (S→C): `config_sync` (ServerConfig in JSON/Gson), `spell_registry_sync` (List<String> chunk JSON del registro incantesimi)
Configurazione (C→S): `ack` (String code)
Play C→S: `cast_request`, `target_stream`, `cast_input`, `attack_perform`, `attack_fx_broadcast`
Play S→C: `spell_cooldown`, `cooldown_sync`, `spell_message`, `particle_effects`, `spell_animation`,
`spell_container_sync`, `attack_available`
Handshake lato server: `FabricServerNetwork` registra task su `ServerConfigurationConnectionEvents.CONFIGURE`
(ConfigurationTask + SpellRegistrySyncTask) e attende `ack`.

### Canali Better Combat (`bettercombat:*`)
`ack`, `config_sync`, `weapon_registry` (configurazione); `attack_animation`, `attack_sound`,
`block_hit`, attack request C2S, swing particles (play).

### Contenuti da emulare (conteggio da lang en_us)
| mod | item | effect | entity | block | spell JSON |
|---|---|---|---|---|---|
| wizards | 55 | 10 | 7 | 0 | 25 |
| paladins | 71 | 12 | 6 | 2 | 15 |
| rogues | 60 | 22 | 5 | 2 | 12 |
| archers | 48 | 6 | 4 | 2 | 6 |
| spell_engine | 4 | 8 | 2 | 2 | 10 |
Dati: ricette, advancement, strutture, villager_trade, weapon_attributes, tag (riferiscono ID modded).

### Formati dei pacchetti di configurazione (verificati sul bytecode, sessione 2)
Tutti sono custom payload (`ClientboundCustomPayloadPacket` / `ServerboundCustomPayloadPacket`).
`writeUtf` = VarInt lunghezza + UTF-8; `writeInt` = int32 big-endian.

| canale | dir | corpo |
|---|---|---|
| `spell_engine:config_sync` | S→C | `writeUtf(gson(ServerConfig))` |
| `spell_engine:spell_registry_sync` | S→C | `writeInt(n)` + n × `writeUtf(chunk)`; chunk = pezzi da 10000 char di `gson(SyncFormat{containers: Map<String itemId, SpellContainer>})` (= `data/*/spell_assignments/**`) |
| `spell_engine:ack` | C→S | `writeUtf(code)`: `spell_engine:config` / `spell_engine:spell_registry` |
| `bettercombat:config_sync` | S→C | `writeUtf(gson(ServerConfig))` |
| `bettercombat:weapon_registry` | S→C | `writeBoolean(compressed)` + `writeInt(n)` + n × `writeUtf(chunk)`; JSON `SyncFormat{attributes: Map<String, AttributesContainer>, registrations: Map<String, WeaponAttributes>}`, se compressed → gzip + Base64; chunk 10000 char |
| `bettercombat:ack` | C→S | `writeUtf(code)`: `bettercombat:config` / `bettercombat:weapon_registry` |
| `fabric:registry/sync` | S→C | vedi RegistrySyncPayload (Apache-2.0): gruppi per namespace, run di raw id consecutivi; namespace `minecraft` ottimizzato a `""` |
| `fabric:registry/sync/complete` | C→S | vuoto |
| `c:version` | ↔ | `writeVarIntArray([1])` |
| `c:register` | ↔ | `VarInt version, Utf protocol("play"/"configuration"), Collection<Identifier>` |

Lato server originale: i task vengono aggiunti solo se `canSend(canale)` (il client ha registrato il canale),
altrimenti il server Fabric disconnette. Il server attende l'ack per chiudere ogni task.

**Incantesimi = registro dinamico sincronizzato** `minecraft:spell` (`Identifier.withDefaultNamespace("spell")`,
dati in `data/<ns>/spell/*.json`), più `equipment_set` (Spell Engine). Viaggiano nel `ClientboundRegistryDataPacket`
vanilla, non in un canale custom. Codec di rete (`SpellRegistry$1`): con NbtOps ogni voce è
`{data: ByteArray(UTF-8 del JSON Gson dello Spell)}` → il bridge può inviare il JSON letto dai jar così com'è.
Purpur non conosce questo registro: il bridge deve iniettare il pacchetto.

### Registry sync di Fabric (fabric-registry-sync-v0 7.1.1, sorgenti letti da maven.fabricmc.net)
- Il server invia solo i registri con attributo SYNCED+MODDED; il client li rimappa in modalità **REMOTE**.
- REMOTE: un ID inviato dal server ma sconosciuto al client → disconnessione; una voce del client **non**
  inviata dal server viene **scartata**. Quindi il bridge deve mandare l'elenco completo (vanilla + tutte le mod del client).
- Se il server non invia nulla, il client tiene gli ID locali (vanilla in ordine vanilla, modded in coda in ordine
  di caricamento → non prevedibile). Meglio inviare sempre la sync.
- Gli ID vanilla dei client Fabric coincidono con quelli vanilla/Purpur.
- Per ottenere la tabella completa: **mod "dumper"** propria su un server Fabric headless con lo stesso modpack,
  che chiama `RegistrySyncManager.createAndPopulateRegistryMap()` e salva la mappa (+ attributi) su file.
  Il bridge carica quel file. Così gli ID derivano dai jar dell'admin, nessun contenuto ARR viene ridistribuito.

### Purpur 26.2 (build 2633)
- `api.purpurmc.org/v2/purpur/26.2/2633/download`; `-Dpaperclip.patchonly=true` estrae `versions/26.2/purpur-26.2.jar`
  + `libraries/` → si compila direttamente contro questi jar (niente paperweight).
- Payload sconosciuti → `DiscardedPayload(id, byte[] data)` (Paper conserva i byte) e vengono inoltrati al Messenger
  Bukkit anche in fase di configurazione (`dispatchIncomingMessage(PlayerConnection, ...)`).
- API utili: `AsyncPlayerConnectionConfigureEvent` (tiene aperta la configurazione finché il listener non ritorna),
  `io.papermc.paper.network.ChannelInitializeListenerHolder` (handler Netty per connessione).

## Ostacolo principale
Registri statici (item, effect, entity, block, menu type, attributi) non esistono su Purpur:
qualsiasi pacchetto con ID modded va riscritto a livello Netty in entrambe le direzioni
(stile ViaVersion), prima del decoder di Purpur. Idea: inviare in configurazione il pacchetto di
registry sync di Fabric API (Apache-2.0) con ID vanilla + modded in ordine deciso dal bridge.
Tutta la logica di gioco (danni, proiettili, cooldown, rune, colpi Better Combat) va reimplementata.

## Piano a tappe
1. Client modded entra su Purpur senza errori (handshake Spell Engine + Better Combat in fase di configurazione) e vede una bacchetta arcana vera.
2. Un incantesimo funzionante (Arcane Bolt) con danni.
3. Classe Mago completa, poi Paladino/Sacerdote, Ladro/Guerriero, Arciere.
4. Strutture, loot, tavolo di Spell Binding.

## Ambiente (sessioni cloud)
- Ambiente "Ai-Agent-Minecraft": host aperti per Purpur/Paper, Modrinth (api/cdn), maven.fabricmc.net.
- JDK 25: `apt-get install -y openjdk-25-jdk-headless` → `/usr/lib/jvm/java-25-openjdk-amd64`.
- I jar (`jars/`, `run/`) sono in .gitignore: si riscaricano da Modrinth (`game_versions=["26.2"], loaders=["fabric"]`).

## Prossimi passi
- [x] Codec dei pacchetti di configurazione e formato di ServerConfig.
- [x] Fabric API registry sync.
- [ ] Mod dumper + server Fabric headless 26.2 con il modpack → `registry_map.json`.
- [ ] Plugin tappa 1 (Java 25, compilato contro purpur-26.2.jar): handshake c:register, registry sync,
      config Spell Engine/Better Combat, iniezione registro `minecraft:spell`.
- [ ] Spell Power: da analizzare (attributi modded, probabilmente in registro ATTRIBUTE).
