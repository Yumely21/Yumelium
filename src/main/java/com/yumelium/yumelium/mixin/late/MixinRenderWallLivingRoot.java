package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.client.entity.ProceduralGeometryCache;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LATE mixin (Betweenlands present only — see YumeliumLateMixins): caches the Sludge Menace family's procedurally
 * built body hull.
 *
 * <p>{@code RenderWallLivingRoot.renderBodyHull} re-solves the hull contraction for every segment and re-emits the
 * whole tube into the shared Tessellator EVERY time it runs — and with entity shadows on, that is twice per frame.
 * The geometry is camera-independent (it reads only the entity, math helpers and the buffer), so the head inject
 * replays the entity's retained VBO and cancels the body whenever the cache is fresh (same frame — the shadow pass
 * builds, the camera pass replays — or within the distance-scaled update interval), and the draw redirect below
 * captures the vertices into that VBO on the frames that DO build. The entity identity comes from the
 * renderEntityStatic tracking (MixinRenderManagerIris), so this class never references a Betweenlands type — only
 * the target strings name the mod.</p>
 *
 * <p>Both injectors carry {@code require = 1}: the config only queues when the Betweenlands is loaded, so a failure
 * here means the mod changed shape and the cache must be revisited, loudly.</p>
 */
@Mixin(targets = "thebetweenlands.client.render.entity.RenderWallLivingRoot")
public class MixinRenderWallLivingRoot {
    @Inject(method = "renderBodyHull", at = @At("HEAD"), cancellable = true, require = 1)
    private void yumelium$hullReplayOrArm(CallbackInfo ci) {
        ProceduralGeometryCache.diagHullEntered(); // DIAG: proves the renderer is reached at all (debug-gated)
        Entity entity = ProceduralGeometryCache.currentEntity();
        if (entity != null && ProceduralGeometryCache.INSTANCE.replayIfFresh(entity)) {
            ci.cancel();
        }
    }

    @Redirect(method = "renderBodyHull",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"),
            require = 1)
    private void yumelium$hullCaptureDraw(Tessellator tessellator) {
        if (ProceduralGeometryCache.INSTANCE.captureActive()) {
            ProceduralGeometryCache.INSTANCE.captureAndDraw(tessellator.getBuffer());
        } else {
            ProceduralGeometryCache.diagVanillaDraw(tessellator.getBuffer()); // DIAG: count on the uncached path too
            tessellator.draw();
        }
    }
}
