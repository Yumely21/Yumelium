package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: routes the block-breaking crack overlay through the pack's {@code gbuffers_damagedblock} program
 * (with its {@code alphaTest} override) instead of fixed-function — the same begin/end bracket pattern as the
 * particles and weather hooks. No-ops when shaders are off or the pack ships no damagedblock program.
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobalDamagedBlockIris {
    @Inject(method = "drawBlockDamageTexture", at = @At("HEAD"))
    private void yumelium$damageBegin(Tessellator tessellatorIn, BufferBuilder bufferBuilderIn, Entity entityIn,
                                      float partialTicks, CallbackInfo ci) {
        IrisPipeline.instance().beginDamagedBlock();
    }

    @Inject(method = "drawBlockDamageTexture", at = @At("RETURN"))
    private void yumelium$damageEnd(Tessellator tessellatorIn, BufferBuilder bufferBuilderIn, Entity entityIn,
                                    float partialTicks, CallbackInfo ci) {
        IrisPipeline.instance().endDamagedBlock();
    }

    /** The block selection outline (untextured GL_LINES) → the pack's {@code gbuffers_basic}. Standalone: no gbuffers
     * pass is live at this point, so endBasic() drops back to fixed-function + colortex0-only. */
    @Inject(method = "drawSelectionBox", at = @At("HEAD"))
    private void yumelium$selectionBegin(CallbackInfo ci) {
        IrisPipeline.instance().beginBasic();
    }

    @Inject(method = "drawSelectionBox", at = @At("RETURN"))
    private void yumelium$selectionEnd(CallbackInfo ci) {
        IrisPipeline.instance().endBasic();
    }
}
