package com.yumelium.yumelium.shaders.pack;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.RegistryNamespaced;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Resolves the shader pack's {@code block.properties} ids against this 1.12.2 instance, producing the
 * {@code IBlockState → id} lookup {@code BlockRenderer} feeds to {@code mc_Entity.x}.
 *
 * <p>The hard part is the FLATTENING: block.properties speaks 1.13+ names, 1.12.2 speaks {@code registryName + metadata}.
 * Some names carried over unchanged ({@code obsidian}, {@code bedrock}, {@code glowstone}); many split a 1.12.2 block's
 * metadata into separate 1.13 blocks ({@code stonebrick:0..3} → {@code stone_bricks} / {@code mossy_stone_bricks} /
 * {@code cracked_stone_bricks} / {@code chiseled_stone_bricks}); and a few RENAMED across the boundary in a way that
 * collides — notably 1.12.2 {@code grass} is the grass BLOCK (1.13 {@code grass_block}) while 1.13 {@code grass} is the
 * PLANT (1.12.2 {@code tallgrass:1}). A blind name match would mis-assign those, so they are handled explicitly.</p>
 *
 * <p>Built by walking the real block registry and asking each state for its 1.13 name, so coverage is measured against
 * what this instance actually has rather than assumed. The resolve logs how many states matched and lists a sample of the
 * misses — that report is the tool for growing {@link #FLATTENED} where it matters, instead of guessing.</p>
 */
public final class BlockIdMapper {

    /**
     * 1.12.2 {@code registryName[:meta]} → 1.13+ name, for the cases a direct name match gets wrong or misses.
     *
     * <p>Keyed with an explicit meta only where the 1.12.2 block's metadata selects a different 1.13 block. Entries
     * without a meta apply to every meta of that block.</p>
     */
    private static final Map<String, String> FLATTENED = new HashMap<>();

    private static void put(String mc112, String mc113) {
        FLATTENED.put(mc112, mc113);
    }

    static {
        // --- renames that COLLIDE with a different 1.13 block (must not fall through to a name match) ---
        put("grass", "grass_block");           // 1.13 `grass` is the plant; the block is `grass_block`
        put("tallgrass:1", "grass");           // ...and the 1.13 plant `grass` is this
        put("tallgrass:2", "fern");
        put("tallgrass:0", "dead_bush");       // shrub
        put("mycelium", "mycelium");
        put("slime", "slime_block");
        put("web", "cobweb");
        put("snow_layer", "snow");
        put("snow", "snow_block");             // 1.12.2 `snow` (full block) is 1.13 `snow_block`
        put("lit_pumpkin", "jack_o_lantern");
        put("melon_block", "melon");
        put("waterlily", "lily_pad");
        put("noteblock", "note_block");
        put("piston_extension", "moving_piston");
        put("lit_redstone_lamp", "redstone_lamp");
        put("redstone_lamp", "redstone_lamp");
        put("lit_furnace", "furnace");
        put("lit_redstone_ore", "redstone_ore");
        put("portal", "nether_portal");
        put("end_portal_frame", "end_portal_frame");
        put("mob_spawner", "spawner");
        put("enchanting_table", "enchanting_table");
        put("brewing_stand", "brewing_stand");
        put("cauldron", "cauldron");
        put("standing_sign", "oak_sign");
        put("wall_sign", "oak_wall_sign");
        put("standing_banner", "white_banner");
        put("wall_banner", "white_wall_banner");
        put("trapdoor", "oak_trapdoor");
        put("iron_trapdoor", "iron_trapdoor");
        put("wooden_button", "oak_button");
        put("wooden_pressure_plate", "oak_pressure_plate");
        put("wooden_door", "oak_door");
        put("fence", "oak_fence");
        put("fence_gate", "oak_fence_gate");
        put("wooden_slab:0", "oak_slab");
        put("wooden_slab:1", "spruce_slab");
        put("wooden_slab:2", "birch_slab");
        put("wooden_slab:3", "jungle_slab");
        put("wooden_slab:4", "acacia_slab");
        put("wooden_slab:5", "dark_oak_slab");
        put("stone_slab:0", "smooth_stone_slab");
        put("stone_slab:1", "sandstone_slab");
        put("stone_slab:3", "cobblestone_slab");
        put("stone_slab:4", "brick_slab");
        put("stone_slab:5", "stone_brick_slab");
        put("stone_slab:6", "nether_brick_slab");
        put("stone_slab:7", "quartz_slab");
        put("stone_slab2:0", "red_sandstone_slab");
        put("stone_stairs", "cobblestone_stairs");
        put("oak_stairs", "oak_stairs");
        put("brick_block", "bricks");
        put("stonebrick:0", "stone_bricks");
        put("stonebrick:1", "mossy_stone_bricks");
        put("stonebrick:2", "cracked_stone_bricks");
        put("stonebrick:3", "chiseled_stone_bricks");
        put("stone:0", "stone");
        put("stone:1", "granite");
        put("stone:2", "polished_granite");
        put("stone:3", "diorite");
        put("stone:4", "polished_diorite");
        put("stone:5", "andesite");
        put("stone:6", "polished_andesite");
        put("dirt:0", "dirt");
        put("dirt:1", "coarse_dirt");
        put("dirt:2", "podzol");
        put("sand:0", "sand");
        put("sand:1", "red_sand");
        put("sandstone:0", "sandstone");
        put("sandstone:1", "chiseled_sandstone");
        put("sandstone:2", "cut_sandstone");
        put("red_sandstone:0", "red_sandstone");
        put("red_sandstone:1", "chiseled_red_sandstone");
        put("red_sandstone:2", "cut_red_sandstone");
        put("log:0", "oak_log");
        put("log:1", "spruce_log");
        put("log:2", "birch_log");
        put("log:3", "jungle_log");
        put("log2:0", "acacia_log");
        put("log2:1", "dark_oak_log");
        put("planks:0", "oak_planks");
        put("planks:1", "spruce_planks");
        put("planks:2", "birch_planks");
        put("planks:3", "jungle_planks");
        put("planks:4", "acacia_planks");
        put("planks:5", "dark_oak_planks");
        put("leaves:0", "oak_leaves");
        put("leaves:1", "spruce_leaves");
        put("leaves:2", "birch_leaves");
        put("leaves:3", "jungle_leaves");
        put("leaves2:0", "acacia_leaves");
        put("leaves2:1", "dark_oak_leaves");
        put("sapling:0", "oak_sapling");
        put("sapling:1", "spruce_sapling");
        put("sapling:2", "birch_sapling");
        put("sapling:3", "jungle_sapling");
        put("sapling:4", "acacia_sapling");
        put("sapling:5", "dark_oak_sapling");
        put("prismarine:0", "prismarine");
        put("prismarine:1", "prismarine_bricks");
        put("prismarine:2", "dark_prismarine");
        put("quartz_block:0", "quartz_block");
        put("quartz_block:1", "chiseled_quartz_block");
        put("quartz_block:2", "quartz_pillar");
        put("red_flower:0", "poppy");
        put("red_flower:1", "blue_orchid");
        put("red_flower:2", "allium");
        put("red_flower:3", "azure_bluet");
        put("red_flower:4", "red_tulip");
        put("red_flower:5", "orange_tulip");
        put("red_flower:6", "white_tulip");
        put("red_flower:7", "pink_tulip");
        put("red_flower:8", "oxeye_daisy");
        put("yellow_flower", "dandelion");
        put("double_plant:0", "sunflower");
        put("double_plant:1", "lilac");
        put("double_plant:2", "tall_grass");
        put("double_plant:3", "large_fern");
        put("double_plant:4", "rose_bush");
        put("double_plant:5", "peony");
        put("deadbush", "dead_bush");
        put("reeds", "sugar_cane");
        put("wheat", "wheat");
        put("carrots", "carrots");
        put("potatoes", "potatoes");
        put("melon_stem", "melon_stem");
        put("pumpkin_stem", "pumpkin_stem");
        put("cocoa", "cocoa");
        put("netherrack", "netherrack");
        put("soul_sand", "soul_sand");
        put("nether_brick", "nether_bricks");
        put("nether_wart", "nether_wart");
        put("end_stone", "end_stone");
        put("end_bricks", "end_stone_bricks");
        put("magma", "magma_block");
        put("torch", "torch");
        put("redstone_torch", "redstone_torch");
        put("unlit_redstone_torch", "redstone_torch");
        put("glowstone", "glowstone");
        put("sea_lantern", "sea_lantern");
        put("obsidian", "obsidian");
        put("ice", "ice");
        put("packed_ice", "packed_ice");
        put("frosted_ice", "frosted_ice");
        put("glass", "glass");
        put("water", "water");
        put("flowing_water", "water");
        put("lava", "lava");
        put("flowing_lava", "lava");
        put("fire", "fire");
        put("end_rod", "end_rod");
        put("beacon", "beacon");
        put("chorus_plant", "chorus_plant");
        put("chorus_flower", "chorus_flower");
        put("dragon_egg", "dragon_egg");
        put("redstone_wire", "redstone_wire");
        put("tripwire_hook", "tripwire_hook");
        put("iron_bars", "iron_bars");
        put("ladder", "ladder");
        put("vine", "vine");
        put("cactus", "cactus");
        put("pumpkin", "carved_pumpkin"); // 1.12.2 `pumpkin` is faced → 1.13 carved_pumpkin

        // --- the remainder the coverage report named: 1.13 dissolved these into ordinary full blocks / merged states ---
        // Double slabs stopped existing in 1.13 (a double slab is just the full block).
        put("double_stone_slab:0", "smooth_stone");
        put("double_stone_slab:1", "sandstone");
        put("double_stone_slab:2", "oak_planks");
        put("double_stone_slab:3", "cobblestone");
        put("double_stone_slab:4", "bricks");
        put("double_stone_slab:5", "stone_bricks");
        put("double_stone_slab:6", "nether_bricks");
        put("double_stone_slab:7", "quartz_block");
        put("double_stone_slab:8", "smooth_stone");   // the "seamless" variants (meta | 8)
        put("double_stone_slab:9", "smooth_sandstone");
        put("double_stone_slab:15", "smooth_quartz");
        put("double_stone_slab2:0", "red_sandstone");
        put("double_stone_slab2:8", "smooth_red_sandstone");
        put("double_wooden_slab:0", "oak_planks");
        put("double_wooden_slab:1", "spruce_planks");
        put("double_wooden_slab:2", "birch_planks");
        put("double_wooden_slab:3", "jungle_planks");
        put("double_wooden_slab:4", "acacia_planks");
        put("double_wooden_slab:5", "dark_oak_planks");
        // Silverfish blocks got an `infested_` prefix.
        put("monster_egg:0", "infested_stone");
        put("monster_egg:1", "infested_cobblestone");
        put("monster_egg:2", "infested_stone_bricks");
        put("monster_egg:3", "infested_mossy_stone_bricks");
        put("monster_egg:4", "infested_cracked_stone_bricks");
        put("monster_egg:5", "infested_chiseled_stone_bricks");
        // Powered/unpowered pairs merged into one block with a `powered` state.
        put("unpowered_comparator", "comparator");
        put("powered_comparator", "comparator");
        put("unpowered_repeater", "repeater");
        put("powered_repeater", "repeater");
        // Named by the coverage report after the MC_VERSION-section preprocessing (2026-07-25): these were matched
        // through the pack's LEGACY 1.12-name sections before; with only the modern section parsed they need real
        // flattened names.
        put("quartz_ore", "nether_quartz_ore");
        put("golden_rail", "powered_rail");
        put("skull", "skeleton_skull");                          // per-mob split is TILE data, not blockstate
        put("daylight_detector_inverted", "daylight_detector");  // inverted became a state
        put("purpur_double_slab", "purpur_block");               // double slabs stopped existing in 1.13
        put("red_nether_brick", "red_nether_bricks");
        put("silver_shulker_box", "light_gray_shulker_box");     // registry uses silver_, dye family light_gray
        put("silver_glazed_terracotta", "light_gray_glazed_terracotta");
    }

    /** Colour-indexed families: 1.12.2 one block + meta 0..15 → sixteen 1.13 blocks. */
    private static final String[] DYE_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    static {
        for (int i = 0; i < 16; i++) {
            String c = DYE_COLORS[i];
            put("wool:" + i, c + "_wool");
            put("stained_glass:" + i, c + "_stained_glass");
            put("stained_glass_pane:" + i, c + "_stained_glass_pane");
            put("stained_hardened_clay:" + i, c + "_terracotta");
            put("concrete:" + i, c + "_concrete");
            put("concrete_powder:" + i, c + "_concrete_powder");
            put("carpet:" + i, c + "_carpet");
            put("bed:" + i, c + "_bed");
        }
        put("hardened_clay", "terracotta");
    }

    private final Map<IBlockState, Integer> byState = new IdentityHashMap<>();

    /** Every id the pack DECLARED, by its 1.13+ name — including names no block on this instance resolves to. */
    private final Map<String, Integer> declaredByName = new HashMap<>();

    /** @return the pack id for this state, or 0 when the pack assigns it none (the pack treats 0 as "no material"). */
    public int idFor(IBlockState state) {
        Integer v = this.byState.get(state);
        return v == null ? 0 : v;
    }

    /**
     * @return the id the pack declared for a 1.13+ block NAME, or 0 if it declared none.
     *
     * <p>Unlike {@link #idFor}, this answers for names that no 1.12.2 block maps to — which is the point: it lets the
     * mesher hand a block an id for a material this version does not have. Used for {@code soul_fire} (a 1.16+ block;
     * 1.12.2 fire on soul sand is plain {@code minecraft:fire}, indistinguishable to the shader). Reading the id from the
     * pack instead of hardcoding 10076 keeps it correct if the pack renumbers, and yields 0 — i.e. the emulation simply
     * does not engage — for a pack that has no such material.</p>
     */
    public int idForPackName(String name) {
        Integer v = this.declaredByName.get(name);
        return v == null ? 0 : v;
    }

    public int size() {
        return this.byState.size();
    }

    /**
     * 1.12.2 encodes some 1.13 boolean properties as SEPARATE BLOCKS (lit_furnace vs furnace, redstone_torch vs
     * unlit_redstone_torch, …). Those blocks can't express the pack's {@code lit=}/{@code powered=} qualifiers as
     * state properties, but the block IDENTITY pins the value — without this, both siblings wildcard-matched and
     * later-entry-wins gave unlit lamps the lit glow and COST lit redstone torches their voxel light (measured in
     * the id-delta log). Keyed by 1.12.2 registry path; value = {property, value}.
     */
    private static final Map<String, String[]> IMPLIED = new HashMap<>();

    static {
        IMPLIED.put("furnace", new String[]{"lit", "false"});
        IMPLIED.put("lit_furnace", new String[]{"lit", "true"});
        IMPLIED.put("redstone_ore", new String[]{"lit", "false"});
        IMPLIED.put("lit_redstone_ore", new String[]{"lit", "true"});
        IMPLIED.put("redstone_lamp", new String[]{"lit", "false"});
        IMPLIED.put("lit_redstone_lamp", new String[]{"lit", "true"});
        IMPLIED.put("redstone_torch", new String[]{"lit", "true"});      // the plain 1.12.2 block IS the lit one
        IMPLIED.put("unlit_redstone_torch", new String[]{"lit", "false"});
        IMPLIED.put("powered_repeater", new String[]{"powered", "true"});
        IMPLIED.put("unpowered_repeater", new String[]{"powered", "false"});
        IMPLIED.put("powered_comparator", new String[]{"powered", "true"});
        IMPLIED.put("unpowered_comparator", new String[]{"powered", "false"});
    }

    /**
     * @return whether every qualifier the state's block CAN express matches; qualifiers naming a property the block
     * does not have fall back to the block-identity {@link #IMPLIED} value, then to wildcard (1.13+-only:
     * waterlogged= etc.). See the byName note in {@link #build}.
     */
    private static boolean stateMatches(String base, IBlockState state, Map<String, String> quals) {
        if (quals.isEmpty()) {
            return true;
        }
        String[] implied = IMPLIED.get(base);
        for (Map.Entry<String, String> q : quals.entrySet()) {
            boolean resolved = false;
            for (Map.Entry<net.minecraft.block.properties.IProperty<?>, Comparable<?>> p : state.getProperties().entrySet()) {
                if (p.getKey().getName().equals(q.getKey())) {
                    if (!serializedValue(p.getKey(), p.getValue()).equals(q.getValue())) {
                        return false; // the block CAN express this property and the value differs
                    }
                    resolved = true;
                    break;
                }
            }
            if (!resolved && implied != null && implied[0].equals(q.getKey()) && !implied[1].equals(q.getValue())) {
                return false; // the block identity pins this property to a different value
            }
        }
        return true;
    }

    /** The property value's serialized name ("true"/"lower"/"south"), matching block.properties' spelling. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String serializedValue(net.minecraft.block.properties.IProperty prop, Comparable value) {
        return prop.getName(value);
    }

    /**
     * Builds the state→id lookup by walking the block registry, deriving each state's 1.13 name, and matching it against
     * the parsed block.properties. Logs coverage + a sample of unmatched blocks so the FLATTENED table can be grown from
     * evidence.
     */
    public static BlockIdMapper build(BlockProperties props) {
        BlockIdMapper m = new BlockIdMapper();

        // name → its entries IN FILE ORDER, qualifiers kept. Resolution per state: an entry applies when every
        // qualifier the 1.12.2 block CAN express matches the state's actual value; qualifiers the block has no
        // property for (1.13+-only: lit=, waterlogged=, ...) are unverifiable and treated as wildcards — that keeps
        // e.g. lit_furnace (a separate 1.12.2 block with no `lit` property) on the pack's furnace:lit=true id.
        // Blindly folding qualifiers away (the old behaviour) made LAST-entry-wins decide whole families: every
        // end_portal_frame state took `eye=true`'s 10556, whose Active material has a WORLD-POSITION-dependent
        // ender-eye emission — held in the first person, fract(cameraPosition) swept through the eye zone and the
        // item strobed while walking (the END held-item flicker).
        Map<String, java.util.List<BlockProperties.Entry>> byName = new HashMap<>();
        for (BlockProperties.Entry e : props.entries()) {
            byName.computeIfAbsent(e.name, k -> new java.util.ArrayList<>()).add(e);
            m.declaredByName.put(e.name, e.id); // last-wins DECLARED table — see idForPackName
        }
        // DIAGNOSTIC (END terrain shadow blink, 2026-07-25): name every id the property-aware matching CHANGED
        // relative to the old qualifier fold-down — the END-only regression appeared with this change, so the delta
        // list is the suspect pool. Grouped name:old→new with a state count, one line, logged once.
        Map<String, String> deltas = new java.util.TreeMap<>();
        Map<String, Integer> deltaCounts = new HashMap<>();

        int matched = 0;
        int states = 0;
        java.util.List<String> misses = new java.util.ArrayList<>();
        RegistryNamespaced<ResourceLocation, Block> reg = Block.REGISTRY;
        for (Block b : reg) {
            ResourceLocation rl = reg.getNameForObject(b);
            if (rl == null || !"minecraft".equals(rl.getNamespace())) {
                continue; // modded blocks: the pack's modded entries are for other MC versions
            }
            String base = rl.getPath();
            boolean any = false;
            for (IBlockState state : b.getBlockState().getValidStates()) {
                states++;
                int meta;
                try {
                    meta = b.getMetaFromState(state);
                } catch (Throwable t) {
                    meta = 0; // some blocks throw on unusual states; fall back to the meta-less mapping
                }
                String name = FLATTENED.get(base + ":" + meta);
                if (name == null) {
                    name = FLATTENED.get(base);
                }
                if (name == null) {
                    name = base; // unchanged across the flattening (obsidian, bedrock, glowstone, ...)
                }
                BlockProperties.Entry best = null;
                java.util.List<BlockProperties.Entry> cands = byName.get(name);
                if (cands != null) {
                    int bestQuals = -1;
                    for (BlockProperties.Entry cand : cands) {
                        if (!stateMatches(base, state, cand.props)) {
                            continue;
                        }
                        if (cand.props.size() >= bestQuals) { // most-specific wins; later-wins on ties (pack semantics)
                            bestQuals = cand.props.size();
                            best = cand;
                        }
                    }
                }
                if (best != null && best.id != 0) {
                    m.byState.put(state, best.id);
                    matched++;
                    any = true;
                }
                // Delta vs the old fold-down (declaredByName IS the old last-wins map).
                Integer oldId = m.declaredByName.get(name);
                int newId = best == null ? 0 : best.id;
                int old = oldId == null ? 0 : oldId;
                if (old != newId) {
                    String key = base + ":" + old + "->" + newId;
                    deltas.put(key, key);
                    deltaCounts.merge(key, 1, Integer::sum);
                }
            }
            if (!any && misses.size() < 40) {
                misses.add(base);
            }
        }
        SodiumClientMod.logger().info("[Iris block.properties] mapped " + matched + " / " + states
                + " block states to pack ids (" + String.format("%.1f", 100.0 * matched / Math.max(1, states)) + "%)");
        if (!misses.isEmpty()) {
            SodiumClientMod.logger().info("[Iris block.properties] blocks with NO id (sample): "
                    + String.join(", ", misses));
        }
        // Spot-check the ids a few blocks whose look DEPENDS on the id actually receive. The aggregate "98.7% mapped"
        // says nothing about whether a specific block got the RIGHT number, and the pack's effects are keyed on exact
        // values: terrainIPBR.glsl gates the ore glow on `mat < 10276` etc., so iron_ore MUST be exactly 10272.
        StringBuilder probe = new StringBuilder("[Iris block.properties] id probe:");
        String[][] expect = {
                {"iron_ore", "10272"}, {"gold_ore", "10300"}, {"diamond_ore", "10320"},
                {"emerald_ore", "10340"}, {"lapis_ore", "10356"},
                {"redstone_ore", "?"}, {"lit_redstone_ore", "10616"},
                {"coal_ore", "?"}, {"quartz_ore", "?"}, {"glowstone", "?"}, {"sea_lantern", "?"},
        };
        for (String[] e : expect) {
            net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromName("minecraft:" + e[0]);
            int got = b == null ? -1 : m.idFor(b.getDefaultState());
            probe.append(' ').append(e[0]).append('=').append(got);
            if (!"?".equals(e[1]) && got != Integer.parseInt(e[1])) {
                probe.append("(EXPECTED ").append(e[1]).append(')');
            }
        }
        if (!deltas.isEmpty()) {
            StringBuilder d = new StringBuilder("[Iris block.properties] ids changed by property matching (state counts): ");
            for (String key : deltas.keySet()) {
                d.append(key).append("×").append(deltaCounts.get(key)).append(' ');
            }
            SodiumClientMod.logger().info(d.toString());
        }
        // Property-qualified spot-check: the end_portal_frame family splits on `eye` (10554 inactive / 10556 active);
        // the old qualifier fold-down gave EVERY state 10556 — whose Active material strobes held items (see build).
        net.minecraft.block.Block epf = net.minecraft.block.Block.getBlockFromName("minecraft:end_portal_frame");
        if (epf != null) {
            int idNoEye = m.idFor(epf.getStateFromMeta(0));  // eye=false
            int idEye = m.idFor(epf.getStateFromMeta(4));    // eye=true
            SodiumClientMod.logger().info("[Iris block.properties] end_portal_frame eye=false=" + idNoEye
                    + (idNoEye == 10554 ? "" : " (EXPECTED 10554)") + " eye=true=" + idEye
                    + (idEye == 10556 ? "" : " (EXPECTED 10556)"));
        }
        SodiumClientMod.logger().info(probe.toString());
        return m;
    }
}
