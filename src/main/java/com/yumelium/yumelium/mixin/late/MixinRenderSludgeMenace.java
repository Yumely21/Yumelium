package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.client.entity.ProceduralGeometryCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LATE mixin (Betweenlands present only — see YumeliumLateMixins): fixes the Sludge Menace being INVISIBLE whenever
 * the shader pipeline is on (2026-08-03).
 *
 * <p>{@code RenderSludgeMenace.doRender} carries a once-per-frame guard: {@code if (boss.renderedFrame !=
 * RenderUtils.getFrameCounter()) { boss.renderedFrame = counter; super.doRender(...); }} — the boss's many
 * {@code EntityMultipartDummy} parts all delegate their render back to the parent ({@code RenderMultipartDummy
 * .delegateRender} → {@code renderEntityStatic}/{@code renderMultipass} on the parent), and the guard is what keeps
 * the whole body from drawing once per dummy. Vanilla renders one pass per frame, so "once per frame" and "once per
 * pass" are the same thing there.</p>
 *
 * <p>Our pipeline breaks that equivalence: with shaders on, the SHADOW caster pass runs FIRST (it is triggered from
 * {@code renderBlockLayer(SOLID)}, before the entity loop) and its {@code renderEntityStatic(boss)} consumes the one
 * draw the guard allows. Every camera-pass attempt — the entity loop, each dummy's delegation, the multipass block —
 * then compares equal and returns without drawing: the boss casts a real volumetric shadow (the "phantom light
 * streaks") while its visible body never renders.</p>
 *
 * <p>The fix redirects the guard's frame-counter read to a PASS-SALTED stamp ({@link
 * ProceduralGeometryCache#passSaltedFrame}): the camera pass sees the plain frame counter, the shadow pass a disjoint
 * negated value. Within one pass repeated delegations still compare equal (dedup preserved — the shadow pass, too,
 * draws the body only once no matter how many dummies delegate); across passes the values differ, so each pass gets
 * its one draw. Shaders off → {@code isShadowPass()} is never true → plain counter → exact vanilla behaviour.</p>
 *
 * <p>The target signature contains only Betweenlands types and primitives, and {@code RenderUtils.getFrameCounter}
 * is Betweenlands' own static — nothing here needs MCP/SRG remapping, so dev and production match by construction.
 * {@code require = 1}: a miss means the mod changed shape and the invisibility bug is back — fail loudly.</p>
 */
@Mixin(targets = "thebetweenlands.client.render.entity.RenderSludgeMenace")
public class MixinRenderSludgeMenace {
    @Redirect(method = "doRender(Lthebetweenlands/common/entity/mobs/EntityWallLivingRoot;DDDFF)V",
            at = @At(value = "INVOKE", target = "Lthebetweenlands/util/RenderUtils;getFrameCounter()I"),
            require = 1)
    private int yumelium$passSaltedFrameCounter() {
        return ProceduralGeometryCache.passSaltedFrame();
    }
}
