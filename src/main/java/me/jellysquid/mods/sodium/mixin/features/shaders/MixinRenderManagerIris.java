package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: feeds the pack's PER-ENTITY uniforms ({@code entityId}, {@code entityColor}, and the
 * {@code currentRenderedItemId} reset) at the head of every entity render. {@code renderEntityStatic} is what
 * RenderGlobal's entity loop calls once per entity (weather effects included), so the values change per entity while
 * the single {@code gbuffers_entities} bind stays in place. No-ops unless the entity pass is live (shaders on, inside
 * the world entity loop) — GUI entity previews are untouched.
 */
@Mixin(RenderManager.class)
public class MixinRenderManagerIris {
    @Inject(method = "renderEntityStatic(Lnet/minecraft/entity/Entity;FZ)V", at = @At("HEAD"))
    private void yumelium$perEntityUniforms(Entity entityIn, float partialTicks, boolean debug, CallbackInfo ci) {
        IrisPipeline.instance().onEntityRender(entityIn);
        // Current-entity tracking for the procedural geometry cache: renderEntityStatic is the funnel BOTH passes use
        // (the camera loop and the shadow caster loop), so a renderer deep inside doRender — the Betweenlands hull
        // builder reached through a LATE mixin that must not capture the mod's own types — can ask which entity it is
        // rendering without any BL compile dependency. Push/pop (not a single field) because passengers recurse.
        com.yumelium.yumelium.client.entity.ProceduralGeometryCache.pushCurrentEntity(entityIn);
    }

    @Inject(method = "renderEntityStatic(Lnet/minecraft/entity/Entity;FZ)V", at = @At("RETURN"), require = 1)
    private void yumelium$perEntityUniformsEnd(Entity entityIn, float partialTicks, boolean debug, CallbackInfo ci) {
        com.yumelium.yumelium.client.entity.ProceduralGeometryCache.popCurrentEntity();
    }
}
