# Mago Nero — kit (RPG Bridge, Spell Engine 1.10, Minecraft 26.2)

Classe di maghi della scuola **Anima** (`spell_power:soul`: esiste in Spell Power ma la RPG Series non la usa), fatta
solo con dati, nel formato documentato da Spell Engine (spell, tag del libro e delle pergamene, set di equipaggiamento,
componenti degli oggetti). **Bastone, vesti, libro e pergamena sono quelli della mod Wizards**: gli oggetti veri
(`wizards:staff_arcane`, `wizards:netherite_arcane_robe_*`) e i modelli del libro/pergamena arcani, che il client ha già.
Il kit cambia solo nome, statistiche, spell e set. Nel resource pack ci sono solo testi e le icone delle 4 spell.

## Contenuto
| Cosa | Oggetto | Statistiche |
|---|---|---|
| Bastone delle Ombre | `wizards:staff_arcane` | danno 4, velocità −3,0, **Potere anima +6**, spell Dardo d'Ombra |
| Cappuccio / Veste / Gambali / Calzari del Mago Nero | `wizards:netherite_arcane_robe_head/chest/legs/feet` | armatura 1/3/2/1, ogni pezzo **anima +25%** e **rapidità +2%** |
| Set "Paramenti del Mago Nero" | `castamir:mago_nero` | 2 pezzi: anima +10%; 4 pezzi: critico +10%, rapidità +5% |
| Tomo della Tenebra | libro di Spell Engine, pool `#castamir:spell_book/mago_nero` | Drenaggio Vitale, Maledizione, Nube di Tenebra |

Valori presi dalla scala dei Wizards (bastone 6 di potere, vesti 0,25 + 0,02 per pezzo). Ogni oggetto ha
`custom_data {rpgbridge_id:"castamir:..."}` per il blocco per classe.

Attacco base del bastone e sei spell da apprendere al tavolo di legatura (Tomo della Tenebra): tre di danno e tre
maledizioni. Con CastigoBellica le spell si sbloccano coi sigilli (sezione `spell` di `classi.yml` del bridge).

| Spell | Tipo | Blocco | Cosa fa | Ricarica |
|---|---|---|---|---|
| `castamir:dardo_ombra` | attacco base | sempre | proiettile, danno anima 0,8 × potere | arma |
| `castamir:drenaggio_vitale` | danno | Novizio | danno anima 0,6 e cura il mago 0,35 | 6 s |
| `castamir:globo_ombra` | danno | Esperto | globo che esplode: danno anima 0,7 nel raggio di 3,5 blocchi | 10 s |
| `castamir:raggio_abisso` | danno | Maestro | raggio canalizzato 4 s, danno anima 0,45 a impulso | 14 s |
| `castamir:maledizione` | maledizione | Novizio | danno anima 0,3 + Avvizzimento 8 s, livello col potere (max 4) | 12 s |
| `castamir:nube_tenebra` | maledizione | Esperto | nube di 4,5 blocchi per 8 s: danno 0,15/s, lentezza II, cecità | 20 s |
| `castamir:marchio_rovina` | maledizione | Maestro | Debolezza (col potere, max III), Lentezza, bersaglio luminoso per 12 s | 18 s |

Tutte costano 1 runa dell'anima. Le staffe del Mago Nero di CastigoEquipaggiamento 0.4 lanciano il Dardo d'Ombra e danno
potere dell'anima: con il tomo equipaggiato lanciano le spell apprese.
Rune: `runes:soul_stone`. Le spell del tomo stanno al tavolo di legatura (Spell Binding), come quelle dei Wizards.

## Installazione
1. **Datapack**: `datapack/castamir_mago_nero` in `world/datapacks/`, poi riavvio o `/reload`.
2. **Resource pack**: copia TUTTA la cartella `resourcepack/castamir_mago_nero/assets/castamir` (con `items/`, `lang/`
   e `textures/`, non solo le texture) nel resource pack del server. Con ItemsAdder:
   `plugins/ItemsAdder/contents/castamir_mago_nero/resourcepack/assets/castamir/...`, poi `/iazip` e rientra.
   Senza `items/spell_book` e `items/spell_scroll`, i tomi e le pergamene creati al tavolo di legatura (modello
   `castamir:spell_book/mago_nero`) appaiono come cubo nero e viola. Il tomo del comando kit usa direttamente il
   modello dei Wizards e si vede comunque.
3. **Classe MMOCore**: `plugins/MMOCore/classes/mago_nero.yml`, poi `/mmocore reload`.
4. **Blocco per classe**: regola di `config/rpgbridge/classi-mago-nero.yml` in `config/rpgbridge/classi.yml`, poi
   `/rpgbridge ricarica`.
5. Oggetti allo staff: `/function castamir:mago_nero/kit` (bastone, 4 vesti, tomo da legare, 64 rune).
   Ai giocatori: ricette nel banco da lavoro (teschio di wither sopra un bastone arcano; rosa del wither sopra un pezzo
   di veste arcana di netherite). Se usate CastigoFabbro o altri plugin per il crafting, togliete
   `data/castamir/recipe` e date gli oggetti col comando o con i vostri plugin.

Alternativa MMOItems (facoltativa): `plugins/MMOItems/item/mago_nero.yml` + `config/rpgbridge/armi-mago-nero.yml`
danno un bastone e un grimorio MMOItems con le stesse spell tramite i bonus del bridge.

## Provato sul server di prova (bot, classe MAGO_NERO, set completo)
- `/function castamir:mago_nero/kit` caricata ed eseguita senza errori.
- Attributi: potere anima 14,7 (= (6+1) × 2,1), critico +10, rapidità +13, armatura 7: set a 4 pezzi attivo.
- Dardo d'Ombra 11,8 danni a un husk; con il tomo nello slot Trinkets: Maledizione 7,4, Drenaggio 8,8, Nube 6,6.
- Classe Human: bastone bloccato; classe Mago Nero: consentito.
- Il tomo in **mano secondaria** non aggiunge le spell al bastone: va nello slot "spell book" di Trinkets.
- Non provato su un client vero: aspetto in gioco, icone, nomi tradotti, tavolo di legatura con questo tomo.

## Modificare
`python3 strumenti/genera_kit.py .` (dalla cartella del kit, serve Pillow) rigenera datapack e resource pack: valori,
effetti, testi, icone. Le icone delle spell sono pixel art originale; i grafici di Castamir possono sostituire i PNG in
`resourcepack/.../textures/spell/` (16×16) senza toccare altro.
