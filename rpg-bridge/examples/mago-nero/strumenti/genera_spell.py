import json, os, shutil, sys
from PIL import Image, ImageDraw
OUT = sys.argv[1]
NS = "castamir"
DARK, PURPLE, VOID = 0xFF3A0F5C, 0xFF7A2BB8, 0xFF1A0526
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
soul_cost = lambda cd=None, group=None: {"cooldown": dict(**({"duration": cd} if cd else {}), **({"group": group} if group else {})), "item": {"id": "runes:soul_stone"}}
spells = {}
spells["dardo_ombra"] = {
  "school": "spell_power:soul", "range": 48.0, "tier": 0, "group": "mago_nero_arma", "learn": {},
  "active": casting(1.0), "release": release,
  "target": {"type": "AIM", "aim": {}},
  "deliver": {"type": "PROJECTILE", "projectile": {"projectile": {"client_data": {"light_level": 4,
      "travel_particles": [p("spell_engine:magic_spell", DARK, "ASCEND", 2.0, "CIRCLE", mn=0.05, mx=0.1, alignment="LOOK"),
                           p("smoke", None, None, 1.0, "SPHERE", mn=0.0, mx=0.02)],
      "composite_model": {"models": [{"fx": {"model_id": "wizards:spell_projectile/arcane_bolt", "scale": 0.45}}]}}}}},
  "impacts": [{"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.8, "knockback": 0.5}},
      "visuals": {"particles": [p("spell_engine:magic_skull", PURPLE, "BURST", 6.0, mn=0.1, mx=0.4), p("spell_engine:smoke_medium", None, None, 12.0, mn=0.05, mx=0.3)]},
      "sound": {"id": "spell_engine:generic_soul_release"}}],
  "cost": soul_cost(group="weapon")}
spells["drenaggio_vitale"] = {
  "school": "spell_power:soul", "range": 16.0, "tier": 1, "group": "mago_nero_drenaggio", "learn": {},
  "active": casting(1.25), "release": release,
  "target": {"type": "AIM", "aim": {"sticky": True}},
  "impacts": [
    {"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.6, "knockback": 0.2}},
     "visuals": {"particles": [p("spell_engine:dripping_blood", None, None, 15.0, "PILLAR", mn=0.05, mx=0.2, vertical_origin=0.5)]},
     "sound": {"id": "spell_engine:leeching_impact"}},
    {"action": {"type": "HEAL", "apply_to_caster": True, "heal": {"spell_power_coefficient": 0.35}},
     "visuals": {"particles": [p("spell_engine:magic_heal", PURPLE, "ASCEND", 10.0, "PILLAR", mn=0.02, mx=0.15, vertical_origin=0.1)]}}],
  "cost": soul_cost(cd=6.0)}
spells["maledizione"] = {
  "school": "spell_power:soul", "range": 24.0, "tier": 1, "group": "mago_nero_maledizione", "learn": {},
  "active": casting(1.5), "release": release,
  "target": {"type": "AIM", "aim": {"sticky": True}},
  "impacts": [
    {"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.3, "knockback": 0.0}},
     "visuals": {"particles": [p("spell_engine:magic_skull", DARK, "BURST", 10.0, mn=0.1, mx=0.4), p("soul", None, None, 12.0, "PILLAR", mn=0.02, mx=0.1, vertical_origin=0.1)]},
     "sound": {"id": "spell_engine:generic_soul_release"}},
    {"action": {"type": "STATUS_EFFECT", "status_effect": {"effect_id": "minecraft:wither", "duration": 8.0,
        "amplifier": 0, "amplifier_power_multiplier": 0.1, "amplifier_cap": 3, "show_particles": True}}}],
  "cost": soul_cost(cd=12.0)}
spells["nube_tenebra"] = {
  "school": "spell_power:soul", "range": 0.0, "tier": 2, "group": "mago_nero_nube", "learn": {},
  "active": {}, "release": {"animation": {"id": "spell_engine:one_handed_area_release"}, "sound": {"id": "spell_engine:generic_soul_release"}},
  "deliver": {"type": "CLOUD", "clouds": [{"volume": {"radius": 4.5, "area": {"vertical_range_multiplier": 0.4}},
      "time_to_live_seconds": 8.0, "impact_tick_interval": 20,
      "client_data": {"particles": [p("spell_engine:smoke_medium", None, None, 3.0, "PILLAR", mn=0.01, mx=0.05, vertical_origin=0.1),
                                    p("spell_engine:area_swirl", DARK, None, 1.0, "PILLAR", mn=0.0, mx=0.0, vertical_origin=0.05)]}}]},
  "impacts": [
    {"action": {"type": "DAMAGE", "damage": {"spell_power_coefficient": 0.15, "knockback": 0.0}}},
    {"action": {"type": "STATUS_EFFECT", "status_effect": {"effect_id": "minecraft:slowness", "duration": 2.0, "amplifier": 1}}},
    {"action": {"type": "STATUS_EFFECT", "status_effect": {"effect_id": "minecraft:blindness", "duration": 2.0}}}],
  "cost": soul_cost(cd=20.0)}

# ---------------- datapack
dp = os.path.join(OUT, "datapack", "castamir_mago_nero")
os.makedirs(os.path.join(dp, "data", NS, "spell"), exist_ok=True)
json.dump({"pack": {"description": "Castamir - Mago Nero (spell Spell Engine)", "min_format": [107, 1], "max_format": 107}}, open(os.path.join(dp, "pack.mcmeta"), "w"), indent=2)
for k, v in spells.items(): json.dump(v, open(os.path.join(dp, "data", NS, "spell", k + ".json"), "w"), indent=2)
os.makedirs(os.path.join(dp, "data", NS, "tags", "spell", "spell_book"), exist_ok=True)
json.dump({"values": [{"id": f"{NS}:{k}", "required": False} for k in spells]}, open(os.path.join(dp, "data", NS, "tags", "spell", "spell_book", "mago_nero.json"), "w"), indent=2)
json.dump({"values": [{"id": f"#{NS}:spell_book/mago_nero", "required": False}]}, open(os.path.join(dp, "data", NS, "tags", "spell", "mago_nero.json"), "w"), indent=2)

# ---------------- resource pack (names, descriptions, icons)
rp = os.path.join(OUT, "resourcepack", "castamir_mago_nero")
os.makedirs(os.path.join(rp, "assets", NS, "lang"), exist_ok=True)
os.makedirs(os.path.join(rp, "assets", NS, "textures", "spell"), exist_ok=True)
json.dump({"pack": {"description": "Castamir - Mago Nero (nomi e icone delle spell)", "min_format": 88, "max_format": 88}}, open(os.path.join(rp, "pack.mcmeta"), "w"), indent=2)
names = {
 "it_it": {"dardo_ombra": ("Dardo d'Ombra", "Scaglia un dardo d'ombra che infligge {damage} danni da anima."),
           "drenaggio_vitale": ("Drenaggio Vitale", "Prosciuga il bersaglio: {damage} danni da anima e ti cura di {heal}."),
           "maledizione": ("Maledizione", "Maledice il bersaglio: {damage} danni da anima, poi lo avvizzisce per 8 secondi."),
           "nube_tenebra": ("Nube di Tenebra", "Evoca attorno a te una nube che acceca, rallenta e consuma i nemici.")},
 "en_us": {"dardo_ombra": ("Shadow Bolt", "Hurls a bolt of shadow, dealing {damage} soul spell damage."),
           "drenaggio_vitale": ("Life Drain", "Drains the target: {damage} soul spell damage, healing you for {heal}."),
           "maledizione": ("Curse", "Curses the target: {damage} soul spell damage, then withers it for 8 seconds."),
           "nube_tenebra": ("Cloud of Darkness", "Summons a cloud around you that blinds, slows and consumes enemies.")}}
for lang, entries in names.items():
    d = {}
    for k, (n, desc) in entries.items():
        d[f"spell.{NS}.{k}.name"] = n; d[f"spell.{NS}.{k}.description"] = desc
    json.dump(d, open(os.path.join(rp, "assets", NS, "lang", lang + ".json"), "w", encoding="utf-8"), indent=2, ensure_ascii=False)
def icon(name, draw_fn):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], fill=(26, 5, 38, 255)); d.rectangle([0, 0, 15, 15], outline=(58, 15, 92, 255))
    draw_fn(d); im.save(os.path.join(rp, "assets", NS, "textures", "spell", name + ".png"))
icon("dardo_ombra", lambda d: (d.line([3, 12, 12, 3], fill=(170, 90, 230, 255), width=2), d.point([(12, 3), (13, 2), (11, 2), (13, 4)], fill=(230, 200, 255, 255))))
icon("drenaggio_vitale", lambda d: (d.ellipse([4, 5, 11, 12], fill=(150, 10, 30, 255)), d.polygon([(7, 2), (9, 2), (8, 5)], fill=(150, 10, 30, 255)), d.point([(6, 7), (7, 7)], fill=(240, 120, 140, 255))))
icon("maledizione", lambda d: (d.ellipse([4, 3, 11, 10], fill=(200, 190, 210, 255)), d.rectangle([6, 10, 9, 12], fill=(200, 190, 210, 255)), d.point([(6, 6), (9, 6)], fill=(60, 0, 80, 255)), d.point([(6, 7), (9, 7)], fill=(60, 0, 80, 255))))
icon("nube_tenebra", lambda d: (d.ellipse([2, 6, 9, 12], fill=(70, 30, 100, 255)), d.ellipse([6, 4, 13, 11], fill=(90, 40, 130, 255)), d.ellipse([4, 8, 12, 13], fill=(50, 20, 75, 255))))
print("ok", sorted(spells))
