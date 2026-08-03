package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.client.entity.ProceduralGeometryCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LATE mixin (Betweenlands present only — see YumeliumLateMixins): second member of the {@code renderedFrame}
 * guard family, found 2026-08-04. Same disease as {@link MixinRenderSludgeMenace}, different renderer.
 *
 * <p>{@code RenderDecayPitTarget} (the decay pit's hanging target machinery — plug, target, cog shields, and the
 * additive {@code chain_beam.png} ribbons) guards BOTH of its draw paths with once-per-frame stamps against
 * {@code RenderUtils.getFrameCounter()}: {@code doRender} (body, shields, the four SHORT beam segments) on
 * {@code EntityDecayPitTarget.renderedFrame}, and {@code renderMultipass} (the four LONG beam segments, called from
 * vanilla RenderGlobal's multipass block) on {@code renderedFrameMultipass}. A jar-wide constant-pool grep shows
 * these two renderers (this and RenderSludgeMenace) are the ONLY members of the guard family.</p>
 *
 * <p>With shaders on, the SHADOW caster pass runs first and its {@code renderEntityStatic} consumes the
 * {@code doRender} guard — the camera pass then draws no machinery body at all. {@code renderMultipass} is never
 * called by the shadow pass, so its guard survives and the LONG beams still draw: the result is a disembodied,
 * fullbright, additively-blended thin glowing line floating in the pit — the "light that survives light shafts OFF"
 * report (2026-08-03). It survived every light-shaft/colored-lighting bisect because it is not lighting at all;
 * it is entity geometry whose anchoring machinery went missing.</p>
 *
 * <p>Fix: both guards read the pass-salted stamp ({@link ProceduralGeometryCache#passSaltedFrame}) — camera and
 * shadow passes each get their one deduped draw, shaders off is bit-identical vanilla. Salting {@code
 * renderMultipass} too is deliberate: today only the camera pass calls it (both Forge render passes share one salt,
 * so the vanilla once-per-frame dedup across pass 0/1 is preserved exactly), and if a future pipeline change ever
 * routes multipass through the shadow pass the guard will already do the right thing.</p>
 *
 * <p>Target signatures are Betweenlands types + primitives and {@code getFrameCounter} is Betweenlands' own static
 * — nothing needs MCP/SRG remapping, dev == production by construction (the specialized generic overrides keep
 * their source names in the reobf jar; only the synthetic bridges are renamed). {@code require = 1} each: a miss
 * means the mod changed shape and the bug is back — fail loudly.</p>
 */
@Mixin(targets = "thebetweenlands.client.render.entity.RenderDecayPitTarget")
public class MixinRenderDecayPitTarget {
    @Redirect(method = "doRender(Lthebetweenlands/common/entity/EntityDecayPitTarget;DDDFF)V",
            at = @At(value = "INVOKE", target = "Lthebetweenlands/util/RenderUtils;getFrameCounter()I"),
            require = 1)
    private int yumelium$passSaltedFrameCounter() {
        return ProceduralGeometryCache.passSaltedFrame();
    }

    @Redirect(method = "renderMultipass(Lthebetweenlands/common/entity/EntityDecayPitTarget;DDDFF)V",
            at = @At(value = "INVOKE", target = "Lthebetweenlands/util/RenderUtils;getFrameCounter()I"),
            require = 1)
    private int yumelium$passSaltedFrameCounterMultipass() {
        return ProceduralGeometryCache.passSaltedFrame();
    }
}
