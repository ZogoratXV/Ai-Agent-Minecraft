# Regole dello stile "rpg_armi"

_Bozza scritta osservando i riferimenti (SamusDev, Paladin Sword e Paladin Pack, RPG Greatswords 2, Fantasy Weapons 14, Sketch Weapons). Correggila liberamente: queste regole hanno priorità sulle statistiche._

## Formato e scala
- Armi come item del resource pack: formato `java_block`, texture 64x64 o 128x128.
- Densità: **2 px per unità** (la maggioranza dei riferimenti); misure dei cubi multiple di 0.5 per non avere mixel.
- Armi "oversize" da RPG: lunghezza totale circa 40–47 unità (2,5–3 blocchi nel modello, ridotte poi dai display settings).
- Numero di cubi in base al tipo: armi semplici 15–30, armi ornate 35–65, spadoni elaborati 70–100.
  La sagoma conta più dei dettagli modellati: i dettagli piccoli vanno dipinti.
- Rotazioni solo a multipli di 22.5° (vincolo Java), usate per guardie, ali, punte e decorazioni inclinate.
- Le gemme e le parti magiche possono usare una seconda texture animata (fotogrammi impilati + `.mcmeta`).

## Composizione
- Struttura leggibile in 3 parti: lama/asta, guardia/testa elaborata, impugnatura con pomolo.
- Ogni arma ha un **punto focale**: gemma o nucleo luminoso (ciano, verde acqua, rosso) incastonato nella guardia o nella testa.
- La guardia/testa è la parte più larga e ornata; l'impugnatura è sottile, con fasce alternate (cuoio + metallo).

## Materiali e colore
- Metallo della lama: grigi freddi tendenti al blu, highlight quasi bianco sul filo.
- Finiture: oro/bronzo caldo (giallo-arancio) per bordi, guardie e anelli.
- Cuoio/stoffa: rosso scuro/bordeaux o marrone caldo.
- Gemme e magia: ciano/acqua molto saturi e luminosi, unici colori davvero saturi del modello.
- Palette ristretta (12–19 colori per modello), rampe corte con hue shift marcato (ombre fredde/viola, luci calde).
- Niente outline neri sui bordi delle facce; contrasto alto fra materiali.
