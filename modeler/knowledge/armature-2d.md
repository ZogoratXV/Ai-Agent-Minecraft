# Armature: elmo 3D + armatura 2D (layer vanilla) + icone

Questa guida spiega come fare un set d'armatura Java Edition in cui solo l'elmo è un modello 3D e il resto
(corazza, gambiere, stivali) usa le texture 2D standard del gioco. Verifica sempre i percorsi esatti sulla
versione del server / del plugin (ItemsAdder, Nexo, ecc.): cambiano tra le versioni.

## 1. Elmo 3D (output "model", format "java_block")
- È un item model indossato in testa: il gioco lo mostra con `display.head`, lo scala al 62,5% e lo centra
  sulla testa. Con `display.head` a scala 1, la testa del giocatore occupa nel modello il cubo
  **[1.6, 1.6, 1.6] → [14.4, 14.4, 14.4]** (12,8 unità per lato). Costruisci l'elmo attorno a quel volume
  (spessore 1–1,5), lasciando libera la zona degli occhi se serve una visiera.
- La faccia **north** del modello è il davanti dell'elmo (verso cui guarda il giocatore).
- Coordinate Java: -16..32, rotazioni solo ±22.5/45 su un asse.
- Imposta `display.head` (di solito scala 1, traslazione 0) e anche `gui`, `ground`, `fixed` per l'inventario.
- Controlla con `render`: compare la vista "in testa".

## 2. Armatura 2D (output "armor_layer")
Due texture 64x32 nel layout vanilla del corpo del giocatore. Usa questi spec come base (coordinate del corpo
del giocatore di riferimento: alto 32, rivolto a nord, braccio destro verso +X). NON cambiare posizioni,
misure e uvOffset: sono il layout che il gioco si aspetta. Cambi solo la pittura.

**Layer 1** (busto + braccia = corazza, gambe = stivali; testa = elmo 2D, da lasciare TRASPARENTE perché
l'elmo è 3D):
```json
{ "name": "<nome>_layer_1", "format": "free", "output": "armor_layer", "pixelsPerUnit": 1,
  "texture": { "width": 64, "height": 32 },
  "cubes": [
    { "name": "head",  "from": [-4, 24, -4], "to": [4, 32, 4],  "inflate": 1, "uvOffset": [0, 0] },
    { "name": "body",  "from": [-4, 12, -2], "to": [4, 24, 2],  "inflate": 1, "uvOffset": [16, 16] },
    { "name": "arm_r", "from": [4, 12, -2],  "to": [8, 24, 2],  "inflate": 1, "uvOffset": [40, 16] },
    { "name": "arm_l", "from": [-8, 12, -2], "to": [-4, 24, 2], "inflate": 1, "uvFrom": "arm_r", "mirrorUv": true },
    { "name": "leg_r", "from": [0, 0, -2],   "to": [4, 12, 2],  "inflate": 1, "uvOffset": [0, 16] },
    { "name": "leg_l", "from": [-4, 0, -2],  "to": [0, 12, 2],  "inflate": 1, "uvFrom": "leg_r", "mirrorUv": true }
  ] }
```
- Nel layer 1 le gambe servono agli **stivali**: dipingi solo la parte bassa (circa le ultime 4–6 righe della
  gamba, più la faccia `down`) e lascia trasparente il resto.
- Busto e braccia sono la **corazza**; le braccia sono specchiate (stessa texture per destra e sinistra).

**Layer 2** (gambiere):
```json
{ "name": "<nome>_layer_2", "format": "free", "output": "armor_layer", "pixelsPerUnit": 1,
  "texture": { "width": 64, "height": 32 },
  "cubes": [
    { "name": "body",  "from": [-4, 12, -2], "to": [4, 24, 2], "inflate": 0.5, "uvOffset": [16, 16] },
    { "name": "leg_r", "from": [0, 0, -2],   "to": [4, 12, 2], "inflate": 0.5, "uvOffset": [0, 16] },
    { "name": "leg_l", "from": [-4, 0, -2],  "to": [0, 12, 2], "inflate": 0.5, "uvFrom": "leg_r", "mirrorUv": true }
  ] }
```
- Nel busto del layer 2 dipingi solo la **cintura** (le ultime 3–4 righe in basso); il resto trasparente.

Regole di pittura per i layer: pixel art pulita come le armi del set (fasce, bordi in luce/ombra, materiali
distinti), placche e bordi leggibili da lontano, niente rumore. Ricorda che i lati (east/west) e il retro
(south) si vedono: dipingili tutti in modo coerente. Controlla con `render`: compare la vista "indossata".

## 3. Icone 2D (output "sprite")
Un'icona d'inventario 16x16 per pezzo (corazza, gambiere, stivali): un piano con la sola faccia north.
```json
{ "name": "<nome>_chestplate", "format": "java_block", "output": "sprite", "pixelsPerUnit": 1,
  "texture": { "width": 16, "height": 16 },
  "cubes": [ { "name": "icon", "from": [0, 0, 8], "to": [16, 16, 8], "hideFaces": ["south"] } ] }
```
- Disegna con `pattern` (righe ASCII 16x16) o `pixels`/`line`/`rect` sulla faccia north; lo sfondo resta
  trasparente. Stile delle icone vanilla: sagoma netta con contorno scuro di 1 px, luce in alto a sinistra.
- L'export produce il PNG e un item model `item/generated`.

## Consegna
Elmo (.bbmodel/.json/.png), `<nome>_layer_1.png`, `<nome>_layer_2.png`, icone `.png` + `.json`.
Nelle versioni recenti di Java i layer si usano come texture "equipment" (umanoide e umanoide-gambiere); i
plugin di item custom hanno le loro cartelle: indica nel resoconto dove vanno e di verificarlo.
