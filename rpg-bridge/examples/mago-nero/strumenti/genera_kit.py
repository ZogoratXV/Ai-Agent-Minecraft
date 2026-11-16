# Genera datapack + resource pack del Mago Nero (formato Spell Engine 1.10, MC 26.2).
# Uso: python3 strumenti/genera_kit.py .   (dalla cartella examples/mago-nero; serve Pillow)
import json, math, os, sys, shutil
from PIL import Image
OUT = sys.argv[1]; NS = "castamir"
shutil.rmtree(os.path.join(OUT, "datapack"), ignore_errors=True); shutil.rmtree(os.path.join(OUT, "resourcepack"), ignore_errors=True)
DP = os.path.join(OUT, "datapack", "castamir_mago_nero"); RP = os.path.join(OUT, "resourcepack", "castamir_mago_nero")
def w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if isinstance(data, (dict, list)): json.dump(data, open(path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    else: open(path, "w", encoding="utf-8").write(data)
DARK, PURPLE = 0xFF3A0F5C, 0xFF7A2BB8
def p(pid, color=None, motion=None, count=1.0, shape="SPHERE", anchor="ENTITY", mn=0.1, mx=0.3, **extra):
    app = {}
    if color is not None: app["color"] = color
    if motion: app["motion"] = motion
    b = {"count": count, "shape": shape, "anchor": anchor, "min_speed": mn, "max_speed": mx}; b.update(extra)
    return {"id": pid, "appearance": app, "batch": b}
casting = lambda dur: {"cast": {"duration": dur, "animation": {"id": "spell_engine:one_handed_projectile_charge"},
    "sound": {"id": "spell_engine:generic_soul_casting", "randomness": 0.0},
    "particles": [p("spell_engine:magic_spell", PURPLE, "ASCEND", 1.0, "PIPE", mn=0.05, mx=0.1, vertical_origin=0.1, width_factor=2.0)]}}
release = {"animation": {"id": "spell_engine:one_handed_projectile_release"}, "sound": {"id": "spell_engine:generic_soul_release"}}
cost = lambda cd=None, group=None: {"exhaust": 0.1, "cooldown": dict(**({"duration": cd} if cd else {}), **({"group": group} if group else {})), "item": {"id": "runes:soul_stone"}}
S = {}
S["dardo_ombra"] = {"school": "spell_power:soul", "type": "ACTIVE", "range": 48.0, "tier": 0, "group": "mago_nero_arma", "learn": {},
  "active": casting(1.0), "release": release, "target": {"type": "AIM", "aim": {}},
  "deliver": {"type": "PROJECTILE", "projectile": {"projectile": {"client_data": {"light_level": 4,
      "travel_particles": [p("spell_engine:magic_spell", DARK, "ASCEND", 2.0, "CIRCLE", mn=0.05, mx=0.1, alignment="LOOK"), p("smoke", None, None, 1.0, mn=0.0, mx=0.02)],
      "composite_model": {"models": [{"fx": {"model_id": "wizards:spell_projectile/arcane_bolt", "scale": 0.45}}]}}}}},
  "impacts": [{"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.8, "knockback": 0.5}},
      "visuals": {"particles": [p("spell_engine:magic_skull", PURPLE, "BURST", 6.0, mn=0.1, mx=0.4), p("spell_engine:smoke_medium", None, None, 12.0, mn=0.05, mx=0.3)]},
      "sound": {"id": "spell_engine:generic_soul_release"}}],
  "cost": cost(group="weapon")}
S["drenaggio_vitale"] = {"school": "spell_power:soul", "type": "ACTIVE", "range": 16.0, "tier": 1, "group": "mago_nero_drenaggio", "learn": {},
  "active": casting(1.25), "release": release, "target": {"type": "AIM", "aim": {"sticky": True}},
  "impacts": [{"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.6, "knockback": 0.2}},
      "visuals": {"particles": [p("spell_engine:dripping_blood", None, None, 15.0, "PILLAR", mn=0.05, mx=0.2, vertical_origin=0.5)]}, "sound": {"id": "spell_engine:leeching_impact"}},
    {"action": {"type": "HEAL", "apply_to_caster": True, "heal": {"spell_power_coefficient": 0.35}},
      "visuals": {"particles": [p("spell_engine:magic_heal", PURPLE, "ASCEND", 10.0, "PILLAR", mn=0.02, mx=0.15, vertical_origin=0.1)]}}],
  "cost": cost(cd=6.0)}
S["maledizione"] = {"school": "spell_power:soul", "type": "ACTIVE", "range": 24.0, "tier": 1, "group": "mago_nero_maledizione", "learn": {},
  "active": casting(1.5), "release": release, "target": {"type": "AIM", "aim": {"sticky": True}},
  "impacts": [{"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.3, "knockback": 0.0}},
      "visuals": {"particles": [p("spell_engine:magic_skull", DARK, "BURST", 10.0, mn=0.1, mx=0.4), p("soul", None, None, 12.0, "PILLAR", mn=0.02, mx=0.1, vertical_origin=0.1)]}, "sound": {"id": "spell_engine:generic_soul_release"}},
    {"action": {"type": "STATUS_EFFECT", "status_effect": {"effect_id": "minecraft:wither", "duration": 8.0, "amplifier": 0, "amplifier_power_multiplier": 0.1, "amplifier_cap": 3, "show_particles": True}}}],
  "cost": cost(cd=12.0)}
S["nube_tenebra"] = {"school": "spell_power:soul", "type": "ACTIVE", "range": 0.0, "tier": 2, "group": "mago_nero_nube", "learn": {},
  "active": {}, "release": {"animation": {"id": "spell_engine:one_handed_area_release"}, "sound": {"id": "spell_engine:generic_soul_release"}},
  "deliver": {"type": "CLOUD", "clouds": [{"volume": {"radius": 4.5, "area": {"vertical_range_multiplier": 0.4}}, "time_to_live_seconds": 8.0, "impact_tick_interval": 20,
      "client_data": {"particles": [p("spell_engine:smoke_medium", None, None, 3.0, "PILLAR", mn=0.01, mx=0.05, vertical_origin=0.1), p("spell_engine:area_swirl", DARK, None, 1.0, "PILLAR", mn=0.0, mx=0.0, vertical_origin=0.05)]}}]},
  "impacts": [{"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.15, "knockback": 0.0}}},
    {"action": {"type": "STATUS_EFFECT", "status_effect": {"effect_id": "minecraft:slowness", "duration": 2.0, "amplifier": 1}}},
    {"action": {"type": "STATUS_EFFECT", "status_effect": {"effect_id": "minecraft:blindness", "duration": 2.0}}}],
  "cost": cost(cd=20.0)}

# ---------------- datapack
w(f"{DP}/pack.mcmeta", {"pack": {"description": "Castamir - Mago Nero (Spell Engine)", "min_format": [107, 1], "max_format": 107}})
for k, v in S.items(): w(f"{DP}/data/{NS}/spell/{k}.json", v)
book_spells = ["drenaggio_vitale", "maledizione", "nube_tenebra"]
w(f"{DP}/data/{NS}/tags/spell/spell_book/mago_nero.json", {"values": [f"{NS}:{s}" for s in book_spells]})
w(f"{DP}/data/{NS}/tags/spell/spell_scroll/mago_nero.json", {"values": [f"{NS}:{s}" for s in book_spells]})
w(f"{DP}/data/{NS}/tags/spell/weapon/bastone_ombre.json", {"values": [f"{NS}:dardo_ombra"]})
def mod(t, amount, op, idsuffix, slot): return {"type": t, "amount": amount, "operation": op, "id": f"{NS}:{idsuffix}", "slot": slot}
def name(key, color): return {"translate": key, "color": color, "italic": False}
STAFF = {"id": "wizards:staff_arcane", "count": 1, "components": {
  "minecraft:rarity": "rare",
  "minecraft:item_name": name(f"item.{NS}.bastone_ombre", "dark_purple"),
  "minecraft:attribute_modifiers": [mod("minecraft:attack_damage", 3.0, "add_value", "bastone_ombre_danno", "mainhand"),
      mod("minecraft:attack_speed", -3.0, "add_value", "bastone_ombre_velocita", "mainhand"),
      mod("spell_power:soul", 6.0, "add_value", "bastone_ombre_anima", "mainhand")],
  "minecraft:custom_data": {"rpgbridge_id": f"{NS}:bastone_ombre"},
  "spell_engine:spell_container": {"access": "MAGIC", "spell_ids": [f"{NS}:dardo_ombra"]}}}
PIECES = [("cappuccio", "wizards:netherite_arcane_robe_head", "head", 1), ("veste", "wizards:netherite_arcane_robe_chest", "chest", 3), ("gambali", "wizards:netherite_arcane_robe_legs", "legs", 2), ("calzari", "wizards:netherite_arcane_robe_feet", "feet", 1)]
ARMOR = {}
for piece, base, slot, armor in PIECES:
    ARMOR[piece] = {"id": base, "count": 1, "components": {
      "minecraft:rarity": "uncommon",
      "minecraft:item_name": name(f"item.{NS}.mago_nero_{piece}", "dark_purple"),
      "minecraft:attribute_modifiers": [mod("minecraft:armor", float(armor), "add_value", f"mago_nero_{piece}_armatura", slot),
          mod("spell_power:soul", 0.25, "add_multiplied_base", f"mago_nero_{piece}_anima", slot),
          mod("spell_power:haste", 0.02, "add_multiplied_base", f"mago_nero_{piece}_rapidita", slot)],
      "minecraft:custom_data": {"rpgbridge_id": f"{NS}:mago_nero_{piece}"},
      "spell_engine:equipment_set": f"{NS}:mago_nero"}}
w(f"{DP}/data/{NS}/equipment_set/mago_nero.json", {"name": f"equipment_set.{NS}.mago_nero",
  "items": [b for _, b, _, _ in PIECES],
  "bonuses": [{"required_piece_count": 2, "attributes": [mod("spell_power:soul", 0.1, "add_multiplied_base", "set_mago_nero_2", "armor")]},
              {"required_piece_count": 4, "attributes": [mod("spell_power:critical_chance", 0.1, "add_multiplied_base", "set_mago_nero_4", "armor"),
                                                          mod("spell_power:haste", 0.05, "add_multiplied_base", "set_mago_nero_4b", "armor")]}]})
def shaped(fname, pattern, key, result):
    w(f"{DP}/data/{NS}/recipe/{fname}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": pattern, "key": key, "result": result})
shaped("bastone_ombre", ["A", "B"], {"A": "minecraft:wither_skeleton_skull", "B": "wizards:staff_arcane"}, STAFF)
for piece, base, *_ in PIECES: shaped(f"mago_nero_{piece}", ["R", "B"], {"R": "minecraft:wither_rose", "B": base}, ARMOR[piece])
def give(stack):
    comps = ",".join(f"{k}={json.dumps(v, ensure_ascii=False)}" for k, v in stack["components"].items())
    return f"give @s {stack['id']}[{comps}]"
book = {"id": "spell_engine:spell_book", "components": {"spell_engine:spell_container": {"pool": f"{NS}:spell_book/mago_nero"},
  "minecraft:item_model": "wizards:spell_book/arcane", "minecraft:item_name": {"translate": f"item.{NS}.spell_book/mago_nero"}}}
kit = ["# Kit del Mago Nero (per lo staff): /function castamir:mago_nero/kit", give(STAFF)] + [give(ARMOR[k]) for k in ARMOR] + [give(book), "give @s runes:soul_stone 64"]
w(f"{DP}/data/{NS}/function/mago_nero/kit.mcfunction", "\n".join(kit) + "\n")

# ---------------- resource pack
w(f"{RP}/pack.mcmeta", {"pack": {"description": "Castamir - Mago Nero", "min_format": 88, "max_format": 88}})
lang = {
 "it_it": {"dardo_ombra": ("Dardo d'Ombra", "Scaglia un dardo d'ombra che infligge {damage} danni da anima."),
   "drenaggio_vitale": ("Drenaggio Vitale", "Prosciuga il bersaglio: {damage} danni da anima, curandoti di {heal}."),
   "maledizione": ("Maledizione", "Maledice il bersaglio: {damage} danni da anima e Avvizzimento {effect_amplifier} per {effect_duration} secondi."),
   "nube_tenebra": ("Nube di Tenebra", "Una nube di tenebra ti avvolge: i nemici al suo interno subiscono {damage} danni da anima al secondo, restano accecati e rallentati."),
   "items": {"bastone_ombre": "Bastone delle Ombre", "mago_nero_cappuccio": "Cappuccio del Mago Nero", "mago_nero_veste": "Veste del Mago Nero",
     "mago_nero_gambali": "Gambali del Mago Nero", "mago_nero_calzari": "Calzari del Mago Nero",
     "spell_book/mago_nero": "Tomo della Tenebra", "spell_book/mago_nero.spell_binding.description": "Tomo del Mago Nero: prosciuga, maledice e avvolge i nemici nella tenebra.",
     "spell_scroll/mago_nero": "Pergamena della Tenebra"}, "set": "Paramenti del Mago Nero"},
 "en_us": {"dardo_ombra": ("Shadow Bolt", "Hurls a bolt of shadow, dealing {damage} soul spell damage."),
   "drenaggio_vitale": ("Life Drain", "Drains the target for {damage} soul spell damage, healing you for {heal}."),
   "maledizione": ("Curse", "Curses the target: {damage} soul spell damage and Wither {effect_amplifier} for {effect_duration} seconds."),
   "nube_tenebra": ("Cloud of Darkness", "A cloud of darkness surrounds you: enemies inside take {damage} soul spell damage per second, blinded and slowed."),
   "items": {"bastone_ombre": "Staff of Shadows", "mago_nero_cappuccio": "Black Mage Hood", "mago_nero_veste": "Black Mage Robe",
     "mago_nero_gambali": "Black Mage Leggings", "mago_nero_calzari": "Black Mage Shoes",
     "spell_book/mago_nero": "Tome of Darkness", "spell_book/mago_nero.spell_binding.description": "Spell Book of Black Mages: drain, curse and engulf enemies in darkness.",
     "spell_scroll/mago_nero": "Scroll of Darkness"}, "set": "Black Mage Vestments"}}
for code, L in lang.items():
    d = {}
    for k in S: d[f"spell.{NS}.{k}.name"], d[f"spell.{NS}.{k}.description"] = L[k]
    for k, v in L["items"].items(): d[f"item.{NS}.{k}"] = v
    d[f"equipment_set.{NS}.mago_nero"] = L["set"]
    w(f"{RP}/assets/{NS}/lang/{code}.json", d)
# item definitions for the spell book / scroll the Spell Binding Table creates (item_model "castamir:spell_book/mago_nero"):
# they point to the Wizards models already on the client (reference only, nothing copied)
for kind in ("spell_book", "spell_scroll"):
    w(f"{RP}/assets/{NS}/items/{kind}/mago_nero.json", {"model": {"type": "minecraft:model", "model": f"wizards:item/{kind}/arcane"}})
# ---------------- spell icons: original hand-made pixel art, 16x16, glow style of the RPG Series spell icons
OUT = f"{RP}/assets/{NS}/textures/spell"
P = {".": None,
 "1": (30, 8, 46), "2": (58, 18, 90), "3": (92, 30, 138), "4": (132, 50, 184), "5": (178, 92, 224), "6": (222, 160, 248), "7": (255, 238, 255),
 "r": (60, 6, 22), "s": (104, 12, 34), "t": (152, 24, 48), "u": (204, 54, 76), "v": (240, 120, 140), "w": (255, 214, 222)}
I = {
"dardo_ombra": [
"..........2344..",
"........234566.4",
".......23456776.",
"......2345677764",
".....23456777765",
"....2334567776.4",
"...2323456665.4.",
"..23..2345554.4.",
".23..23234443...",
"23..2.2323332...",
"2..2.2.2.222....",
"..2.2.2.2.......",
".2.2.2..........",
"2.2.............",
".2..............",
"2...............",
],
"maledizione": [
"....33444433....",
"..3455666655433.",
".3456677776654..",
"345677777777653.",
"45677777777776543",
"4567111771117654",
"456111117111116",
"4561171171171165",
"4567111767111765",
"34567777177776543",
".3456771117765..",
"..344566666543..",
"..3.3417171433..",
".3..3416161433.3",
"3.....33333.....",
"..3..........3..",
],
"drenaggio_vitale": [
".......tt.......",
"......tuut......",
".....tuvvut.....",
"....tuvvvvut....",
"...stuvwwvuts...",
"..stuvwwwvvuts..",
"..stuvwwvvvuts..",
".rstuvvvvvvutsr.",
".rstuuvvvvuutsr.",
"5rsttuuuuuttsr5.",
"6.rssttttttssr.6",
"56.rrssssssrr.65",
".56..rrrrrr..65.",
"..567......765..",
"...5667777665...",
".....556655.....",
],
"nube_tenebra": [
"....22333322....",
"..2334455443322.",
".233455665544322",
".2345566776654432",
"23455667777665432",
"2344566777766543",
"23445567777654432",
"234455667766544322",
"23344556666554432",
"2233445566554433",
".2233445555443322",
"..22334444433222",
"...2233333322...",
"....2.3.3.3.2...",
"...2.3.2.3.2.3..",
"..2...2...2...2.",
]}
os.makedirs(OUT, exist_ok=True)
for name, rows in I.items():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows[:16]):
        row = (row + "." * 16)[:16]
        for x, ch in enumerate(row):
            if P.get(ch): im.putpixel((x, y), P[ch] + (255,))
    im.save(f"{OUT}/{name}.png")

print("ok", len(S), "spell")
