import type { Vec3 } from "../core/math";
import { allGroups, type Animation, type Keyframe, type Model, type OutlineNode } from "../bbmodel/model";

/** Valore di un canale al tempo t (secondi). smooth = Catmull-Rom come in Blockbench. */
export function sample(kfs: Keyframe[] | undefined, t: number, fallback: Vec3): Vec3 {
  if (!kfs || !kfs.length) return fallback;
  const k = [...kfs].sort((a, b) => a.time - b.time);
  if (t <= k[0].time) return k[0].value;
  if (t >= k[k.length - 1].time) return k[k.length - 1].value;
  let i = 0;
  while (i < k.length - 1 && k[i + 1].time < t) i++;
  const a = k[i], b = k[i + 1];
  const u = (t - a.time) / Math.max(1e-6, b.time - a.time);
  const interp = b.interp ?? "smooth";
  if (interp === "step") return a.value;
  if (interp === "linear") return [0, 1, 2].map((j) => a.value[j] + (b.value[j] - a.value[j]) * u) as Vec3;
  const p0 = (k[i - 1] ?? a).value, p3 = (k[i + 2] ?? b).value;
  return [0, 1, 2].map((j) => {
    const P0 = p0[j], P1 = a.value[j], P2 = b.value[j], P3 = p3[j];
    return 0.5 * (2 * P1 + (-P0 + P2) * u + (2 * P0 - 5 * P1 + 4 * P2 - P3) * u * u + (-P0 + 3 * P1 - 3 * P2 + P3) * u * u * u);
  }) as Vec3;
}

/**
 * Posa del modello al tempo t. I valori dei keyframe sono quelli della timeline di Blockbench in formato Bedrock:
 * come fa Blockbench, rotazione X/Y e posizione X sono invertite rispetto agli assi della scena.
 */
export function poseModel(m: Model, anim: Animation, t: number): Model {
  const pose = (ns: OutlineNode[]): OutlineNode[] =>
    ns.map((n) => {
      if (n.kind !== "group") return n;
      const tr = anim.bones[n.group.name];
      if (!tr) return { ...n, children: pose(n.children) };
      const r = sample(tr.rotation, t, [0, 0, 0]), p = sample(tr.position, t, [0, 0, 0]), s = sample(tr.scale, t, [1, 1, 1]);
      return {
        kind: "group",
        group: {
          ...n.group,
          rotation: [n.group.rotation[0] - r[0], n.group.rotation[1] - r[1], n.group.rotation[2] + r[2]],
          offset: [-p[0], p[1], p[2]],
          scale: s,
        },
        children: pose(n.children),
      };
    });
  return { ...m, outline: pose(m.outline) };
}

/** Controlla che l'animazione usi bone esistenti e tempi dentro la durata. */
export function checkAnimation(m: Model, a: Animation): string[] {
  const names = new Set(allGroups(m.outline).map((g) => g.name));
  const out: string[] = [];
  for (const [bone, tr] of Object.entries(a.bones)) {
    if (!names.has(bone)) out.push(`${a.name}: bone '${bone}' inesistente (bone del modello: ${[...names].join(", ")})`);
    for (const [ch, kfs] of Object.entries(tr) as [string, Keyframe[] | undefined][])
      for (const k of kfs ?? []) if (k.time < 0 || k.time > a.length + 1e-6) out.push(`${a.name}.${bone}.${ch}: keyframe a ${k.time}s fuori dalla durata ${a.length}s`);
    if (a.loop === "loop")
      for (const [ch, kfs] of Object.entries(tr) as [string, Keyframe[] | undefined][]) {
        if (!kfs?.length) continue;
        const first = sample(kfs, 0, [0, 0, 0]), last = sample(kfs, a.length, [0, 0, 0]);
        if (first.some((v, i) => Math.abs(v - last[i]) > 0.01)) out.push(`${a.name}.${bone}.${ch}: in un loop il valore a 0s e a ${a.length}s dovrebbe essere uguale (scatto alla ripetizione)`);
      }
  }
  return out;
}
