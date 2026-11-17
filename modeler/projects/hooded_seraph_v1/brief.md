# BLOCKBENCH PROJECT — THE HOODED SERAPH (brief del cliente)

You are an expert Minecraft mob artist, professional Blockbench modeler, and pixel-art texture designer.

Your task is to create a complete, polished, optimized Minecraft mob model directly in Blockbench, named "The Hooded Seraph".

The creature is an ancient, mysterious angelic warrior wearing a hooded robe, wielding a sacred spear, and possessing two majestic feathered wings.

The final result should look like a high-quality custom Minecraft boss, combining a strong silhouette, elegant design, excellent pixel-art textures, and extremely efficient geometry.

## 1. CHARACTER DESIGN

Create a tall, slender, intimidating angelic guardian.

### Head and Hood
- Large, deep hood made from ivory-white fabric.
- The hood completely conceals the face in darkness.
- Two small glowing golden eyes visible within the shadow.
- Carefully textured fabric folds and subtle worn edges.
- A mysterious, divine, slightly unsettling appearance.

### Body and Clothing
- Long, layered white and light-gray robes.
- Elegant golden embroidery along the edges.
- Ancient golden shoulder armor and bracers.
- A narrow waist and slightly elongated proportions.
- Subtle cloth folds and shading created through textures rather than extra geometry.

### Wings
- Two large, majestic angelic wings emerging from the back.
- Elegant, recognizable feathered silhouettes.
- Mostly white and ivory, with subtle gray shading.
- Feathers should primarily be illustrated through detailed pixel-art textures.
- Use a small number of carefully positioned geometric segments to establish the wing shapes.
- Each wing should have an appropriate rotation pivot for future animations.

### Sacred Spear
- A long, elegant spear held in the right hand.
- Dark shaft with gold decorations.
- Sharp, symmetrical golden spearhead.
- A small sacred crystal near the blade.
- Keep the weapon geometrically simple but visually impressive through excellent texturing.

## 2. CRITICAL — LOW-POLY OPTIMIZATION

DO NOT OVERMODEL THE CHARACTER. This is one of the most important requirements.

The model must be lightweight, efficient, and suitable for real-time rendering inside Minecraft.

- Use as few cubes as reasonably possible.
- Prioritize silhouette over geometric complexity.
- Avoid unnecessary subdivisions and overlapping cubes.
- Do not construct individual feathers using dozens of tiny cubes.
- Do not model small decorative details that can be painted onto textures.
- Use simple cuboid geometry with well-planned proportions.
- Avoid unnecessary hidden faces and redundant elements.
- Keep the model clean, organized, and animation-friendly.
- Reuse texture space intelligently when appropriate.
- Aim for approximately 60–100 cubes or fewer, if possible, without sacrificing the character's recognizable design. This is a target, not a reason to compromise essential shapes.

Every cube must have a clear visual or functional purpose. The character should look detailed because of its textures, not because of excessive polygons.

## 3. CRITICAL — PROFESSIONAL PIXEL-ART TEXTURING

TEXTURE QUALITY IS THE HIGHEST VISUAL PRIORITY. Do not simply apply flat colors to cubes. Create carefully designed, hand-crafted-looking Minecraft pixel-art textures. Use a coherent texture atlas, preferably 128×128, while preserving a readable, consistent pixel-art scale.

- Fabric: carefully placed highlights and shadows; readable folds and seams; subtle color variation between ivory, white, and gray; gold embroidered details; natural-looking depth without excessive visual noise.
- Wings: individually readable feather patterns painted into the texture; subtle separation between feather layers; gentle highlights along feather edges; darker shading near the wing roots; avoid relying on additional geometry for feather details.
- Golden Armor: rich antique-gold palette; pixel-art metallic reflections; darker edges and carefully placed highlights; subtle ornamental patterns.
- Spear: detailed shaft wrapping; metallic shading on the spearhead; strong contrast between dark metal and bright gold.
- Hood and Face: deep black shadows inside the hood; subtle fabric texture; small, striking golden eyes.

Texture rules: consistent texel density; clean and precise UV mapping; no blurry gradients; no random pixel noise; no excessive dithering; no plain unfinished surfaces; controlled contrast and intentional color clusters; good from every angle, not only from the front.

A simple model with beautiful textures is infinitely preferable to a complex model with poor textures.

## 4. ART DIRECTION
Minecraft dark fantasy; ancient celestial guardian; divine but intimidating; elegant, mysterious, powerful; vanilla-compatible visual language with premium custom-boss quality; impressive without being overly realistic or cluttered.

Palette: ivory white, pale silver-gray, warm antique gold, deep charcoal, almost-black facial shadows. Avoid overly saturated colors.

## 5. MODEL STRUCTURE AND ANIMATION
Groups: Head, Hood, Body, RightArm, LeftArm, RightLeg, LeftLeg, RightWing, LeftWing, Spear. Appropriate pivots and parent-child relationships. Must support idle, walking, wing flapping, flight, spear attacks, dramatic boss poses. The spear follows the right hand.

## 6. EXECUTION
Actually build the model, UVs and textures; inspect from multiple angles; fix clipping, UV errors and inconsistencies; save the editable .bbmodel. Do not claim the model or textures are complete unless they have actually been generated.

## 7. FINAL QUALITY CHECK
Strong recognizable silhouette; hood genuinely conceals the face; spear clearly visible and correctly positioned; both wings elegant and symmetrical; geometry efficiently optimized; textures with meaningful intentional details; no stretching or accidental UV overlaps; all important surfaces textured; impressive from front, side and back; structured for animation.

LESS GEOMETRY. BETTER TEXTURES. STRONGER SILHOUETTE. PROFESSIONAL RESULTS.

## Riferimenti visivi del cliente (solo atmosfera, non da copiare)
`projects/hooded_seraph/mood/ref_1.jpg` (guerriero angelico bianco-oro con ali e spadone), `projects/hooded_seraph/mood/ref_2.jpg` (ali piumate grandi a ventaglio). Non sono nel repository.
