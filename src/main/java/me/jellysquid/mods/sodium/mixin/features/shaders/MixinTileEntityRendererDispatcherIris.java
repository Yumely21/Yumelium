package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: feeds the pack's {@code blockEntityId} uniform (block.properties id of the TESR's block state) at the
 * head of every block-entity render — Sodium's TESR loop calls {@code render(TileEntity, float, int)} once per tile
 * entity, so the value changes per TESR while the single {@code gbuffers_block} bind stays in place. This is what
 * routes chests/signs/banners/conduits through the pack's blockEntityIPBR material dispatch. No-ops unless the
 * block-entity pass is live.
 */
@Mixin(TileEntityRendererDispatcher.class)
public class MixinTileEntityRendererDispatcherIris {
    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("HEAD"))
    private void yumelium$blockEntityUniforms(TileEntity tileEntityIn, float partialTicks, int destroyStage, CallbackInfo ci) {
        IrisPipeline.instance().onBlockEntityRender(tileEntityIn);
    }
}
