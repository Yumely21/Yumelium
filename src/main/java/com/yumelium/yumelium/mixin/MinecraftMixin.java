package com.yumelium.yumelium.mixin;

import com.yumelium.yumelium.YumeliumMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smoke-test mixin proving the mixin chain (compile -> refmap -> runtime apply) works.
 * Refmap is handled by Unimined automatically. Remove once real Sodium mixins are ported.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "createDisplay", at = @At("HEAD"))
    public void yumelium$onCreateDisplay(CallbackInfo ci) {
        YumeliumMod.LOGGER.info("Yumelium mixin applied successfully!");
    }
}
