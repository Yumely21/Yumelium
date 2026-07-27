# Iris / Oculus (shader-pack pipeline) → Yumelium / 1.12.2 — Port Plan

Status: **M0 PASSED = GO** (2026-07-06). This document scopes the port of the Iris shader-pack rendering pipeline (via
the **Oculus** Forge fork) onto Yumelium's ported Embeddium 0.5.x engine on Minecraft 1.12.2 (Cleanroom). This is the
second headline goal of the project ("Sodium **+ Iris** to 1.12.2 = MAX performance + COMPLETE shader support"). The
Sodium/Embeddium half + Nvidium + CTM are all complete; this is the remaining half.

> **✅ M0 RESULT (2026-07-06, runtime, RTX 4070 / GL 4.6 compat):** GO. The GL context is a **COMPATIBILITY profile**
> (`GL_CONTEXT_PROFILE_MASK=0x2`), so a representative legacy `#version 120` `gbuffers_terrain`-style program
> (`gl_Vertex`/`gl_ModelViewProjectionMatrix`/`gl_MultiTexCoord0`/`gl_NormalMatrix`/`gl_FragData[]` MRT) **compiled +
> linked directly**, and a multi-target G-buffer FBO (3 colortex + depth, MRT draw buffers, `FRAMEBUFFER_COMPLETE`) +
> `glBlitFramebuffer` worked. `GL_MAX_DRAW_BUFFERS`/`GL_MAX_COLOR_ATTACHMENTS = 8`. **Consequence:** the OptiFine /
> ShadersMod-style **direct-compile path is viable → the heavy glsl-transformer (ANTLR) is largely UNNECESSARY**,
> eliminating the biggest MED risk (§5.5). Probe = `com.yumelium.yumelium.client.shaders.IrisCapabilityProbe`.

---

## 1. Verdict

**Portable in principle, and the cleanest integration seam (terrain) is already isolated in our engine — but this is
the LARGEST and RISKIEST task in the entire project, larger than the Sodium port and larger than Nvidium.** The reason
is not any single blocker; it is *scope + the absence of a 1.12.2 reference*:

- The **Sodium** port had **Vintagium** (`Asek3/sodium-1.12`) as a proven 1.12.2 reference.
- The **Nvidium** port had **Alphadium** as an LGPL 1.21 reference to translate down.
- **Iris/Oculus has NO 1.12.2 version, ever.** Iris/Oculus never shipped below 1.16.5. So the *1.12.2-specific glue*
  (how to feed 1.12.2's **fixed-function, immediate-mode** entity/particle/sky/weather rendering into shader-pack
  gbuffer programs) must be **built from scratch**. The conceptual reference is how OptiFine / the old GLSL Shaders
  Mod did it for 1.7–1.12 — but neither is a copyable OSS codebase.

**Realistic framing:** "COMPLETE shader support" is the north star. A realistic *first deliverable* is a working
**terrain + sky + basic deferred/composite** pipeline with a *simple* shader pack. Full pack compatibility (every
gbuffers program, shadows on all geometry, POM/PBR, entity/particle shading, all uniforms) is a long multi-month tail.
The plan below is phased and gated so we can stop with a useful partial result at several points.

---

## 2. Base + strategy (decided; see [[yumelium-port-plan]])

- **Base = Oculus for 1.16.5** (`Asek3/Oculus`, the Forge fork of `IrisShaders/Iris`), pairing with our Embeddium
  0.5.x engine — this is the exact combo (Rubidium/Embeddium + Oculus) that Asek3 designed to work together on 1.16.5.
- **Same author advantage:** Asek3 authored *both* Vintagium (our Sodium 1.12.2 reference) *and* Oculus. So Oculus's
  Sodium-integration mixins (`iris.compat.sodium`) target Sodium classes whose 1.12.2 idioms we already know from the
  Vintagium port. The terrain seam is the best-understood part.
- **License = LGPL-3.0** (Iris core and Oculus are LGPL-3.0 — verified, see [[sodium-license-versions]]). LGPLv3 ⊂ our
  LGPLv3 Sodium base → no forced relicensing. Keep it a separable subtree with LGPL headers + NOTICE crediting Iris +
  Oculus authors.
- **Intermediate version rule:** port 1.16.5 → 1.12.2 (same as Sodium). Do NOT try to port Fabric Iris (1.20+) — its
  render pipeline (`RenderType`/`VertexConsumer`/blaze3d) is even further from 1.12.2.

---

## 3. What Iris/Oculus is (architecture)

Iris replaces vanilla's forward world rendering with a **user-programmable deferred pipeline** implementing the
OptiFine/ShadersMod shader-pack format. A shader pack is a folder/zip of GLSL programs + `.properties` config; Iris
loads it, transforms the GLSL, allocates a bank of framebuffers ("render targets"), and runs the programs in a fixed
order each frame. Subsystems (roughly version-independent unless noted):

1. **Shader-pack loading & config** — `ShaderPack`, `ProgramSet`, `ProgramSource`, `ShaderProperties`,
   `shaders.properties` / `block.properties` / `item.properties` / `entity.properties`, the `#define` option system +
   `/lang` option labels. Mostly plain file/string work → **ports cleanly**.
2. **GLSL transformation** — the biggest reusable dependency. `glsl-transformer` (ANTLR-based) + a C-preprocessor
   (`#include`/`#define`) rewrite each shader: patch legacy GL builtins (`gl_Vertex`, `gl_Color`, `gl_MultiTexCoord*`,
   `gl_NormalMatrix`, `ftransform()`, texture matrices) into Iris-injected attributes/uniforms so the pack's
   `#version 120` GLSL runs on a core-ish profile. **MC-version-agnostic → ports as a library** (needs bundling ANTLR
   runtime; Java 25 OK).
3. **Uniforms** — hundreds of shader-pack uniforms: `CommonUniforms`, `CameraUniforms`, `CelestialUniforms`
   (sunPosition/moonPosition/shadowLightPosition), `MatrixUniforms` (gbufferModelView/Projection + their inverses +
   previous-frame + shadow matrices), `SystemTimeUniforms`/`IrisTimeUniforms` (frameTimeCounter/worldTime),
   `ViewportUniforms`, biome/weather/fog uniforms. Values come from the render loop → **the *plumbing* ports, but every
   source value must be re-derived from 1.12.2 APIs** (no `Camera`/`ClientLevel` accessors; use `ActiveRenderInfo`,
   `WorldClient`, fixed-function GL matrices — exactly like our `SodiumWorldRenderer` already does).
4. **Render targets (G-buffers)** — `RenderTargets`: `colortex0..15`, `depthtex0..2`, `shadowtex0/1`,
   `shadowcolor0/1`, managed FBOs with the pack-declared formats + ping-pong. Pure GL/FBO management → **ports cleanly
   on LWJGL3** (our `gl/` layer already wraps FBOs/textures; adapt Iris's `IrisRenderSystem` GL calls to our stack).
5. **Pipeline orchestration** — `DeferredWorldRenderingPipeline` (1.16.5 era): per-frame order = `begin*` → **shadow
   pass** → `prepare*` → **gbuffers** (terrain/entities/blockentities/particles/sky/weather/hand/water) → `deferred*`
   → translucent gbuffers → `composite*` → `final`. Each program samples prior colortex outputs. Mostly GL work →
   **ports**, but *driven by* version-specific render-loop hooks.
6. **Shadow pass** — `ShadowRenderer` + `ShadowRenderTargets`: renders the scene AGAIN from the sun/moon POV into
   shadowtex, with its own frustum + terrain gather. Needs to re-drive our terrain + entity rendering from a second
   viewpoint → **couples tightly to our `SodiumWorldRenderer` + the immediate-mode entity path**.
7. **Sodium/Embeddium terrain integration** — `iris.compat.sodium`: `SodiumTerrainPipeline` generates terrain shader
   variants from the pack's `gbuffers_terrain`/`shadow` programs adapted to Sodium's chunk vertex layout, and mixins
   swap Sodium's own chunk shader programs + **extend the chunk vertex format** with `at_midBlock`/normal/tangent/
   `mc_Entity` so the pack can do normal-mapping/POM/per-block effects. **This is the cleanest seam for us** — it maps
   onto our isolated `ShaderChunkRenderer`/`ChunkShaderInterface`/`CompactChunkVertex` (see §4).
8. **Immediate-mode geometry (entities/particles/sky/weather/clouds/hand/block-entities)** — on 1.16.5 Iris patches
   the `RenderType`/`VertexConsumer` batching + `mc_Entity`/entity-id/tangent injection. **On 1.12.2 there is NO
   RenderType/VertexConsumer** — everything is fixed-function `Tessellator`/`BufferBuilder` + `GlStateManager`. This
   entire subsystem must be **re-invented for 1.12.2's immediate mode** (bind the right gbuffers program + feed
   attributes before each vanilla draw). **This is the hardest, most pioneering part** (§5.2).
9. **`ShaderModBridge` cooperation (already present!)** — our engine already has
   `org.embeddedt.embeddium.render.ShaderModBridge` with `areShadersEnabled()` / `isShaderPackInUse()` reflection into
   `net.irisshaders.iris.api.v0.IrisApi`, and `emulateLegacyColorBrightnessFormat()` already flips our
   `ChunkColorWriter` to the shader-expected legacy color/brightness vertex format when shaders are on. **The engine
   was ported shader-aware** — these are ready-made integration points.

---

## 4. Integration surface — Oculus hook → our engine

### 4.1 Terrain (the clean seam — already isolated)

| Iris/Oculus concern | Our engine class (already present) | Notes |
|---|---|---|
| Chunk shader program swap | `render/chunk/ShaderChunkRenderer.java` (`compileProgram`/`begin`/`end`) | Single choke point that compiles+binds the terrain program. Iris injects pack-derived vsh/fsh here. |
| Chunk shader uniforms/interface | `render/chunk/shader/ChunkShaderInterface.java` | Already abstracts uniform binding (matrices, textures, fog). Iris adds its uniform set here. |
| Chunk vertex format | `render/chunk/vertex/format/impl/CompactChunkVertex.java` (STRIDE=20) | Iris extends this with normal/tangent/midBlock/`mc_Entity`. This is the deepest terrain change. |
| Color/brightness format flip | `org/embeddedt/embeddium/render/ChunkColorWriter.java` + `ShaderModBridge.emulateLegacyColorBrightnessFormat()` | **Already wired.** Flips to legacy format when shaders on. |
| Terrain draw dispatch | `render/chunk/DefaultChunkRenderer.java` + `SodiumWorldRenderer.drawChunkLayer` | Where terrain is drawn; must happen inside Iris's gbuffer FBO with the pack program bound. Same hook Nvidium uses. |
| Shader-pack API surface (for our GUI/bridge) | implement `net.irisshaders.iris.api.v0.IrisApi` | `ShaderModBridge` already reflects for it → provide it so `areShadersEnabled()` returns real state + the video-settings "Shaders" button opens the pack screen. |

### 4.2 World render loop (the driving hooks)

| Iris needs to hook | 1.16.5 Iris target | Our 1.12.2 equivalent |
|---|---|---|
| Frame begin/end, phase ordering, FBO bind | `MixinLevelRenderer` / `GameRenderer` | `mixin/features/chunk_rendering/MixinRenderGlobal` (already @Overwrites `setupTerrain`/`renderBlockLayer`) + a new `MixinEntityRenderer` for frame setup |
| Terrain layer draw | Sodium terrain mixins | our `SodiumWorldRenderer.drawChunkLayer` (already the seam for Nvidium) |
| Entities / block-entities | `RenderType`/`VertexConsumer` patch | **rewrite for immediate mode** — our `MixinRenderGlobal.sodium$renderEntities` / `sodium$renderTileEntities` are the insertion points |
| Sky / weather / clouds | `MixinLevelRenderer` sky hooks | `MixinRenderGlobalYumeliumPlus` (we already mixin `renderSky`/`renderRainSnow`) + `MixinEntityRendererYumeliumPlus` (fog) |
| Shadow pass re-render | `ShadowRenderer` drives level render | re-drive `SodiumWorldRenderer` terrain gather + the immediate-mode entity path from the light viewport |
| Hand / GUI exclusion | first-person hand mixins | `EntityRenderer.renderHand`/GUI path |

**Key point:** our render loop is already a bespoke 1.12.2 rewrite (fixed-function GL matrices via `glGetFloat`,
`@Overwrite renderBlockLayer`, custom entity/TESR loops). Iris's pipeline must be inserted *around* this existing
structure, not on top of vanilla — which is actually an advantage (we control the seams) but means Iris's own
`MixinLevelRenderer` cannot be used as-is; its logic is re-hosted in our mixins.

---

## 5. Key adaptation challenges (why it is not a straight copy)

### 5.1 No blaze3d / RenderSystem / PoseStack — GL state layer
Iris 1.16.5 is built on `RenderSystem` + `IrisRenderSystem` + `GlStateManager` (1.16 flavor) + `PoseStack` matrices.
1.12.2 has fixed-function GL + an older `GlStateManager` + GL matrix stack. Iris's `gl/` (framebuffers, textures,
shader objects, uniform upload, sampler/image bindings, blit) ports with LWJGL3 help (like Sodium's `gl/` did), but
every `RenderSystem.*`/`PoseStack` call is rewritten to fixed-function GL / our `glGetFloat` matrix reads.

### 5.2 The immediate-mode geometry gap (BIGGEST) — §3.8
1.12.2 renders entities/particles/sky/weather/blockentities in **fixed-function immediate mode** (`Tessellator` +
`BufferBuilder` + `GlStateManager`, no bound shader). Shader packs need every such draw to (a) render into the current
gbuffer FBO, (b) with the correct `gbuffers_*` program bound, (c) fed the attributes the pack reads (normal, `mc_Entity`
block/entity id, `mc_midTexCoord`, `at_tangent`, entity color, lightmap/overlay). There is no `RenderType`/
`VertexConsumer` seam to inject these. This is exactly the problem OptiFine/ShadersMod solved for 1.7–1.12 by wrapping
the fixed-function pipeline. We must re-invent that: a state tracker that, at each world-render phase, binds the right
program + sets `mc_Entity`/entity uniforms + translates fixed-function color/UV/normal into the program's attributes.
**Partial support is acceptable early** (e.g. terrain + sky shaded, entities drawn untransformed into gbuffers with a
default id) — but full per-entity/particle correctness is a large per-phase effort.

### 5.3 Shadow pass needs a second scene traversal
The shadow map re-renders terrain + entities from the light POV. Our `SodiumWorldRenderer` can be driven with a second
`Viewport`/matrix set for terrain (its section graph already supports arbitrary viewports), but the immediate-mode
entity path (§5.2) must also be re-runnable from the light viewport → doubles the §5.2 work.

### 5.4 Vertex-format extension for terrain (§4.1)
Adding normal/tangent/midBlock/`mc_Entity` to `CompactChunkVertex` (STRIDE=20 → larger) touches the mesher
(`BlockRenderer` writes), the encoder (`ChunkVertexEncoder`), the GL attribute bindings, AND Nvidium's decode (the
mesh shader reads the compact format by hand — see [[yumelium-port-plan]] Nvidium notes). Iris + Nvidium are mutually
exclusive at runtime (Nvidium disables under shaders), so the Nvidium decode can ignore the extended attributes, but
the format constants must stay consistent. Manageable, but not a one-file change.

### 5.5 GLSL transformer + preprocessor dependencies
`glsl-transformer` (ANTLR4 runtime) + a GLSL C-preprocessor must be bundled. Plain Java, Java-25-compatible, but they
are heavy new dependencies to add to the build (shadow/relocate like JOML/fastutil). Verify ANTLR runtime works under
the Cleanroom classloader.

### 5.6 Feature/uniform long tail
Real packs (BSL, Complementary, SEUS, Sildur's) use a huge uniform/feature surface: shadows, PBR/labPBR, POM, custom
sky, clouds, water waves, motion blur, TAA (needs previous-frame matrices), `noise` texture, custom images
(`imageX`), SSBOs. Each missing piece = visibly broken output for packs that use it. Target a *simple* pack first
(e.g. a minimal test pack or a lite profile) and expand.

### 5.7 Mutual exclusion + fallback
Iris ON must cleanly disable Nvidium (they conflict) and, on failure/incompatible pack, fall back to our normal
Embeddium forward renderer without a crash. `ShaderModBridge` already gates the Nvidium/color-format side; the toggle
+ reload path (like our Nvidium `applyConfig()`/`reset()` REQUIRES_RENDERER_RELOAD flow) is the model.

---

## 6. Phased milestones (each is session-sized+; ✅ = go/no-go gate)

- **M0 — Feasibility gate (GATE).** Three cheap probes on our stack, logged, no rendering commitment:
  1. **GL/FBO capability**: allocate a multi-target FBO (colortex0..3 + depthtex) + a scratch framebuffer blit on the
     lwjglx/LWJGL3 stack, confirm no error. (We already create FBOs in the engine, so low risk.)
  2. **GLSL transformer bring-up**: add `glsl-transformer` + preprocessor deps; transform + compile ONE real
     `gbuffers_basic`-style `#version 120` program into a program our GL context links. Proves the pack→GL toolchain.
  3. **API surface**: stub `net.irisshaders.iris.api.v0.IrisApi` so `ShaderModBridge.areShadersEnabled()` toggles.
  **If the transformer or FBO stack fails on this LWJGL/shim/Cleanroom combo, the port is not viable — stop here.**
- **M1 — Shader-pack load + config.** Port `ShaderPack`/`ProgramSet`/`ShaderProperties` + `.properties` parsing +
  the `#define` option system. Load a pack from `shaderpacks/` (new dir) or an embedded test pack; log the discovered
  programs. No rendering yet.
- **M2 — Render targets + pipeline skeleton.** Port `RenderTargets` (colortex/depthtex bank) + a minimal
  `DeferredWorldRenderingPipeline` that binds the gbuffer FBO at world-render start and runs `final` (blit colortex0
  to screen) at end — with vanilla terrain drawn in between untransformed. Proves FBO round-trip on 1.12.2.
- **M3 — Terrain gbuffers (first visible shading).** Wire `SodiumTerrainPipeline` → our `ShaderChunkRenderer`: compile
  the pack's `gbuffers_terrain` into our chunk program slot, extend `CompactChunkVertex` with normal/midBlock/
  `mc_Entity`, feed Iris terrain uniforms. Terrain now shaded by the pack (no shadows/composite yet). **First real
  milestone** — mirrors how Sodium+Iris cooperate on Fabric, using our cleanest seam.
- **M4 — Deferred + composite + `final`.** Run `deferred*`/`composite*`/`final` program chain over the terrain
  gbuffers → real lighting/tonemapping from a simple pack. This is where "it looks like shaders" happens.
- **M5 — Sky / fog / clouds / weather.** Route the immediate-mode sky/weather draws (we already mixin these) through
  `gbuffers_skybasic`/`skytextured`/`clouds`/`weather`. First slice of the §5.2 immediate-mode work.
- **M6 — Shadow pass.** `ShadowRenderer` + shadowtex + re-drive terrain from the light viewport; sample in
  composite. Big visual leap (real shadows) but couples to §5.2/§5.3.
- **M7 — Entities / block-entities / particles / hand (the §5.2 grind).** The immediate-mode vertex-injection layer:
  bind `gbuffers_entities`/`gbuffers_block`/`gbuffers_textured` + `mc_Entity`/id/tangent + fixed-function→attribute
  translation, for the regular entity loop, TESR loop, particles, and the hand. Incremental per phase.
- **M8 — Integration/UX + hardening.** Real `IrisApi` impl; a Video-Settings "Shaders" button (`ShaderModBridge`
  already can open it) + shaderpack-select screen; hard mutual-exclusion with Nvidium; graceful per-pack fallback;
  crash hardening; uniform long-tail (previous-frame matrices/TAA, noise, custom images) as packs demand.

Stop-with-value points: after **M4** (shaded terrain + composite = a real if partial shader look), after **M6**
(terrain + sky + shadows = most of the visual impact), after **M8** (broad pack support).

---

## 7. Risks / blockers

- **[HIGH] No 1.12.2 reference** — unlike Sodium (Vintagium) and Nvidium (Alphadium), the 1.12.2 immediate-mode glue
  is pioneering; the conceptual guide (OptiFine/ShadersMod) is not copyable OSS.
- **[HIGH] Scope** — largest task in the project; realistically multi-month for broad pack compatibility. Phasing +
  stop-with-value points mitigate.
- **[HIGH] Immediate-mode geometry (§5.2)** — the entity/particle/blockentity vertex-injection layer has no clean
  1.12.2 seam; each render phase is bespoke.
- **[MED] GLSL transformer / ANTLR on Cleanroom+Java25** — must be proven at M0.
- **[MED] Vertex-format extension churn** — touches mesher/encoder/GL-bindings and coexists with Nvidium's manual
  decode.
- **[MED] Pack feature long tail** — partial support = broken output for advanced packs; manage expectations, target
  a simple pack first.
- **[LOW-MED] Mutual exclusivity with Nvidium** — by design; the toggle/fallback must be robust (model exists).

## 8. License

Port from **Oculus / Iris (LGPL-3.0)**. Keep it a separable subtree (e.g. `net.irisshaders.iris.*` /
`com.yumelium.yumelium.shaders.*`) with LGPL-3.0 headers + NOTICE crediting the Iris and Oculus authors. LGPLv3 ⊂ our
LGPLv3 Sodium base → no relicensing of Yumelium is forced. Bundle glsl-transformer/ANTLR per their licenses (BSD).

## 9. Recommended immediate first step

Do **M0 only** as a cheap go/no-go: (1) a multi-target FBO + blit probe on our stack, (2) add glsl-transformer +
compile one transformed `#version 120` program, (3) stub `IrisApi` so `ShaderModBridge` toggles. All logged. If the
FBO round-trip and the GLSL transform/compile both succeed on the lwjglx/LWJGL3/Cleanroom/Java-25 stack, greenlight
M1+. If either fails, the port is not viable on this stack and we stop with minimal cost — exactly the Nvidium M0
model that de-risked that port before committing.
