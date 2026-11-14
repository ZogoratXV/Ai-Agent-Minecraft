# RPG Bridge (Purpur 26.2)

Plugin GPL-3.0 che permette ai client Fabric con RPG Series + Better Combat di entrare su Purpur.
Stato: tappa 1 (handshake di configurazione + item modded). Dettagli tecnici in `CLAUDE.md`.

## 1. Generare il dump (una volta per versione del modpack)
Serve un server Fabric 26.2 headless con **le stesse mod dei client** + `dumper/build/rpgbridge-dumper.jar`.
```
dumper/build.sh                       # compila il dumper (richiede jars/ e dumper/run/ come in CLAUDE.md)
cd dumper/run && java -Dfabric.gameJarPath=server.jar -cp "libs/*" net.fabricmc.loader.impl.launch.knot.KnotServer nogui
```
Il server si avvia, scrive `rpgbridge-dump/` e si ferma da solo.

## 2. Installare su Purpur
```
plugin/build.sh
cp plugin/build/RPGBridge.jar <purpur>/plugins/
cp -r dumper/run/rpgbridge-dump <purpur>/plugins/RPGBridge/dump
```
Avviare Purpur: nel log deve comparire `Injected 180 modded items`.

## 3. Test
Entrare con il client modded, poi `/give @s wizards:wand_arcane` (o `/rpgbridge give wizards:wand_arcane`).
Nel log del server: `<nome>: modded handshake completed`.
