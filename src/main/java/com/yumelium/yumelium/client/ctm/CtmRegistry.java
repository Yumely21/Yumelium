package com.yumelium.yumelium.client.ctm;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Built-in connected-texture rules and their atlas tile sprites. A rule is keyed by the base sprite a block's quads use
 * and applies to a set of blocks with a connect mode; it holds the 47-tile {@code ctm} set. Tiles are registered into
 * the block atlas at stitch time and resolved here; the mesher queries {@link #tileFor} per quad. External CTM
 * resource-pack ({@code .properties}) parsing is a later phase — for now these are the mod's built-in switchable sets.
 */
public final class CtmRegistry {
    private static final CtmRegistry INSTANCE = new CtmRegistry();

    /** 1.12.2 stained-glass colour sprite suffixes (silver = light gray). */
    private static final String[] STAINED_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    public static CtmRegistry instance() {
        return INSTANCE;
    }

    /** One rule: the blocks it applies to, whether a neighbour must be the exact same state, and the 47 tile sprites. */
    private static final class Rule {
        final Set<Block> blocks;
        final boolean sameState;
        final TextureAtlasSprite[] tiles; // 47

        Rule(Set<Block> blocks, boolean sameState, TextureAtlasSprite[] tiles) {
            this.blocks = blocks;
            this.sameState = sameState;
            this.tiles = tiles;
        }
    }

    private final Map<String, Rule> rulesBySprite = new HashMap<>(); // base sprite name -> rule
    private volatile boolean active;

    private CtmRegistry() {
    }

    public boolean isActive() {
        return this.active;
    }

    /** @return true if the block is a glass pane (its CTM quads use {@link PaneCtmQuad}, not {@link CtmQuad}). */
    public static boolean isPane(Block block) {
        return block == Blocks.GLASS_PANE || block == Blocks.STAINED_GLASS_PANE;
    }

    public void clear() {
        this.rulesBySprite.clear();
        this.active = false;
    }

    /**
     * Register the connected-texture tile sprites into the block atlas and build the rules.
     *
     * <p>The tiles are NOT shipped with the mod — a distributed mod jar must not carry Minecraft's assets — so they come
     * from the separate Yumelium companion resource pack (or any pack that provides the same paths). The rules and the
     * mesher are ours and always present; only the artwork is external.</p>
     *
     * @return false when no pack provides the tiles, i.e. nothing was registered.
     */
    public boolean registerBuiltins(TextureMap map) {
        this.rulesBySprite.clear();

        // Probe first. registerSprite on a path no resource pack provides does NOT fail — Minecraft stitches its
        // magenta/black "missing texture" placeholder instead, so without this every pane of glass would turn into a
        // checkerboard: strictly worse than simply leaving glass alone.
        if (!tilesAvailable()) {
            this.active = false;
            return false;
        }

        // Clear glass — block + pane (pane quads use the dedicated PaneCtmQuad UV, see the mesher).
        Set<Block> glass = Set.of(Blocks.GLASS, Blocks.GLASS_PANE);
        addRule(map, "minecraft:blocks/glass", glass, false, TILE_ROOT + "/glass");

        // Stained glass — 16 colours; block + pane connect only to the same STATE (colour).
        Set<Block> stained = Set.of(Blocks.STAINED_GLASS, Blocks.STAINED_GLASS_PANE);
        for (String c : STAINED_COLORS) {
            addRule(map, "minecraft:blocks/glass_" + c, stained, true, TILE_ROOT + "/glass_" + c);
        }

        this.active = !this.rulesBySprite.isEmpty();
        return this.active;
    }

    /** Where the companion resource pack puts the tiles: {@code assets/minecraft/textures/ctm/<set>/0..46.png}. */
    private static final String TILE_ROOT = "ctm";

    /** @return true if a resource pack actually provides the tiles (probed with one representative tile). */
    private static boolean tilesAvailable() {
        ResourceLocation probe = new ResourceLocation("minecraft", "textures/" + TILE_ROOT + "/glass/0.png");
        try {
            net.minecraft.client.Minecraft.getMinecraft().getResourceManager().getResource(probe);
            return true;
        } catch (java.io.IOException e) {
            return false; // pack not installed — the normal case, not an error
        } catch (Throwable t) {
            return false;
        }
    }

    private void addRule(TextureMap map, String baseSpriteName, Set<Block> blocks, boolean sameState, String tileDir) {
        TextureAtlasSprite[] tiles = new TextureAtlasSprite[47];
        for (int i = 0; i < 47; i++) {
            tiles[i] = map.registerSprite(new ResourceLocation("minecraft", tileDir + "/" + i));
        }
        this.rulesBySprite.put(baseSpriteName, new Rule(blocks, sameState, tiles));
    }

    /**
     * @return the connected-texture tile sprite for this quad, or {@code null} if the block/sprite/face has no rule.
     */
    public TextureAtlasSprite tileFor(IBlockState state, String quadSpriteName, EnumFacing face,
                                      IBlockAccess world, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        if (!this.active || face == null) {
            return null;
        }
        Rule rule = this.rulesBySprite.get(quadSpriteName);
        if (rule == null || !rule.blocks.contains(state.getBlock())) {
            return null;
        }
        int idx = Ctm47.tileIndex(world, x, y, z, state, rule.sameState, face, scratch);
        return rule.tiles[idx];
    }
}
