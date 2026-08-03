package me.jellysquid.mods.sodium.mixin.features.render;

import com.yumelium.yumelium.client.light.DynamicLightManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Yumelium Plus dynamic lights, RENDER-QUERY half: raises {@code World.getCombinedLight}'s block-light component to
 * the dynamic light level at the queried position.
 *
 * <p>This is THE central packed-lightmap query of 1.12.2's render path, and hooking it here is what lights everything
 * that is not a chunk mesh: entities ({@code Entity.getBrightnessForRender} delegates here — this replaced the earlier
 * entity-only mixin), TESR block entities ({@code TileEntityRendererDispatcher.render} sets the lightmap from it — the
 * Betweenlands dungeons are full of them), particles ({@code Particle.getBrightnessForRender}), the first-person held
 * item ({@code ItemRenderer} queries the WORLD directly, not the player entity — the one consumer the entity mixin
 * missed), and any modded renderer that asks the world itself. Chunk meshes are NOT affected: the mesher reads light
 * through {@code WorldSlice}'s own copy of this method and gets its dynamic light from the {@code LightDataAccess}
 * merge instead.</p>
 *
 * <p>The packed format is vanilla's {@code (skyLight << 20) | (blockLight << 4)}: the sky half is preserved, the
 * block half max-combined with the dynamic level — never lowered. Gated on {@code isRemote} so integrated-server
 * logic can never see modified light. Cost per call is one volatile read plus, only while any dynamic light exists,
 * one hash lookup ({@link DynamicLightManager#getLuminance} returns immediately on the empty map — also the state
 * whenever the feature is OFF or yielding to a shader pack).</p>
 */
@Mixin(World.class)
public abstract class MixinWorldDynamicLights {
    @Inject(method = "getCombinedLight", at = @At("RETURN"), cancellable = true, require = 1)
    private void yumelium$applyDynamicLight(BlockPos pos, int lightValue, CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        if (!self.isRemote) {
            return; // client-side visual lighting only — never touch server logic
        }
        int dyn = DynamicLightManager.INSTANCE.getLuminance(pos.getX(), pos.getY(), pos.getZ());
        if (dyn <= 0) {
            return;
        }
        int packed = cir.getReturnValueI();
        int blockBits = packed & 0xFFFF;      // blockLight << 4, i.e. 0..240
        int dynBits = dyn << 4;
        if (dynBits > blockBits) {
            cir.setReturnValue((packed & 0xFFFF0000) | dynBits);
        }
    }
}
