package com.yumelium.yumelium.shaders.pack;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the shader pack's {@code entity.properties} ids for the {@code entityId} uniform — the per-entity analogue
 * of {@link BlockIdMapper}/{@link ItemIdMapper}. Complementary keys real behaviour off these: {@code entityId == 50076}
 * (boats) takes a special lighting path, {@code entityId != 50017} (current_player) gates the held-light halo, and
 * entityIPBR dispatches per-entity materials. The pack lists BOTH flattened and legacy names
 * ({@code iron_golem villager_golem}, {@code end_crystal ender_crystal}), so 1.12.2 registry names resolve without a
 * translation table.
 *
 * <p>Special names (OptiFine spec): {@code current_player} = the entity the camera follows; {@code player} = any other
 * player. Both live outside the registry ({@code EntityList.getKey} returns null for players), so they are matched by
 * type — as are lightning bolts, which 1.12.2 keeps as registry-less weather effects.</p>
 */
public final class EntityIdMapper {
    private final Map<String, Integer> byName = new HashMap<>();
    private final int playerId;
    private final int currentPlayerId;
    private final int lightningId;

    private EntityIdMapper(Map<String, Integer> declared) {
        this.byName.putAll(declared);
        this.playerId = this.byName.getOrDefault("player", 0);
        this.currentPlayerId = this.byName.getOrDefault("current_player", this.playerId);
        this.lightningId = this.byName.getOrDefault("lightning_bolt", 0);
    }

    /** @return the pack's entity id for {@code entity}, or 0 when it has none (the pack treats 0 as "nothing special"). */
    public int idFor(Entity entity) {
        if (entity == null) {
            return 0;
        }
        if (entity instanceof EntityPlayer) {
            return entity == Minecraft.getMinecraft().getRenderViewEntity() ? this.currentPlayerId : this.playerId;
        }
        if (entity instanceof EntityLightningBolt) {
            return this.lightningId;
        }
        ResourceLocation key = EntityList.getKey(entity);
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return 0;
        }
        return this.byName.getOrDefault(key.getPath(), 0);
    }

    /** Builds the map from a parsed {@code entity.properties}. Unlike items, there is no registry pre-filtering:
     * lookups resolve lazily per rendered entity, and an undeclared name simply misses to 0. */
    public static EntityIdMapper build(BlockProperties props) {
        Map<String, Integer> declared = new HashMap<>();
        for (BlockProperties.Entry e : props.entries()) {
            declared.put(e.name, e.id); // last wins, matching the pack's own semantics
        }
        SodiumClientMod.logger().info("[Iris entity.properties] " + declared.size() + " declared entity names"
                + " (player=" + declared.getOrDefault("player", 0)
                + " current_player=" + declared.getOrDefault("current_player", 0) + ")");
        return new EntityIdMapper(declared);
    }
}
