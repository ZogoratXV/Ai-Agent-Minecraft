# Mago Nero — kit di esempio (RPG Bridge)

Classe di maghi della scuola **Anima** (`spell_power:soul`, già presente in Spell Power e non usata dalla RPG Series),
fatta solo con dati: spell di Spell Engine in un datapack, classe MMOCore, armi MMOItems e regole del bridge.

## Spell (datapack `castamir_mago_nero`, namespace `castamir`)
| Spell | Cosa fa | Costo | Ricarica |
|---|---|---|---|
| `castamir:dardo_ombra` — Dardo d'Ombra | proiettile, danno anima (0,8 × potere) | 1 runa dell'anima | arma |
| `castamir:drenaggio_vitale` — Drenaggio Vitale | danno anima (0,6) al bersaglio e cura il mago (0,35) | 1 runa dell'anima | 6 s |
| `castamir:maledizione` — Maledizione | danno anima (0,3) + Avvizzimento 8 s, livello che sale col potere (max 4) | 1 runa dell'anima | 12 s |
| `castamir:nube_tenebra` — Nube di Tenebra | nube di 4,5 blocchi attorno al mago per 8 s: danno (0,15 al secondo), lentezza II, cecità | 1 runa dell'anima | 20 s |
Le rune sono `runes:soul_stone` (mod Runes). Tag per i libri: `#castamir:spell_book/mago_nero` e `#castamir:mago_nero`.

## Installazione
1. **Datapack**: copia `datapack/castamir_mago_nero` in `world/datapacks/` del server e riavvia (o `/reload`).
2. **Resource pack**: unisci `resourcepack/castamir_mago_nero/assets/castamir` al resource pack del server
   (nomi e descrizioni in italiano/inglese, icone 16×16). Senza, le spell funzionano ma con nome tecnico e senza icona.
3. **Classe MMOCore**: `plugins/MMOCore/classes/mago_nero.yml` (copia della classe Mage con nome/descrizione/icona
   cambiati: adatta abilità e statistiche). Riavvia o `/mmocore reload`.
4. **Armi MMOItems**: le voci di `plugins/MMOItems/item/mago_nero.yml` vanno in `sword.yml` (BASTONE_OMBRE) e in
   `off_catalyst.yml` (GRIMORIO_NERO), poi `/mi reload`. Classe richiesta: Mago Nero.
5. **RPG Bridge**: aggiungi le regole di `config/rpgbridge/armi-mago-nero.yml` in `config/rpgbridge/armi.yml`,
   poi `/rpgbridge ricarica`. Il bastone riceve Spell Power anima +4, stile Better Combat "staff" e le 4 spell;
   il grimorio +2 in mano secondaria.

## Provato (server di prova)
Classe MAGO_NERO, Bastone delle Ombre: Dardo d'Ombra 4 danni; Drenaggio Vitale 3 danni e cura 10 → 11,75;
Maledizione 3,5 in 4,5 s (avvizzimento ancora in corso); Nube di Tenebra 2,6 in 4,5 s; con classe Human la spell
è bloccata. Non provati: grimorio in mano secondaria, aspetto in gioco (particelle, icone, nomi) su un client vero.

## Modificare
`strumenti/genera_spell.py` rigenera datapack e resource pack (valori, effetti, nomi, icone):
`python3 strumenti/genera_spell.py <cartella>`. Il proiettile usa il modello dell'Arcane Bolt dei Wizards (più
piccolo), con particelle scure: per un modello proprio serve un modello nel resource pack.
