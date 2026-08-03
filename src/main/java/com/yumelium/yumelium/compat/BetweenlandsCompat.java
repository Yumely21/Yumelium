package com.yumelium.yumelium.compat;

import com.yumelium.yumelium.YumeliumMod;
import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;

/**
 * The Betweenlands compat — yield its shader system to ours, dynamically.
 *
 * <p>In its dimension the mod runs an FBO post-processing chain (WorldShader: geometry buffer, god rays, ground
 * fog, own tonemapper) that blits over the frame — on top of our composite output, which reads as "shaders turned
 * off" the moment you enter. Every entry point funnels through {@code ShaderHelper.canUseShaders()}, which
 * re-reads {@code BetweenlandsConfig.RENDERING.useShader} on every call (verified in 3.9.6 bytecode) — so keeping
 * that field {@code false} while OUR pipeline is enabled switches the mod to its supported non-shader fallback,
 * and restoring the user's own value when our shaders go off (K) brings The Betweenlands' effects back
 * automatically.</p>
 *
 * <p>WHY REFLECTION, NOT A MIXIN (2026-07-28): a {@code @Pseudo} mixin on ShaderHelper crashed the game — our
 * mixin configs are processed at coremod time, BEFORE mod jars are on the classpath, and Cleanroom Foundation's
 * ActualClassLoader negative-caches the failed target probe, so BTL's later legitimate load of ShaderHelper threw
 * ClassNotFoundException from the cache. Early mixin configs must never target other MODS' classes on Foundation.
 * This ticker re-asserts the field each client tick instead, which also survives BTL's own config re-syncs.</p>
 *
 * <p>Registered from ClientProxy only when the mod is loaded; the reflection handles resolve once and the
 * per-tick cost is a boolean field read.</p>
 */
public final class BetweenlandsCompat {
    private Object rendering;      // BetweenlandsConfig.RENDERING instance
    private Field useShader;       // Rendering.useShader
    private boolean broken;        // a future BTL fork changed the layout — stand down permanently
    private Boolean savedUseShader; // the user's own value while we force false; null = not forcing

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || this.broken) {
            return;
        }
        try {
            if (this.useShader == null && !resolve()) {
                return;
            }
            boolean current = this.useShader.getBoolean(this.rendering);
            if (IrisPipeline.instance().isEnabled()) {
                if (current) { // re-asserted every tick, so BTL's own config re-sync can't undo the yield
                    this.savedUseShader = true;
                    this.useShader.setBoolean(this.rendering, false);
                    YumeliumMod.LOGGER.info("[compat] Betweenlands WorldShader yielded (Yumelium shaders ON)");
                }
            } else if (this.savedUseShader != null) {
                this.useShader.setBoolean(this.rendering, this.savedUseShader);
                this.savedUseShader = null;
                YumeliumMod.LOGGER.info("[compat] Betweenlands WorldShader restored (Yumelium shaders OFF)");
            }
        } catch (Throwable t) {
            this.broken = true;
            YumeliumMod.LOGGER.warn("[compat] Betweenlands shader yield disabled", t);
        }
    }

    private boolean resolve() throws Exception {
        Class<?> cfg = Class.forName("thebetweenlands.common.config.BetweenlandsConfig");
        Object renderingInstance = cfg.getField("RENDERING").get(null);
        if (renderingInstance == null) {
            return false; // config not populated yet — try again next tick
        }
        this.useShader = renderingInstance.getClass().getField("useShader");
        this.rendering = renderingInstance;
        return true;
    }

    // --- Sludge Menace dummy-dispatch skip (task #15, 2026-08-04) -------------------------------------------------

    /** Kill switch for {@link #isSludgeMenaceDummy}. /ylbench measured ~0.2 ms/boss/frame of pure dispatch
     * overhead, ~linear to N (camEnt 0.34→3.34 ms, shadowEnt 0.60→3.70 ms across N=1→32). */
    public static final boolean SKIP_SLUDGE_DUMMY_DISPATCH = true;

    private static volatile Class<?> sludgeDummyClass;

    /**
     * True for the Sludge Menace's 17 {@code EntitySludgeMenace$DummyPart} entities — skipped in OUR camera entity
     * loop and shadow caster loop. Safety argument (bytecode-verified 2026-08-04): the PARENT boss keeps
     * {@code renderBoundingBox} = the union of all 17 part AABBs (client side), so it passes every camera
     * visibility gate whenever ANY part is on screen; in the shadow loop the parent sits in the same
     * loadedEntityList under the same distance cull; and the pass-salted {@code renderedFrame} guard already
     * dedups the body draw to once per pass. Each dummy dispatch is therefore pure overhead: our per-entity
     * bracket (x2 with the nested parent re-dispatch), BL delegateRender's {@code new Frustum()} (TWO glGetFloat
     * matrix readbacks), shouldRender, and — in the shadow pass — the full per-caster re-assert bracket.
     * Deliberately NARROW: the generic {@code EntityMultipartDummy} (other BL multipart mobs) is NOT skipped,
     * because only the Sludge Menace's union-BB override is verified. Known edge: at the shadow-radius boundary a
     * boss whose origin is just outside the cull while a part is inside loses its shadow up to ~12 blocks early —
     * negligible at pack shadow distances. Lazy Class cache: one reference compare per entity once resolved.
     */
    public static boolean isSludgeMenaceDummy(net.minecraft.entity.Entity entity) {
        if (!SKIP_SLUDGE_DUMMY_DISPATCH) {
            return false;
        }
        Class<?> c = entity.getClass();
        Class<?> cached = sludgeDummyClass;
        if (cached != null) {
            return c == cached;
        }
        if ("thebetweenlands.common.entity.mobs.EntitySludgeMenace$DummyPart".equals(c.getName())) {
            sludgeDummyClass = c;
            return true;
        }
        return false;
    }
}
