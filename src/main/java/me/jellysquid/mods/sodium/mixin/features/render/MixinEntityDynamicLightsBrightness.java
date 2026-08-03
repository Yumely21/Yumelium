package me.jellysquid.mods.sodium.mixin.features.render;

import com.yumelium.yumelium.client.light.DynamicLightManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Yumelium Plus dynamic lights, ENTITY half: raises {@code getBrightnessForRender}'s block-light component to the
 * dynamic light level at the entity's position.
 *
 * <p>Without this, dynamic lights only existed in the CHUNK MESHES ({@code LightDataAccess} blends
 * {@link DynamicLightManager#getLuminance} into the baked block light) — the terrain around a held torch glowed while
 * every entity model, the player's own skin included, kept the WORLD's real light level and stayed pitch black in the
 * dark. Entity renderers set their lightmap from this method ({@code RenderLivingBase} → {@code
 * OpenGlHelper.setLightmapTextureCoords}), so lifting the block-light bits here lights the models the same way the
 * terrain is lit; the first-person hand takes the player's value through the same call.</p>
 *
 * <p>The packed format is vanilla's {@code (skyLight << 20) | (blockLight << 4)}: the sky half is preserved, the block
 * half is max-combined with the dynamic level — never lowered. Cost per call is one volatile read plus, only while any
 * dynamic light exists, one hash lookup ({@link DynamicLightManager#getLuminance} returns immediately on the empty
 * map, which is also the state whenever the feature is OFF or yielding to a shader pack). Subclasses that override
 * {@code getBrightnessForRender} for fullbright rendering (magma cubes etc.) bypass this on purpose — they are already
 * brighter than anything we would add.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityDynamicLightsBrightness {
    @Inject(method = "getBrightnessForRender", at = @At("RETURN"), cancellable = true, require = 1)
    private void yumelium$applyDynamicLight(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        if (self.world == null || !self.world.isRemote) {
            return; // client-side visual lighting only — never touch server logic
        }
        // Same sample point the manager spreads from: the entity's eye block.
        int dyn = DynamicLightManager.INSTANCE.getLuminance(
                (int) Math.floor(self.posX),
                (int) Math.floor(self.posY + self.getEyeHeight()),
                (int) Math.floor(self.posZ));
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
