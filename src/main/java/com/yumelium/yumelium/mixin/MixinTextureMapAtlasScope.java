package com.yumelium.yumelium.mixin;

import com.yumelium.yumelium.YumeliumMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureMapPopulator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets {@code TextureMap.loadSprites} so MixinTextureUtilMipmap's alpha floor knows when the BLOCK atlas
 * ({@code Minecraft.textureMapBlocks}) is loading.
 *
 * <p>{@code loadSprites} — NOT {@code loadTextureAtlas} — on purpose: VintageFix's {@code preloadTextures} injects
 * at loadTextureAtlas HEAD and runs sprite loads on its worker pool from there; same-point callback order between
 * two default-priority mixins is unguaranteed, but loadSprites is loadTextureAtlas's CALLER, so this flag is set
 * strictly before VintageFix's preload starts, and VF blocks on its loadedCount inside that callback, so every
 * worker-thread load completes inside this bracket. The identity check is valid because {@code Minecraft.init}
 * assigns {@code textureMapBlocks} before {@code loadTickableTexture} triggers the first load; resource reloads
 * reuse the instance.</p>
 */
@Mixin(TextureMap.class)
public class MixinTextureMapAtlasScope {

    @Inject(method = "loadSprites(Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/client/renderer/texture/ITextureMapPopulator;)V",
            at = @At("HEAD"), require = 1)
    private void yumelium$enterAtlasLoad(IResourceManager resourceManager, ITextureMapPopulator populator, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        // Plain assignment (not |=): a non-block-atlas load self-heals any flag left set by a crashed load.
        YumeliumMod.BLOCK_ATLAS_LOADING = mc != null && (Object) this == mc.getTextureMapBlocks();
    }

    @Inject(method = "loadSprites(Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/client/renderer/texture/ITextureMapPopulator;)V",
            at = @At("RETURN"), require = 1)
    private void yumelium$exitAtlasLoad(IResourceManager resourceManager, ITextureMapPopulator populator, CallbackInfo ci) {
        YumeliumMod.BLOCK_ATLAS_LOADING = false;
    }
}
