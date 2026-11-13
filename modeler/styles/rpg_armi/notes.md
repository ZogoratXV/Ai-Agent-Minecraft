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

## Tecniche di costruzione (osservate nei riferimenti, da seguire)
- **Lame slanciate**: larghezza della lama circa 1/8–1/10 della lunghezza (es. 3–4 unità su 28–32). Mai lame "a pala".
- **Lame sfaccettate**: la lama non è un piano unico. Si costruisce con un'anima centrale più spessa e 1–2 listelli
  laterali più sottili (spessore a scalare, es. 1.5 → 1 → 0.5) che formano i fili; la punta con 1–2 cubi ruotati a 45°.
- **Guardie angolari**: pezzi ruotati a 22.5°/45° che si allargano verso l'esterno (ali, punte, uncini), non barre dritte.
- **Impugnature a fasce**: alternanza di fasciature colorate e ghiere/anelli di un altro materiale, con qualche cubo
  ruotato per suggerire l'avvolgimento.
- **Pomoli sfaccettati**: a punta, a rombo o a goccia, costruiti con cubi ruotati; mai un semplice cubo.

## Tecniche di texture (da seguire)
- **Lame a fasce verticali pulite**: filo chiarissimo di 1 px sui bordi, tono medio, anima centrale più scura (o con
  incisione). Nessun rumore casuale: la variazione viene da fasce, riflessi e pochi segni disegnati a mano.
- **Contrasto tra materiali sempre**: almeno 3 materiali ben distinti per tono e colore (es. metallo della lama,
  metallo scuro/oro delle finiture, stoffa o cuoio colorati dell'impugnatura). Anche un'arma di un solo metallo
  usa una lega diversa o una patina per le finiture e un colore deciso per la fasciatura.
- **Bordi delle parti**: ogni pezzo ha un lato in luce chiaro e un lato in ombra scuro, che ne definiscono la forma.
- **Niente puntinato**: graffi e usura come brevi linee intenzionali, non pixel sparsi.
