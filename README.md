# Yumelium

**Sodium + Iris + Nvidium + Lithium for Minecraft 1.12.2 (Cleanroom), in a single mod.**

Yumelium is a rendering and performance overhaul for Minecraft 1.12.2 running on
[Cleanroom](https://github.com/CleanroomMC/Cleanroom). It ports the modern CaffeineMC stack — normally exclusive to
1.16+ — back to 1.12.2:

- **Sodium terrain engine** (based on Embeddium 0.5.8 / Sodium 0.5.8): meshed chunk rendering, occlusion culling,
  translucency sorting, and the full correctness layer for 1.12.2 (`getActualState` connected blocks, metadata
  block families, biome color blending).
- **Iris-style shader pipeline**: runs OptiFine/Iris shader packs — developed and verified against
  **Complementary Unbound r5.8.1 + Euphoria Patches** — with colored lighting (voxel flood-fill), world-space
  reflections, colored/entity/block-entity shadows, volumetric light, TAA, and per-dimension pipelines
  (Overworld / Nether / End). Visual parity is measured against real Iris 1.7.6 on 1.20.1.
- **Nvidium-style mesh-shader terrain** (NVIDIA GPUs): GPU-resident geometry with bindless mesh-shader
  rasterization, including full within-section translucency sorting. Suspended automatically while shaders are on.
- **Selective Lithium port**: the optimizations that actually help 1.12.2 (collision sweep, entity data tracker,
  pathfinding cache, sine LUT, explosion sweep) — each verified bit-identical to vanilla+Forge behaviour.
- **Connected glass (CTM)**: clear + 16 stained-glass blocks and panes, switchable Legacy (1.12.2) / New (1.14)
  texture styles.
- **Yumelium Plus**: sky/fog/weather toggles, zoom, texture-set switching, Nvidium settings, and an extended F3
  debug screen (renderer, shadow pass, culling, Nvidium stats).

## Requirements

- Minecraft **1.12.2** on **Cleanroom** (0.5.14-alpha or newer; regular Forge is not supported)
- Java 21+ (Cleanroom requirement)
- Shader packs are **not** bundled — download e.g.
  [Complementary Shaders](https://www.complementary.dev/) separately and drop it into `shaderpacks/`
- The Nvidium path additionally needs an NVIDIA GPU with `GL_NV_mesh_shader`

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Built on the
[CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) (custom Unimined fork; see that
repository for toolchain details).

## License

**LGPL-3.0** — see [LICENSE](LICENSE). Yumelium contains code derived from Sodium, Embeddium, Vintagium and
Lithium (all LGPL-3.0). See [CREDITS.md](CREDITS.md) for full attributions and third-party notices.

---

## 日本語

Yumelium は Cleanroom 上の Minecraft 1.12.2 向けに、Sodium(Embeddium 0.5.8 ベース)の地形描画エンジン、
Iris 互換のシェーダーパイプライン(Complementary Unbound で開発・検証)、Nvidium 方式のメッシュシェーダー描画、
Lithium の選別移植、接続ガラス(CTM)をひとつにまとめた描画・軽量化 MOD です。

- 前提: Cleanroom 0.5.14-alpha 以降 / Java 21+
- シェーダーパックは同梱していません。Complementary 等を各公式サイトから入手して `shaderpacks/` に入れてください
- ライセンスは LGPL-3.0、帰属表示は [CREDITS.md](CREDITS.md) を参照
