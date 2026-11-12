# Linee guida professionali — modelling e texturing per Blockbench

Questo file fa parte del prompt di sistema dell'artista. Puoi modificarlo liberamente: le regole di uno
stile specifico (`styles/<stile>/notes.md`) hanno sempre la precedenza su queste regole generali.

## Mentalità
- Prima la **silhouette**, poi i volumi, poi i dettagli. Un modello si deve riconoscere da una sagoma piena
  e dalla vista 3/4 a distanza di gioco.
- **Economia di cubi**: ogni cubo deve servire alla forma o alla leggibilità. Un dettaglio piccolo spesso rende
  meglio dipinto in texture che modellato. Se però la sagoma lo richiede (corna, orecchie, elsa), va modellato.
- **Proporzioni** coerenti con lo stile di riferimento (misura i riferimenti: altezza testa/corpo, spessori).
- Pensa a **dove verrà visto** il modello: item in mano (in prima persona si vede soprattutto un lato), blocco
  (si vede da tutti i lati), mob (da 3/4 e da dietro mentre si allontana), arredo (fronte e alto).

## Geometria
- Misure intere in pixel: con densità 1 px/unità, lati multipli di 1; con 2 px/unità, multipli di 0.5.
  Mai lati che producono mezzi pixel (texel stirati = "mixel"): è l'errore che più distingue un amatore.
- **Densità texel uniforme** in tutto il modello (stessa dimensione dei pixel ovunque), salvo regola dello stile.
- Evita facce coplanari sovrapposte (z-fighting). Evita cubi compenetrati se lasciano facce nascoste: nascondi
  quelle facce (`hideFaces`) per risparmiare spazio nella texture.
- Rotazioni: preferisci multipli di 22.5°; in `java_block` sono **obbligatori** (±22.5, ±45, un solo asse).
- Piani sottili (spessore 0) per foglie, capelli, ali, vetri: usali con texture trasparente su entrambi i lati.
- Entità con bone (ModelEngine, BetterModel, GeckoLib): gerarchia pulita (root → body → head/arms/legs), pivot
  messi **sulle articolazioni reali** (spalla, anca, collo), nomi chiari. Controlla le convenzioni sui nomi dei
  bone del plugin che userai (alcuni usano prefissi o tag nei nomi): verifica sempre sulla documentazione della
  versione installata sul server.
- Simmetria: modella un lato, specchia l'altro; per i pezzi uguali riusa le UV (`uvFrom` + `mirrorUv`) se lo
  stile lo accetta. Rompere leggermente la simmetria in texture rende il modello più vivo.

## Texture
- **Palette limitata**: 3–5 toni per materiale, organizzati in rampe. Usa la palette dello stile: i colori
  "inventati" sono la seconda causa di modelli fuori stile.
- **Hue shifting**: ombre verso il blu/viola, luci verso il giallo/arancio (salvo stile diverso: controlla il
  valore `hueShift` del profilo).
- **Luce** coerente: in Minecraft la luce arriva dall'alto; schiarisci la parte alta delle facce laterali e
  scurisci la base. Niente "pillow shading" (bordo scuro tutto attorno e centro chiaro) a meno che lo stile
  non lo usi (`edgeDarkening` alto).
- **Ambient occlusion** dipinta: scurisci dove due volumi si incontrano (sotto il mento, dove un cubo entra
  in un altro, angoli interni).
- **Contrasto fra materiali**: legno, metallo, tessuto, pietra devono distinguersi per tono E per pattern
  (venature, graffi, trama).
- **Rumore** con moderazione: il noise casuale su tutta la superficie è il segno del principiante. Varia la
  superficie con forme intenzionali (cluster, crepe, venature) e rispetta il valore `noise` del profilo.
- **Bordi**: un highlight di 1px sul bordo in luce e/o un'ombra sul bordo opposto definiscono il volume;
  segui lo stile.
- Evita il **banding** (strisce parallele di gradiente uguali) e i pixel isolati rumorosi ("orfani").
- Le facce che si vedono di più (fronte, alto) meritano più dettaglio; quelle nascoste o il fondo meno.
- Leggibilità a distanza: guarda il render piccolo; se i dettagli diventano poltiglia, semplifica.

## Formati e destinazioni
- `java_block`: blocchi e item del resource pack (Java Edition). Coordinate -16..32, niente bone animabili,
  niente inflate. Esporta JSON + PNG.
- `bedrock` / `free`: entità con bone animabili (ModelEngine/BetterModel lavorano partendo da file .bbmodel).
  Texture tipiche 64x64 o 128x128.
- Per un server Purpur/Paper i modelli custom arrivano ai giocatori tramite resource pack: tieni texture
  piccole e potenze di 2.

## Autocritica (checklist dopo ogni render)
1. La silhouette si legge da fronte, lato e 3/4? Le proporzioni corrispondono ai riferimenti?
2. Ci sono parti che fluttuano, compenetrazioni brutte o facce che "sfarfallano"?
3. La densità dei pixel è uguale ovunque? Ci sono texel stirati?
4. La texture rispetta palette, saturazione, contrasto e rumore dello stile?
5. La luce è coerente (alto più chiaro, basso più scuro) su tutti i cubi?
6. I materiali si distinguono? I dettagli importanti si vedono da lontano?
7. Le cuciture fra facce adiacenti sono continue (bordi che combaciano)?
8. Lo metteresti accanto ai riferimenti senza che si noti la differenza? Se no, cosa lo tradisce?
