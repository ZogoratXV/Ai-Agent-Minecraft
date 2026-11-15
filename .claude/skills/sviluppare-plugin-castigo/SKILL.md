---
name: sviluppare-plugin-castigo
description: Lavorare sull'ecosistema Castigo del server Castamir (plugin server Castigo*, mod client Castigo*Client, CastigoCore, CastigoInterfacceClient) o su integrazioni che li toccano, come RPG Bridge con le mod client Castigo. Carica dal repository condiviso Knefas90/castigo-ecosistema la skill ufficiale sempre aggiornata, invece di usare una copia locale.
---

# Sviluppare plugin Castigo (rimando alla skill ufficiale)

La skill vera vive nel repository condiviso **https://github.com/Knefas90/castigo-ecosistema**, in
`skills/sviluppare-plugin-castigo/` (`SKILL.md` + `references/regole-castigo.md`, `references/consegna-e-test.md`,
`references/standard-guide.md`). Le regole cambiano spesso (decisioni di Fabio con data): questa pagina è solo un
puntatore e **non** ne contiene una copia, che invecchierebbe.

## Prima di ogni attività Castigo

1. Rendere disponibile il repository: se la sessione non lo ha, aggiungerlo con lo strumento `add_repo`
   (owner `Knefas90`, repo `castigo-ecosistema`, accesso in lettura) e clonarlo come indicato dallo strumento
   (di norma `git clone --depth 1 https://github.com/Knefas90/castigo-ecosistema /home/user/castigo-ecosistema`,
   con timeout lungo: il repository è grande). Se esiste già un clone, aggiornarlo
   (`git -C /home/user/castigo-ecosistema pull --ff-only`) senza perdere modifiche locali.
2. Leggere **integralmente**, dal clone aggiornato:
   - `skills/sviluppare-plugin-castigo/SKILL.md`
   - `skills/sviluppare-plugin-castigo/references/regole-castigo.md` (lungo: leggerlo a pagine fino in fondo)
   - `skills/sviluppare-plugin-castigo/references/consegna-e-test.md` e `references/standard-guide.md`
   - `AGENTS.md`, `CLAUDE.md`, `INVENTARIO.md` e `docs/collaborazione/DIPENDENZE.md` / `PROTOCOLLO.md` quando pertinenti
3. Seguire quelle istruzioni: prevalgono su questa pagina. Le richieste correnti dell'utente definiscono il perimetro.
4. Se il remoto non è raggiungibile, dirlo e usare un clone esistente solo come riferimento non aggiornato.

## Punti fermi (promemoria, verificare sempre sulla skill remota)

- Castamir è il server, Castigo l'ecosistema. Esclusi CastigoClassi, CastigoMagia, CastigoCombat salvo richiesta esplicita.
- Mod client con suffisso `Client`; i plugin server senza.
- Le mod client Castigo parlano ai plugin tramite plugin channel (Fabric networking ↔ `Player.sendPluginMessage`;
  canali come `castigo:ui`, `castigo:finestra`, `castigo:banco`, più quelli dei singoli plugin, con HELLO e capacità).
  Paper limita a 128 canali per giocatore: sul server Castamir l'avvio usa `-Dpaper.disableChannelLimit=true`
  (necessario con RPG Bridge, che fa conoscere a Bukkit anche i canali delle mod Fabric).
- Pubblicazioni (PR sul repository condiviso, release, Telegram, installazione o riavvio del server) solo nei limiti
  autorizzati dalla skill remota e dalla richiesta corrente.
