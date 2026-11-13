# Regole dello stile "rpg_armature"

_Bozza scritta osservando i riferimenti (Cleric Armor Expanded, 5 tier). Correggila liberamente._

## Formato e struttura
- Armature 3D indossabili per GeckoLib / MythicArmor: formato a bone (usa `bedrock` nello spec), texture 256x256, densità 1 px/unità.
- Gerarchia dei bone come nei riferimenti: per ogni parte un bone `biped*` (pivot del corpo) e dentro un bone `armor*`:
  `bipedHead/armorHead` (pivot 0,24,0), `bipedBody/armorBody` (0,24,0), `bipedRightArm/armorRightArm` (5,22,0),
  `bipedLeftArm/armorLeftArm` (-5,22,0), `bipedRightLeg/armorRightLeg` e `armorRightBoot` (2,12,0), `bipedLeftLeg/armorLeftLeg` e `armorLeftBoot` (-2,12,0).
  Verifica sempre i nomi richiesti dalla versione di GeckoLib/MythicArmor che usi.
- Il corpo del giocatore (testa 8x8x8, busto 8x12x4, braccia e gambe 4x12x4) è il volume da rivestire: i pezzi stanno sopra, mai dentro.
- Armature ricche: 175–200 cubi. Molte piastre sottili ruotate liberamente e cubi con inflate per gli strati sovrapposti.

## Progressione dei tier
- Il tier 1 è sobrio; ogni tier successivo aggiunge volumi (spallacci più grandi, mantello/tabarro, corona o aureola sull'elmo) e più finiture d'oro e gemme.
- La silhouette deve cambiare in modo riconoscibile tra un tier e l'altro, non solo i colori.

## Materiali e colore
- Base metallica fredda (grigio-azzurro, acciaio chiaro) con stoffe blu notte.
- Finiture in oro caldo e gemme ciano/verde acqua; accenti rossi su nastri e decorazioni.
- Palette contenuta (18–27 colori), alto contrasto, hue shift moderato, nessun outline sui bordi.
