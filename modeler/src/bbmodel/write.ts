import { isZero } from "../core/math";
import { FACE_DIRS, allCubes, type Model, type OutlineNode } from "./model";

/* eslint-disable @typescript-eslint/no-explicit-any */

/** Serializza nel formato .bbmodel (format_version 4.10, apribile da Blockbench 4.x e 5.x). */
export function writeBBModel(m: Model): any {
  const elements = allCubes(m.outline).map((c) => {
    const faces: any = {};
    for (const d of FACE_DIRS) {
      const f = c.faces[d];
      faces[d] = f ? { uv: f.uv, texture: f.texture, ...(f.rotation ? { rotation: f.rotation } : {}) } : { uv: [0, 0, 0, 0], texture: null };
    }
    return {
      name: c.name,
      box_uv: false,
      rescale: false,
      locked: false,
      render_order: "default",
      allow_mirror_modeling: true,
      from: c.from,
      to: c.to,
      autouv: 0,
      color: 0,
      ...(c.inflate ? { inflate: c.inflate } : {}),
      origin: c.origin,
      ...(isZero(c.rotation) ? {} : { rotation: c.rotation }),
      faces,
      type: "cube",
      uuid: c.uuid,
    };
  });

  const outliner = (nodes: OutlineNode[]): any[] =>
    nodes.map((n) =>
      n.kind === "cube"
        ? n.cube.uuid
        : {
            name: n.group.name,
            origin: n.group.origin,
            ...(isZero(n.group.rotation) ? {} : { rotation: n.group.rotation }),
            color: 0,
            uuid: n.group.uuid,
            export: true,
            mirror_uv: false,
            isOpen: true,
            locked: false,
            visibility: true,
            autouv: 0,
            children: outliner(n.children),
          },
    );

  return {
    meta: { format_version: "4.10", model_format: m.format, box_uv: false },
    name: m.name,
    model_identifier: m.format === "bedrock" ? m.name : "",
    ...(m.display ? { display: m.display } : {}),
    visible_box: [1, 1, 0],
    variable_placeholders: "",
    variable_placeholder_buttons: [],
    timeline_setups: [],
    unhandled_root_fields: {},
    resolution: m.resolution,
    elements,
    outliner: outliner(m.outline),
    textures: m.textures.map((t, i) => ({
      path: "",
      name: t.name,
      folder: "",
      namespace: "",
      id: String(i),
      width: t.img.width,
      height: t.img.height,
      uv_width: t.uvWidth,
      uv_height: t.uvHeight,
      particle: i === 0,
      use_as_default: false,
      layers_enabled: false,
      sync_to_project: "",
      render_mode: "default",
      render_sides: "auto",
      frame_time: 1,
      frame_order_type: "loop",
      frame_order: "",
      frame_interpolate: false,
      visible: true,
      internal: true,
      saved: true,
      uuid: t.uuid,
      relative_path: "",
      source: t.img.toDataUri(),
    })),
  };
}

/**
 * Esporta un modello Java Edition (block/item model JSON).
 * Richiede che i cubi rispettino i vincoli Java (rotazione su un solo asse, multipli di 22.5°, ±45°).
 */
export function writeJavaModel(m: Model, textureRef: string): any {
  const sx = 16 / m.resolution.width, sy = 16 / m.resolution.height;
  return {
    credit: "Made with Blockbench (blockbench-artist)",
    ...(m.resolution.width !== 16 || m.resolution.height !== 16 ? { texture_size: [m.resolution.width, m.resolution.height] } : {}),
    textures: { "0": textureRef, particle: textureRef },
    ...(m.display ? { display: m.display } : {}),
    elements: allCubes(m.outline).map((c) => {
      const ax = c.rotation.findIndex((r) => r !== 0);
      const faces: any = {};
      for (const d of FACE_DIRS) {
        const f = c.faces[d];
        if (!f) continue;
        faces[d] = {
          uv: [f.uv[0] * sx, f.uv[1] * sy, f.uv[2] * sx, f.uv[3] * sy].map((x) => Math.round(x * 1000) / 1000),
          ...(f.rotation ? { rotation: f.rotation } : {}),
          texture: "#0",
        };
      }
      return {
        name: c.name,
        from: c.from,
        to: c.to,
        ...(ax >= 0 ? { rotation: { angle: c.rotation[ax], axis: "xyz"[ax], origin: c.origin } } : {}),
        faces,
      };
    }),
  };
}
