# Blueprints

Formato dei progetti che l'architetto disegna e i costruttori piazzano (Fase 6).

- Schema: [`blueprint.schema.json`](blueprint.schema.json) (generato da `protocol/src/blueprint.ts`).
- Esempio: [`examples/casa-medievale.json`](examples/casa-medievale.json), una casa 9x9x9 in 5 moduli.

In breve: `size` [x, y, z], una `palette` di lettere → blocchi (con stati, es.
`minecraft:spruce_stairs[facing=south]`, `_` = aria) e dei `modules` (fondamenta, muri, tetto…)
costruiti in ordine di dipendenza, dal basso verso l'alto. Operazioni: `set`, `line`, `fill`
(`solid`, `hollow`, `walls`, `outline`) e `layers`. Coordinate locali: x est, y su, z sud, origine
nell'angolo in basso a nord-ovest. Il plugin ruota (0/90/180/270), sposta nell'ancora dentro la
regione e controlla ogni blocco.
