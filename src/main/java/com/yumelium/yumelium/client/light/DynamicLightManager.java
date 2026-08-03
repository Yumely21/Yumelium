package com.yumelium.yumelium.client.light;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/**
 * Tracks light-emitting entities and projects their luminance into a per-block-position map, which the chunk mesher
 * blends with the vanilla block light (see {@code LightDataAccess}). Rebuilt each client tick; chunk sections whose
 * dynamic light changed are scheduled for a re-mesh so Sodium's baked lighting stays in sync.
 *
 * <p>The map is published through a {@code volatile} reference and only ever replaced (never mutated in place), so the
 * chunk-build worker threads can read a consistent snapshot without locking.</p>
 */
public final class DynamicLightManager {
    public static final DynamicLightManager INSTANCE = new DynamicLightManager();

    private volatile Long2IntMap lightmap = Long2IntMaps.EMPTY_MAP;
    private int tickCounter;

    private DynamicLightManager() {
    }

    /** @return true while Yumelium's shader pipeline is rendering, so the pack owns handheld/entity lighting. */
    private static boolean isShaderPipelineActive() {
        try {
            com.yumelium.yumelium.shaders.pipeline.IrisPipeline p = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance();
            return p != null && p.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Drops any baked dynamic light, re-meshing the sections that had some. Safe to call when already empty. */
    private void clearMap() {
        if (!this.lightmap.isEmpty()) {
            Long2IntMap empty = Long2IntMaps.EMPTY_MAP;
            this.scheduleChangedSections(this.lightmap, empty);
            this.lightmap = empty;
        }
    }

    /** Fast, thread-safe read used during meshing. Returns 0 when there is no dynamic light at the position. */
    public int getLuminance(int x, int y, int z) {
        Long2IntMap map = this.lightmap;
        if (map.isEmpty()) {
            return 0;
        }
        return map.get(pack(x, y, z));
    }

    /** Called once per client tick on the main thread. */
    public void update(Minecraft mc) {
        SodiumGameOptions.DynamicLightsMode mode = SodiumClientMod.options().yumeliumPlus.dynamicLights;

        // A shader pack does its own handheld/entity lighting (Complementary: heldBlockLightValue + heldItemId → a
        // COLOURED light that follows the player). Ours cannot compete: it bakes a single luminance number into the chunk
        // mesh, so the light is uncoloured AND costs a re-mesh of every section it touches. Running both would double the
        // light and keep paying that meshing cost for a worse result, so BY DEFAULT the pack wins whenever the pipeline
        // is on — unless the player opts in (dynamicLightsWithShaders), e.g. for packs without their own handheld light.
        if (isShaderPipelineActive() && !SodiumClientMod.options().yumeliumPlus.dynamicLightsWithShaders) {
            clearMap();
            return;
        }

        if (mc.world == null) {
            // World gone (quit/dimension change): just drop the map — there is nothing left to re-mesh, and the
            // renderer's section manager may already be destroyed (scheduling here NPE'd the world unload).
            this.lightmap = Long2IntMaps.EMPTY_MAP;
            return;
        }

        if (mode == SodiumGameOptions.DynamicLightsMode.OFF || mc.player == null) {
            if (!this.lightmap.isEmpty()) {
                Long2IntMap empty = Long2IntMaps.EMPTY_MAP;
                this.scheduleChangedSections(this.lightmap, empty);
                this.lightmap = empty;
            }
            return;
        }

        // FAST refreshes less often to cut down on chunk rebuilds while moving.
        if (mode == SodiumGameOptions.DynamicLightsMode.FAST) {
            this.tickCounter++;
            if ((this.tickCounter & 3) != 0) {
                return;
            }
        }

        Long2IntOpenHashMap next = new Long2IntOpenHashMap();
        next.defaultReturnValue(0);

        for (Entity entity : mc.world.loadedEntityList) {
            int luminance = luminanceOf(entity);
            if (luminance > 1) {
                spread(next, entity, luminance);
            }
        }

        this.scheduleChangedSections(this.lightmap, next);
        this.lightmap = next;
    }

    private void spread(Long2IntOpenHashMap map, Entity entity, int luminance) {
        int sx = (int) Math.floor(entity.posX);
        int sy = (int) Math.floor(entity.posY + entity.getEyeHeight());
        int sz = (int) Math.floor(entity.posZ);

        int r = luminance - 1; // light = luminance - dist; positive only for dist < luminance

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int dist = (int) (Math.sqrt((double) (dx * dx + dy * dy + dz * dz)) + 0.5D);
                    int light = luminance - dist;
                    if (light <= 0) {
                        continue;
                    }
                    long key = pack(sx + dx, sy + dy, sz + dz);
                    if (light > map.get(key)) {
                        map.put(key, light);
                    }
                }
            }
        }
    }

    private void scheduleChangedSections(Long2IntMap oldMap, Long2IntMap newMap) {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.getInstanceNullable();
        if (renderer == null) {
            return;
        }

        LongOpenHashSet dirtySections = new LongOpenHashSet();
        collectDirty(oldMap, newMap, dirtySections);
        collectDirty(newMap, oldMap, dirtySections);

        LongIterator it = dirtySections.iterator();
        while (it.hasNext()) {
            long s = it.nextLong();
            renderer.scheduleRebuildForChunk(unpackX(s), unpackY(s), unpackZ(s), false);
        }
    }

    private static void collectDirty(Long2IntMap from, Long2IntMap other, LongOpenHashSet dirtySections) {
        for (Long2IntMap.Entry entry : Long2IntMaps.fastIterable(from)) {
            long key = entry.getLongKey();
            if (other.get(key) != entry.getIntValue()) {
                dirtySections.add(pack(unpackX(key) >> 4, unpackY(key) >> 4, unpackZ(key) >> 4));
            }
        }
    }

    private static int luminanceOf(Entity entity) {
        int luminance = 0;

        if (entity.isBurning()) {
            luminance = 15;
        }

        if (entity instanceof EntityItem) {
            luminance = Math.max(luminance, itemLuminance(((EntityItem) entity).getItem()));
        } else if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            luminance = Math.max(luminance, itemLuminance(living.getHeldItemMainhand()));
            luminance = Math.max(luminance, itemLuminance(living.getHeldItemOffhand()));
        }

        if (entity instanceof EntityBlaze) {
            luminance = Math.max(luminance, 10);
        }

        return luminance;
    }

    private static int itemLuminance(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        if (item == Items.LAVA_BUCKET) {
            return 15;
        }
        if (item instanceof ItemBlock) {
            Block block = ((ItemBlock) item).getBlock();
            try {
                return block.getLightValue(block.getDefaultState());
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }

    // 26-bit signed X, 12-bit unsigned Y, 26-bit signed Z packed into a long (internal use only).
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static int unpackX(long p) {
        return (int) (p >> 38);
    }

    private static int unpackY(long p) {
        return (int) (p & 0xFFF);
    }

    private static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }
}
