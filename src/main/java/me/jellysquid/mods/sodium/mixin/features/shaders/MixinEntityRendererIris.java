package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris/Oculus port — M2. Wraps the whole world-render phase so the deferred pipeline can bind its G-buffer framebuffer
 * before any world geometry is drawn and copy the result back to Minecraft's main framebuffer afterwards. When the
 * pipeline is disabled (default) both hooks are no-ops, so vanilla/Sodium rendering is untouched.
 *
 * <p>{@code renderWorld(float,long)} runs while Minecraft's main framebuffer is bound and covers sky → terrain →
 * entities → particles → weather → hand; injecting at HEAD/RETURN captures that entire scene into the G-buffer.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererIris {
    @Shadow
    protected abstract void renderRainSnow(float partialTicks);

    @Inject(method = "renderWorld(FJ)V", at = @At("HEAD"))
    private void yumelium$beginWorldRender(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisPipeline.instance().beginWorldRender(partialTicks);
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"))
    private void yumelium$endWorldRender(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisPipeline.instance().endWorldRender();
    }

    /**
     * Routes particles through the pack's {@code gbuffers_textured} by binding it around
     * {@code ParticleManager.renderParticles} inside {@code renderWorldPass}. A {@code @Redirect} with try/finally is used
     * (not HEAD/RETURN on renderParticles) because the "All Particles" toggle cancels renderParticles at HEAD, which would
     * skip a RETURN unbind and leak the bound program. No-op when shaders are off.
     */
    @Redirect(
            method = "renderWorldPass(IFJ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleManager;renderParticles(Lnet/minecraft/entity/Entity;F)V")
    )
    private void yumelium$particles(ParticleManager particleManager, Entity entity, float partialTicks) {
        IrisPipeline.instance().beginParticles();
        try {
            particleManager.renderParticles(entity, partialTicks);
        } finally {
            IrisPipeline.instance().endParticles();
        }
    }

    /**
     * Routes falling rain/snow through the pack's {@code gbuffers_weather} by binding it around
     * {@code EntityRenderer.renderRainSnow} inside {@code renderWorldPass}, so the weather is shaded the pack's way (tinted
     * by ambient/sun) instead of vanilla's flat streaks. {@code @Redirect} + try/finally so the program is always unbound.
     * No-op when shaders are off or the pack has no weather program (then rain renders vanilla).
     */
    @Redirect(
            method = "renderWorldPass(IFJ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;renderRainSnow(F)V")
    )
    private void yumelium$weather(EntityRenderer instance, float partialTicks) {
        IrisPipeline.instance().beginWeather();
        try {
            this.renderRainSnow(partialTicks); // instance == this (renderWorldPass calls this.renderRainSnow)
        } finally {
            IrisPipeline.instance().endWeather();
        }
    }

    /**
     * Keeps the opaque scene depth in the pipeline's {@code depthtex0} for the composite fog/atmosphere.
     *
     * <p>Right before the first-person hand, vanilla does {@code GlStateManager.clear(GL_DEPTH_BUFFER_BIT)} so the hand
     * never clips into nearby blocks. With the shader pipeline active, the bound framebuffer is our shared G-buffer, so
     * that clear wipes the terrain + entity depth the composite reads as {@code depthtex0} — leaving only the hand's
     * depth (drawn after the clear). The composite then treats every terrain pixel as maximally distant, applying a
     * heavy uniform grey fog over the whole world. We suppress <b>only</b> that depth-only clear while capturing the
     * world; the hand still draws on top (its near depth passes {@code GL_LEQUAL}), and the composite now gets the real
     * per-pixel scene depth. This redirect targets every {@code clear(int)} in {@code renderWorldPass}, but acts only on
     * the depth-only ({@code == GL_DEPTH_BUFFER_BIT}) call — the world clear at the top uses colour|depth, so it is
     * untouched. Fully vanilla when shaders are off.</p>
     */
    @Redirect(
            method = "renderWorldPass(IFJ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;clear(I)V")
    )
    private void yumelium$keepHandDepth(int mask) {
        if (mask == GL11.GL_DEPTH_BUFFER_BIT && IrisPipeline.instance().isCapturingWorld()) {
            return;
        }
        GlStateManager.clear(mask);
    }
}
