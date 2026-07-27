# Continuity (Connected Textures) Port Plan

**Goal:** Connected textures in the OptiFine/MCPatcher CTM format, applied inside our 1.12.2 Sodium/Nvidium mesher.

**License:** Continuity is **LGPL-3.0** (⊂ our LGPL Sodium base) — we may reference/adapt its code. But Continuity is Fabric + Fabric-Rendering-API (FRAPI) based, and we DROPPED FRAPI, so this is a **reimplementation** of the CTM concept against our mesher, referencing Continuity's algorithms — NOT a direct code port.

**Why in our mesher (not tterrag CTM / OptiFine):** those hook vanilla rendering; our mesher replaces vanilla terrain, so connected textures must be produced at *our* mesh time. This is the same hook + technique already used for BetterGrass.

## Key facts about our engine (grounded 2026-07-06)
- **Hook point:** `BlockRenderer.renderQuadList` iterates each `BakedQuadView quad`; BetterGrass already demonstrates the pattern: `quad = new BetterGrassQuad(quad, sprite, tintIndex)` — a `BakedQuadView` wrapper that overrides `getSprite()` + `getTexU/V()` (remaps rel-UV from the source sprite's atlas region into the target sprite's). **CtmQuad = same wrapper, target = the connected tile sprite.**
- **Neighbor access:** `BlockRenderContext.world()` = `WorldSlice` (IBlockAccess); `WorldSlice.getBlockState(x,y,z)` / `getBlockStateRelative`. `ctx.pos()` = the block pos. So connection queries are cheap and local.
- **Atlas:** register CTM tile textures into the block atlas via Forge `TextureStitchEvent.Pre` (`event.getMap().registerSprite(ResourceLocation)`), get the `TextureAtlasSprite` back at `TextureStitchEvent.Post` / via `map.getAtlasSprite(name)`. Events registered in `ClientProxy.preInit` (`MinecraftForge.EVENT_BUS.register`).
- **Sprite identity:** the mesher reads `quad.getSprite()` (a 1.12.2 `TextureAtlasSprite`, has `getIconName()`), so we match CTM rules by the quad's sprite name + the block state.
- **Config:** add `YumeliumPlusSettings.connectedTextures` toggle (REQUIRES_RENDERER_RELOAD), gate the whole feature.

## Architecture (`com.yumelium.yumelium.client.ctm`)
- **CtmQuad** — BakedQuadView wrapper: sprite + UV remap (clone of BetterGrassQuad, generalized target sprite). FOUNDATION.
- **CtmProperties** — parse one OptiFine `.properties` (method, matchTiles, matchBlocks, tiles, connect, faces, symmetry, ...).
- **CtmLoader** — on resource reload, scan every loaded resource pack for `assets/<ns>/optifine/ctm/**.properties` (+ legacy `mcpatcher/ctm/**`), build the rule registry; collect the set of tile ResourceLocations to stitch.
- **CtmStitchHandler** — `@SubscribeEvent TextureStitchEvent.Pre` registers all collected tile sprites; resolves them to `TextureAtlasSprite` for the registry after stitch.
- **CtmRegistry** — lookup: (spriteName, blockState) → applicable CtmRule(s). Fast path: `Map<String spriteName, List<CtmRule>>` + per-rule block/tile match test.
- **CtmMethod / CtmRule** — method enum + resolved rule (tile sprites[], connect mode, faces, per-method tile selector).
- **Connection logic** — per method, build the neighbor connection bitmask (which of the 4/8 neighbors "connect" per `connect=block|tile|state|material`) → tile index:
  - `ctm` (full): 8-neighbor → 47-tile via the standard OptiFine CTM index table.
  - `ctm_compact`: 8-neighbor → 5 tiles (0..4) + reuse of the 47-set (compact packs ship 5, expand).
  - `horizontal` / `vertical`: 2 in-plane neighbors → 4 tiles.
  - `horizontal+vertical` / `vertical+horizontal`: try horizontal, fall back vertical (or vice-versa).
  - `repeat`: tile = f(worldPos mod width/height).
  - `random`: tile = hash(worldPos, face) → weighted pick.
  - `fixed`: tile[0].
- **Mesher hook** (BlockRenderer.renderQuadList): if CTM enabled and a rule matches the quad's sprite+block+face, compute the tile sprite and wrap `quad = new CtmQuad(quad, tileSprite)`. Ordering vs BetterGrass: apply CTM to the base sprite; BetterGrass already special-cases grass sides (they rarely have CTM). Keep independent for now.

## Phasing
- **P0 FOUNDATION:** package + `CtmQuad` (generalized BetterGrassQuad) + `connectedTextures` config toggle + `CtmStitchHandler`/`CtmLoader` skeleton that scans resource packs for `.properties` and LOGS what it found (proves the discovery + stitch pipeline). No connection logic yet. Build green.
- **P1 CORE `ctm`:** real `.properties` parse (method/matchTiles/matchBlocks/tiles/connect) + tile stitching + the 47-tile `ctm` method + mesher hook. Test on **glass / glass panes** (canonical CTM). Verify connected glass in-game.
- **P2 MORE METHODS:** ctm_compact, horizontal, vertical, h+v, v+h, repeat, random, fixed. Test on bookshelves/logs/sandstone packs.
- **P3 OVERLAY + EMISSIVE (deferred):** overlay/overlay_ctm/overlay_random/overlay_repeat/overlay_fixed (extra tinted edge layer = extra quads), then emissive textures (full-bright pass — larger, needs an emissive material/pass).

## Risks / open questions
- OptiFine `.properties` has MANY optional keys; P1 supports the core subset, warn+skip unknowns.
- `connect=tile` vs `block` vs `state`: default is `block` for matchBlocks, `tile` for matchTiles (OptiFine rule). Get defaults right.
- Sprite-name matching: 1.12.2 sprite `getIconName()` = e.g. `minecraft:blocks/glass`; matchTiles entries are tile file names → normalize.
- Animated CTM tiles + our texture-swap feature: CTM tiles come from resource packs (higher priority than our embedded set) so they compose fine.
- Atlas size: many CTM tiles can bloat the block atlas (OptiFine has the same cost). Acceptable.
