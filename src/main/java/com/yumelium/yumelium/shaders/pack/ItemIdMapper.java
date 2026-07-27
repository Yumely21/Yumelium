package com.yumelium.yumelium.shaders.pack;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.RegistryNamespaced;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the shader pack's {@code item.properties} ids for the items this 1.12.2 instance actually has, so the pack's
 * {@code heldItemId} / {@code heldItemId2} uniforms carry real values.
 *
 * <p>Complementary keys its HANDHELD LIGHT off these: {@code GetSpecialBlocklightColor(heldItemId - 44000)} turns the id
 * into the light's COLOUR (torch orange, sea lantern cyan, …), and {@code heldItemId == 45032} is the lava-bucket special
 * case. Without the id the pack still lights, but with the generic blocklight colour — which is exactly the "the light is
 * not coloured" symptom. The ids that matter for light are the 44xxx range (39 entries; only a handful — torch, end_rod,
 * beacon, glowstone, jack_o_lantern, sea_lantern, redstone_torch, magma_block, … — exist in 1.12.2 at all).</p>
 *
 * <p>Only the plain name is matched. item.properties speaks 1.13+ names, and unlike blocks the light-bearing item names
 * are almost all unchanged by the flattening ({@code sea_lantern}, {@code torch}, {@code glowstone}); the ones that did
 * change mostly name items 1.12.2 does not have. The resolve logs what matched, so the gap is measured rather than
 * assumed — see {@link BlockIdMapper}, which has the same contract for blocks.</p>
 */
public final class ItemIdMapper {
    /** 1.12.2 registry name → pack id. Metadata is not consulted: no 44xxx light item is metadata-split in 1.12.2. */
    private final Map<String, Integer> byName = new HashMap<>();

    private ItemIdMapper() {
    }

    /** @return the pack's item id for {@code stack}, or 0 when it has none (the pack treats 0 as "nothing special"). */
    public int idFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ResourceLocation rl = stack.getItem().getRegistryName();
        if (rl == null || !"minecraft".equals(rl.getNamespace())) {
            return 0;
        }
        return this.byName.getOrDefault(rl.getPath(), 0);
    }

    /**
     * @return the block light level {@code stack} would emit if placed (0..15), or 0 for a non-block item.
     * This is what the pack's {@code heldBlockLightValue} means: OptiFine/Iris feed it the held block's own light value,
     * and the pack fades it by distance in {@code GetHeldLighting}. The lava bucket is not a block and is handled by the
     * pack itself via {@code heldItemId == 45032}.
     */
    public static int heldLightValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        if (!(item instanceof ItemBlock)) {
            return 0;
        }
        Block block = ((ItemBlock) item).getBlock();
        try {
            return block.getDefaultState().getLightValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Builds the map from a parsed {@code item.properties}, keeping only ids that resolve to an item this instance has.
     */
    public static ItemIdMapper build(BlockProperties props) {
        ItemIdMapper m = new ItemIdMapper();

        Map<String, Integer> declared = new HashMap<>();
        for (BlockProperties.Entry e : props.entries()) {
            declared.put(e.name, e.id); // last wins, matching the pack's own semantics
        }

        RegistryNamespaced<ResourceLocation, Item> reg = Item.REGISTRY;
        int matched = 0;
        for (Item item : reg) {
            ResourceLocation rl = reg.getNameForObject(item);
            if (rl == null || !"minecraft".equals(rl.getNamespace())) {
                continue;
            }
            Integer id = declared.get(rl.getPath());
            if (id != null) {
                m.byName.put(rl.getPath(), id);
                matched++;
            }
        }

        // Report the LIGHT ids specifically — those are the ones the handheld light depends on, and a silent miss there
        // is exactly the "held sea lantern emits nothing" bug this exists to fix.
        StringBuilder lights = new StringBuilder();
        int lightCount = 0;
        for (Map.Entry<String, Integer> e : m.byName.entrySet()) {
            if (e.getValue() >= 44000 && e.getValue() < 45000) {
                lights.append(' ').append(e.getKey()).append('=').append(e.getValue());
                lightCount++;
            }
        }
        SodiumClientMod.logger().info("[Iris item.properties] mapped " + matched + " / " + declared.size()
                + " declared names to items on this instance; " + lightCount + " are light sources (44xxx):" + lights);
        return m;
    }
}
