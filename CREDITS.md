# Credits & Third-Party Notices

Yumelium is licensed under **LGPL-3.0** (see [LICENSE](LICENSE)). It builds on, ports, or reimplements ideas from
the following projects:

## Code lineage (LGPL-3.0)

- **[Sodium](https://github.com/CaffeineMC/sodium-fabric)** — CaffeineMC / JellySquid.
  The terrain rendering engine (`me.jellysquid.mods.sodium`) is ported from Sodium 0.5.8 via Embeddium.
- **[Embeddium](https://github.com/embeddedt/embeddium)** — embeddedt.
  The 1.16.5/modernized branch is the direct base of the Sodium port.
- **[Vintagium](https://github.com/Asek3/sodium-1.12)** — Asek3.
  Reference implementation for the 1.12.2 world/light/biome access layer.
- **[Lithium](https://github.com/CaffeineMC/lithium-fabric)** — CaffeineMC / JellySquid / 2No2Name.
  The game-logic optimizations (`me.jellysquid.mods.lithium`) are a selective port of the 1.16.x branch.
  `CompactSineLUT` is based on coderbot16's original implementation in [i73](https://gitlab.com/coderbot16/i73).
- **[Iris](https://github.com/IrisShaders/Iris)** / **[Oculus](https://github.com/Asek3/Oculus)** — Iris Project / coderbot / Asek3.
  The shader pipeline (`com.yumelium.yumelium.shaders`) is an independent 1.12.2 reimplementation of the
  Iris/OptiFine shader-pack contract, referencing Iris 1.6-1.7 behaviour as the compatibility target.
- **[Nvidium](https://github.com/MCRcortex/nvidium)** — MCRcortex.
  The mesh-shader terrain path (`com.yumelium.yumelium.nvidium`) is an original implementation of the same concept.
- **[Continuity](https://github.com/PepperCode1/Continuity)** — PepperCode1.
  Referenced for the connected-textures (CTM) design; the CTM engine here is an original implementation.

## Template (MIT)

- **[CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate)** — kappa-maintainer / CleanroomMC.
  Build scaffolding (Gradle setup, Unimined fork wiring). Original notice:

  > MIT License — Copyright (c) 2025 kappa-maintainer.
  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
  > documentation files (the "Software"), to deal in the Software without restriction, including without limitation
  > the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software.
  > THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.

## Assets

- The switchable texture sets and generated CTM tiles are derived from **Minecraft** game assets.
  Minecraft is © Mojang Studios. These assets are NOT covered by this repository's LGPL-3.0 license and are
  included solely for use with the game.
- Shader packs (e.g. **Complementary Shaders** by EminGT, **Euphoria Patches** by SpacEagle17) are **not** bundled —
  download them separately from their official pages.

## Runtime

- **[Cleanroom](https://github.com/CleanroomMC/Cleanroom)** — the modernized 1.12.2 loader this mod targets.
