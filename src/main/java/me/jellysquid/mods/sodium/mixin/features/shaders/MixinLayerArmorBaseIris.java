package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: swaps the pack's dedicated {@code gbuffers_armor_glint} in around the ARMOR enchantment-glint
 * overlay (enchanted armor worn by entities/players) — the counterpart of the item-glint hook in
 * {@link MixinRenderItemIris}. The pipeline no-ops outside a live gbuffers pass and restores the enclosing pass's
 * program + attachments afterwards.
 */
@Mixin(LayerArmorBase.class)
public class MixinLayerArmorBaseIris {
    @Inject(method = "renderEnchantedGlint", at = @At("HEAD"))
    private static void yumelium$beginGlint(CallbackInfo ci) {
        IrisPipeline.instance().beginArmorGlint();
    }

    @Inject(method = "renderEnchantedGlint", at = @At("RETURN"))
    private static void yumelium$endGlint(CallbackInfo ci) {
        IrisPipeline.instance().endArmorGlint();
    }
}
