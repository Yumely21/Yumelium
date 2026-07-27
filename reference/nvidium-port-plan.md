# Nvidium (mesh-shader terrain backend) → Yumelium / 1.12.2 — Port Plan

Status: **PLAN ONLY** (2026-07-05). No implementation yet. This document scopes a port of Nvidium's NVIDIA
mesh-shader terrain-rendering backend onto Yumelium's ported Embeddium 0.5.x engine on Minecraft 1.12.2 (Cleanroom).

## 1. Verdict

**Portable in principle — but it is the single largest task in the project, on the scale of the whole Sodium engine
port.** Every hard prerequisite is satisfied; the cost is scope, not a blocker.

Reference base for the port = **Alphadium** (`me.cortex.alphadium`, the LGPL-3.0 community revival of MCRcortex's
Nvidium). We port *from Alphadium* (LGPL-3.0 is compatible with our LGPLv3 Sodium base); we do **not** copy the
original Nvidium (historically restrictively licensed).

## 2. Prerequisites — all verified

| Requirement | Status |
|---|---|
| GPU mesh-shader support (Turing+) | ✅ user's RTX 4070 (Ada) |
| GL context | ✅ OpenGL 4.6 (NVIDIA 610.62) per runClient log |
| LWJGL API surface | ✅ lwjgl-opengl-3.4.1 ships `NVMeshShader`, `NVCommandList`, `NV/ARBBindlessTexture`, `NVBindlessMultiDrawIndirect(Count)` |
| Engine integration hooks present | ✅ all Sodium classes Alphadium mixes into exist in our engine (see §4) |
| Engine cooperation hook | ✅ `org.embeddedt.embeddium.render.ShaderModBridge` already has `isNvidiumEnabled()` + `emulateLegacyColorBrightnessFormat()` |
| ⚠️ Runtime: does the lwjglx shim actually dispatch `glDrawMeshTasksNV`/bindless to the real 4.6 context? | **UNVERIFIED — this is the go/no-go gate (M0)** |

## 3. What Nvidium/Alphadium is (architecture)

An alternative terrain path that keeps all chunk geometry **GPU-resident** and draws it with **NV mesh shaders**,
doing **GPU-driven culling** — the CPU essentially stops touching per-section draw calls.

- **Geometry storage**: section meshes live in large persistent/sparse **bindless** buffers
  (`PersistentSparseAddressableBuffer`, `BufferArena`, `UploadingBufferStream`), addressed by GPU pointers — not
  per-region VBOs.
- **Draw**: task+mesh shaders (`terrain/task.glsl`,`terrain/mesh.glsl`) expand the compact per-section quad data into
  triangles on-GPU; `frag.frag` shades. Separate translucent variants + a temporal (previous-frame) rasterizer.
- **Culling** (all on GPU): `occlusion/` shaders rasterize region/section bounding boxes into a depth/visibility
  buffer (`region_raster`, `section_raster`, `queries/region`), an `AsyncOcclusionTracker` reads results back, and a
  `command_buffer_builder.comp` compute shader builds the indirect draw command buffer. Translucent ordering via
  `sorting/region_section_sorter.comp` + `sorting_network.glsl`.
- **Sodium coupling**: it does NOT re-mesh — it **intercepts Sodium's chunk build output** (the already-built compact
  mesh) and copies it into its GPU buffers, then takes over the draw. Managers: `RegionManager`, `SectionManager`,
  `RegionVisibilityTracker`.

## 4. Integration surface — Alphadium hook → our engine (all present ✅)

Alphadium mixes into these Sodium classes; every one exists in our port (Embeddium 0.5.x shares Sodium's arch):

| Alphadium mixin/target | Our engine class |
|---|---|
| `MixinChunkBuildOutput` | `…/render/chunk/compile/ChunkBuildOutput.java` |
| `MixinChunkBuilderMeshingTask` | `…/compile/tasks/ChunkBuilderMeshingTask.java` |
| `MixinChunkJobQueue` | `…/compile/executor/ChunkJobQueue.java` |
| `MixinRenderRegionManager` | `…/render/chunk/region/RenderRegionManager.java` |
| `MixinRenderSection` | `…/render/chunk/RenderSection.java` |
| `MixinRenderSectionManager` | `…/render/chunk/RenderSectionManager.java` |
| `MixinSodiumWorldRenderer` | our `SodiumWorldRenderer.java` (heavily custom for 1.12.2) |
| `AlphadiumCompactChunkVertex` / `sodium_vertex_format.glsl` | `…/vertex/format/impl/CompactChunkVertex.java` (STRIDE=20) |
| `BuiltSectionMeshParts` (build result) | `…/render/chunk/data/BuiltSectionMeshParts.java` |

The plug-in point is the **section build result** (`ChunkBuildOutput` / `BuiltSectionMeshParts` produced by
`ChunkBuilderMeshingTask`) — Nvidium takes the compact vertex buffer from there.

## 5. Key adaptation challenges (why it's not a straight copy)

1. **Sodium 0.8.6 → Embeddium 0.5.x API drift.** Class *names* match, but method signatures/internal data differ
   between the versions. Every mixin target must be re-verified against our 0.5.x classes (much like the whole engine
   port already required).
2. **1.12.2 render loop.** Our `SodiumWorldRenderer`/`MixinRenderGlobal` are already a bespoke 1.12.2 rewrite
   (fixed-function GL matrices, @Overwrite of `renderBlockLayer`). Alphadium's `MixinSodiumWorldRenderer` assumptions
   (1.21 render pipeline, `Viewport`, matrices) must be re-mapped to ours.
3. **GLSL / shader toolchain.** Mesh/task shaders need `#version 460` + `#extension GL_NV_mesh_shader`; our current
   chunk shaders are `#version 330 core`. Alphadium's GLSL can largely be reused as-is (its own stage), but the
   loader (`sodiumCompat/ShaderLoader`) + `#import` handling must be ported to our `ShaderLoader`, and the vertex
   format glue matched to our 20-byte `CompactChunkVertex`.
4. **LWJGL via lwjglx shim.** Real GL4.6 calls work (engine renders), and `GL42C/GL43C/GL44C` are used already, so
   `NVMeshShader`/bindless *should* dispatch — **but this must be proven at runtime first (M0)**. Bindless texture
   handles + `glProgramUniformHandenceui64NV` etc. are the riskiest for the shim.
5. **Vertex-color format cooperation.** `ShaderModBridge.emulateLegacyColorBrightnessFormat()` already flips the
   `ChunkColorWriter` when Nvidium is "enabled" — we must set our internal `IS_ENABLED` flag so this path activates,
   and confirm our compact color/light encoding matches what the mesh shader expects.
6. **Shader-mod mutual exclusion.** Nvidium disables when Iris/Oculus shaders are on. Our future Iris port and this
   are runtime-exclusive by design — the toggle logic must enforce that.

## 6. Phased milestones (each is a session-sized chunk; ✅ = go/no-go gate)

- **M0 — Capability probe (GATE).** Add a tiny runtime test: create a trivial mesh-shader program + one
  `glDrawMeshTasksNV`, check `GL.getCapabilities().GL_NV_mesh_shader`, `GL_ARB_bindless_texture`,
  `GL_NV_shader_buffer_load`. Confirm the lwjglx shim dispatches them without error on the RTX 4070. **If this fails,
  the port is impossible on this stack — stop here.**
- **M1 — GL abstractions.** Port Alphadium's `gl/buffers/*` (persistent-mapped, device-only, sparse addressable
  bindless buffers), `BufferArena`, `UploadingBufferStream`, and the mesh-shader program/pipeline wrapper. Standalone,
  testable with a dummy draw.
- **M2 — Geometry residency.** `SectionManager`/`RegionManager` + the mixin on `ChunkBuildOutput`/meshing task to
  divert the compact mesh into the bindless arena. Keep vanilla Sodium draw active; just mirror the data.
- **M3 — Opaque terrain rasterizer.** `PrimaryTerrainRasterizer` + `terrain/task.glsl`+`mesh.glsl`+`frag.frag` +
  vertex-format glue; a toggle that swaps `DefaultChunkRenderer` draw for the mesh-shader draw (opaque pass only).
  First visible milestone.
- **M4 — GPU-driven culling.** `occlusion/*` region/section raster + `AsyncOcclusionTracker` +
  `command_buffer_builder.comp` + `RegionVisibilityTracker`. This is where the real FPS win lands.
- **M5 — Translucent + sorting.** Translucent rasterizer + `sorting/*` compute; temporal rasterizer.
- **M6 — Integration/UX.** `IS_ENABLED` flag wired to `ShaderModBridge`, an Advanced/Yumelium-Plus toggle
  ("Nvidium Renderer", gated on M0 capability + NVIDIA vendor), graceful fallback to `DefaultChunkRenderer`, and hard
  mutual-exclusion with the (future) Iris path.
- **M7 — Perf/stability.** Crash hardening (mesh shaders are crash-prone), driver-blacklist respect, memory tuning,
  block-entity/mob paths still driven by our existing hooks.

## 7. Risks / blockers

- **[HIGH] lwjglx shim mesh-shader/bindless dispatch** — the whole port hinges on M0.
- **[HIGH] Effort** — 6–8 sessions minimum; comparable to the Sodium port itself.
- **[MED] 0.8.6↔0.5.x drift** — every hook re-verified; some Alphadium logic assumes newer Sodium data structures.
- **[MED] Crash-proneness** — cutting-edge NV path; needs robust guards + fallback.
- **[LOW-MED] NVIDIA-only + shader-exclusive** — no benefit for non-NVIDIA users; cannot run with the planned
  Iris/Oculus shader support simultaneously (conflicts with the project's "complete shader support" goal — the two are
  a user-choice toggle, never both).

## 8. License

Port from **Alphadium (LGPL-3.0)**. Keep it a separable subtree (`me/cortex/…`-style package or a `yumelium.nvidium`
package) with the LGPL-3.0 header + NOTICE crediting MCRcortex (original) and the Alphadium authors. LGPLv3 ⊂ our
LGPLv3 Sodium base, so no relicensing of Yumelium is forced (unlike GPL-only code).

## 9. Recommended immediate first step

Do **M0 only** as a cheap go/no-go: a ~1-file capability probe + a single trivial mesh-shader draw, logged. If the
shim dispatches `glDrawMeshTasksNV` on the RTX 4070, greenlight M1+. If not, the port is not viable on this
LWJGL/shim stack and we stop with minimal cost.
