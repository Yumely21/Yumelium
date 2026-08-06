package com.yumelium.yumelium.compat;

import com.yumelium.yumelium.YumeliumMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 3dSkinLayers × Betweenlands decay compat, REBUILT 2026-08-07 from a full bytecode map of both mods (the first
 * port and its three patches are autopsied in CLAUDE.md; every design decision below has a bytecode citation in
 * the scratchpad disassemblies).
 *
 * <p><b>What it does:</b> Betweenlands draws player decay by re-rendering the vanilla model with
 * {@code player_decay.png} at a pulsing alpha ({@code DecayRenderHandler$LayerDecay}), deliberately excluding the
 * six vanilla second-layer wear parts. 3dSkinLayers replaces those wear parts with extruded voxel geometry, so at
 * high decay the body rots while hat/jacket/sleeves stay clean. This compat re-draws the voxel layers with a
 * synthesised decay texture, in the SAME pass, at the SAME alpha.</p>
 *
 * <p><b>THE constraint that killed the first port (never regress this):</b> 3dSkinLayers'
 * {@code CustomizableCube.render} writes PER-VERTEX colour (255,255,255,255) into a POSITION_TEX_COLOR_NORMAL
 * buffer — a colour array OVERRIDES the current {@code glColor}, so a pulse alpha set via
 * {@code GlStateManager.color} modulates NOTHING and the overlay draws fully opaque (the "black shell" bug).
 * The alpha therefore rides in the TEXTURE: the synthesised 64×64 template is re-uploaded with its alpha channel
 * scaled by the quantised pulse ({@link #bindAlphaScaled}) — one tiny glTexSubImage per alpha step, deduped.</p>
 *
 * <p><b>Hook architecture:</b> late mixins into BETWEENLANDS classes only (zero mixins into 3dSkinLayers —
 * everything there goes through resolve-once reflection with a broken-latch): {@code LayerDecay.doRenderLayer}
 * HEAD (clear) + colour redirect (capture the exact alpha, no formula duplication) + TAIL (consume-and-draw,
 * same player, same pass — 3dSkinLayers' own draw happened earlier in the same {@code doRender} at renderModel
 * TAIL, so the voxel parts are guaranteed built and posed). First person:
 * {@code renderArmFirstPersonWithDecay} colour redirect + TAIL, re-drawing the extruded sleeve voxel part the
 * way 3dSkinLayers' own {@code renderFirstPersonArm} does. Re-invoking
 * {@code BodyLayerFeatureRenderer.renderLayers} / {@code HeadLayerFeatureRenderer.renderCustomHelmet} is
 * bytecode-proven safe: pure draw loops, no lazy geometry rebuild reachable, deterministic part.x/y rewrites,
 * matrices fully push/popped, their float parameters dead.</p>
 *
 * <p><b>Testing note:</b> with a DEFAULT skin 3dSkinLayers builds no geometry at all
 * ({@code SkinUtil.hasCustomSkin} false) — the stock dev "Developer" account can only exercise this feature with
 * the Yumelium Plus "Yumely21's Skin" toggle ON (any non-default skin location works).</p>
 */
public final class SkinLayersDecayCompat {

    private static final String MOD_ID = "skinlayers3d";

    private static final ResourceLocation DECAY_SKIN =
            new ResourceLocation("thebetweenlands", "textures/entity/player_decay.png");
    private static final ResourceLocation LAYERED_DECAY_SKIN =
            new ResourceLocation("yumelium", "decay_skin_layers");

    /** dstX, dstY, srcX, srcY, w, h — every second-layer slot filled from its base part ({@code player_decay.png}
     * ships all six overlay regions EMPTY). Verified against SkinUtil.setup3dLayers' exact wrapBox windows. */
    private static final int[][] OVERLAY_REGIONS = {
            {32, 0, 0, 0, 32, 16},      // hat          <- head
            {16, 32, 16, 16, 24, 16},   // jacket       <- body
            {40, 32, 40, 16, 16, 16},   // right sleeve <- right arm
            {0, 32, 0, 16, 16, 16},     // right pants  <- right leg
            {48, 48, 32, 48, 16, 16},   // left sleeve  <- left arm
            {0, 48, 16, 48, 16, 16},    // left pants   <- left leg
    };

    /** 3dSkinLayers' own arm x-offsets (renderFirstPersonArm / renderLayers ARMS shapes). */
    private static final float ARM_X_CLASSIC = 15.968F;
    private static final float ARM_X_SLIM = 7.984F;

    private static final Set<String> diagnosed = new HashSet<>();

    private static boolean broken;
    private static boolean resolved;
    private static float decayAlpha = -1.0F;

    // Synthesised texture: template pixels (full alpha) + one dynamic texture re-uploaded per alpha step.
    private static int[] template;
    private static DynamicTexture dynamicTexture;
    private static int lastUploadedQ = -1;

    // 3dSkinLayers reflection surface (resolved once; names verified against 1.2.0 bytecode).
    private static Class<?> accessorClass;      // accessor.PlayerEntityModelAccessor (on RenderPlayer)
    private static Method getBodyLayer;         // accessor: BodyLayerFeatureRenderer
    private static Method getHeadLayer;         // accessor: HeadLayerFeatureRenderer
    private static Method hasThinArms;          // accessor: boolean
    private static Method getSkinLayers;        // PlayerSettings: CustomizableModelPart[]
    private static Method getHeadLayers;        // PlayerSettings: CustomizableModelPart
    private static Method renderLayers;         // BodyLayerFeatureRenderer.renderLayers(player, parts, float)
    private static Method renderCustomHelmet;   // HeadLayerFeatureRenderer.renderCustomHelmet(settings, player, float)
    private static Method partRender;           // CustomizableModelPart.render(boolean)
    private static Field partX;                 // CustomizableModelPart.x
    private static Float baseVoxelSize;         // Config.baseVoxelSize (first-person sleeve scale), null = 1.15 default

    private SkinLayersDecayCompat() {
    }

    // --- alpha lifecycle (captured from Betweenlands' own colour calls — its pulse formula is never duplicated) --

    /** LayerDecay HEAD: one clear per rendered player, BEFORE its decay pass decides whether to draw. */
    public static void clearDecayAlpha() {
        decayAlpha = -1.0F;
    }

    /** Every {@code GlStateManager.color} the decay passes issue: the pre-draw call carries the pulse alpha
     * (captured), the opaque resets are ignored. BL's alpha can go NEGATIVE at low decay levels — clamped at
     * consumption. */
    public static void captureDecayAlpha(float alpha) {
        if (alpha < 1.0F) {
            decayAlpha = alpha;
        }
    }

    private static float consumeAlpha() {
        float a = decayAlpha;
        decayAlpha = -1.0F;
        return Math.min(a, 1.0F); // negative stays negative -> caller skips
    }

    // --- third person: called at LayerDecay.doRenderLayer TAIL (single shared return — the alpha gate is what
    // distinguishes "decay actually drew" from the early-out paths) -------------------------------------------------

    public static void drawLayersOverlay(AbstractClientPlayer player) {
        float alpha = consumeAlpha();
        if (broken || alpha <= 0.004F) {
            return;
        }
        try {
            if (!resolved && !resolve()) {
                return;
            }
            Object renderer = Minecraft.getMinecraft().getRenderManager().getEntityRenderObject(player);
            if (!accessorClass.isInstance(renderer)) {
                diagOnce("noAccessor", "player renderer " + renderer.getClass().getName()
                        + " does not implement PlayerEntityModelAccessor");
                return;
            }
            Object bodyParts = getSkinLayers.invoke(player);
            Object headPart = getHeadLayers.invoke(player);
            if (bodyParts == null && headPart == null) {
                return; // 3dSkinLayers inert for this player (default skin / not yet built) — silently stand down
            }
            if (!ensureTexture()) {
                return;
            }
            diagOnce("draw", "painting decay over the 3D layers (alpha=" + alpha + ")");
            // BL's decay pass leaves blend ENABLED with SRC_ALPHA/ONE_MINUS_SRC_ALPHA and colour opaque white —
            // exactly what the overlay needs. Assert it through GlStateManager anyway (cache-coherent, 2 calls).
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            bindAlphaScaled(alpha);
            if (bodyParts != null) {
                renderLayers.invoke(getBodyLayer.invoke(renderer), player, bodyParts, 0.0F);
            }
            if (headPart != null) {
                renderCustomHelmet.invoke(getHeadLayer.invoke(renderer), player, player, 0.0F);
            }
            // Restore exactly what LayerDecay leaves bound (it never rebinds after its own draw).
            Minecraft.getMinecraft().getTextureManager().bindTexture(DECAY_SKIN);
        } catch (Throwable t) {
            broken = true;
            YumeliumMod.LOGGER.warn("[compat] 3dSkinLayers decay overlay disabled", t);
        }
    }

    // --- first person: called at renderArmFirstPersonWithDecay TAIL — replicates 3dSkinLayers'
    // renderFirstPersonArm (postRender the posed arm, 0.0625 scale, voxel scale, arm x-offset, part.render) ------

    public static void drawFirstPersonSleeve(EnumHandSide side) {
        float alpha = consumeAlpha();
        if (broken || alpha <= 0.004F) {
            return;
        }
        try {
            if (!resolved && !resolve()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            AbstractClientPlayer player = mc.player;
            if (player == null) {
                return;
            }
            Object renderer = mc.getRenderManager().getEntityRenderObject(player);
            if (!(renderer instanceof RenderPlayer) || !accessorClass.isInstance(renderer)) {
                return;
            }
            Object parts = getSkinLayers.invoke(player);
            if (parts == null) {
                return; // no voxel sleeves built — nothing to paint
            }
            // renderLayers layer order: [0] leftPants [1] rightPants [2] leftSleeve [3] rightSleeve.
            Object sleeve = java.lang.reflect.Array.get(parts, side == EnumHandSide.LEFT ? 2 : 3);
            if (sleeve == null) {
                return;
            }
            if (!ensureTexture()) {
                return;
            }
            diagOnce("drawFp", "painting decay over the first-person sleeve (alpha=" + alpha + ")");
            ModelBiped model = ((RenderPlayer) renderer).getMainModel();
            ModelRenderer arm = side == EnumHandSide.LEFT ? model.bipedLeftArm : model.bipedRightArm;
            boolean thin = Boolean.TRUE.equals(hasThinArms.invoke(renderer));
            float voxel = baseVoxelSize != null ? baseVoxelSize : 1.15F;
            // renderLayers rewrites part.x on every third-person call, so this per-draw assignment needs no restore.
            partX.setFloat(sleeve, -(thin ? ARM_X_SLIM : ARM_X_CLASSIC));
            GlStateManager.pushMatrix();
            arm.postRender(0.0625F);
            GlStateManager.scale(0.0625F, 0.0625F, 0.0625F);
            GlStateManager.scale(voxel, 1.035F, voxel);
            // BL's method ends with blend DISABLED (its own disableBlend) — enable for the overlay, restore after.
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            bindAlphaScaled(alpha);
            partRender.invoke(sleeve, false);
            GlStateManager.disableBlend();
            mc.getTextureManager().bindTexture(DECAY_SKIN);
            GlStateManager.popMatrix();
        } catch (Throwable t) {
            broken = true;
            YumeliumMod.LOGGER.warn("[compat] 3dSkinLayers first-person decay overlay disabled", t);
        }
    }

    // --- internals ---------------------------------------------------------------------------------------------

    /** One line per distinct situation, so a single test run explains itself without spamming the log. */
    private static void diagOnce(String key, String message) {
        if (diagnosed.add(key)) {
            YumeliumMod.LOGGER.info("[compat] 3dSkinLayers decay: {}", message);
        }
    }

    private static boolean resolve() throws Exception {
        if (!Loader.isModLoaded(MOD_ID)) {
            broken = true;
            diagOnce("noMod", "mod '" + MOD_ID + "' is not loaded — standing down");
            return false;
        }
        Class<?> settings = Class.forName("dev.tr7zw.skinlayers.accessor.PlayerSettings");
        Class<?> partArray = Class.forName("[Ldev.tr7zw.skinlayers.render.CustomizableModelPart;");
        Class<?> part = Class.forName("dev.tr7zw.skinlayers.render.CustomizableModelPart");
        getSkinLayers = settings.getMethod("getSkinLayers");
        getHeadLayers = settings.getMethod("getHeadLayers");
        renderLayers = Class.forName("dev.tr7zw.skinlayers.renderlayers.BodyLayerFeatureRenderer")
                .getMethod("renderLayers", AbstractClientPlayer.class, partArray, float.class);
        renderCustomHelmet = Class.forName("dev.tr7zw.skinlayers.renderlayers.HeadLayerFeatureRenderer")
                .getMethod("renderCustomHelmet", settings, AbstractClientPlayer.class, float.class);
        accessorClass = Class.forName("dev.tr7zw.skinlayers.accessor.PlayerEntityModelAccessor");
        getBodyLayer = accessorClass.getMethod("getBodyLayer");
        getHeadLayer = accessorClass.getMethod("getHeadLayer");
        hasThinArms = accessorClass.getMethod("hasThinArms");
        partRender = part.getMethod("render", boolean.class);
        partX = part.getField("x");
        try {
            Object config = Class.forName("dev.tr7zw.skinlayers.SkinLayersModBase").getField("config").get(null);
            baseVoxelSize = ((Number) config.getClass().getField("baseVoxelSize").get(config)).floatValue();
        } catch (Throwable t) {
            baseVoxelSize = null; // fall back to the 1.2.0 default — cosmetic-only difference
        }
        resolved = true;
        YumeliumMod.LOGGER.info("[compat] 3dSkinLayers decay overlay active");
        return true;
    }

    /** Builds the template once (base decay art copied into the empty second-layer slots) and registers the
     * dynamic texture that {@link #bindAlphaScaled} re-uploads per alpha step. */
    private static boolean ensureTexture() throws Exception {
        if (template != null) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        BufferedImage image = TextureUtil.readBufferedImage(
                mc.getResourceManager().getResource(DECAY_SKIN).getInputStream());
        for (int[] region : OVERLAY_REGIONS) {
            for (int x = 0; x < region[4]; x++) {
                for (int y = 0; y < region[5]; y++) {
                    image.setRGB(region[0] + x, region[1] + y, image.getRGB(region[2] + x, region[3] + y));
                }
            }
        }
        int w = image.getWidth();
        int h = image.getHeight();
        template = image.getRGB(0, 0, w, h, null, 0, w);
        dynamicTexture = new DynamicTexture(w, h);
        mc.getTextureManager().loadTexture(LAYERED_DECAY_SKIN, dynamicTexture);
        lastUploadedQ = -1;
        return true;
    }

    /**
     * Binds the synthesised texture with its alpha channel scaled to the pulse — the ONLY way the pulse can reach
     * these draws (per-vertex colour overrides glColor; see the class doc). Quantised so camera+shadow passes and
     * consecutive frames at the same pulse step cost zero re-uploads.
     *
     * <p>DIAG (debug_logging, live — no reload needed): paints OUR overlay pure GREEN (alphas kept) so a
     * screenshot separates it from Betweenlands' own base decay — anything dark that is NOT green is the mod's
     * authentic base pass, not this compat.</p>
     */
    private static void bindAlphaScaled(float alpha) {
        boolean diag = me.jellysquid.mods.sodium.client.SodiumClientMod.debugLogs();
        int q = Math.max(0, Math.min(255, Math.round(alpha * 255.0F))) | (diag ? 0x10000 : 0);
        if (q != lastUploadedQ) {
            int pulse = q & 0xFFFF;
            int[] data = dynamicTexture.getTextureData();
            for (int i = 0; i < template.length; i++) {
                int a = template[i] >>> 24;
                data[i] = ((a * pulse / 255) << 24) | (diag ? 0x00FF00 : template[i] & 0x00FFFFFF);
            }
            dynamicTexture.updateDynamicTexture();
            lastUploadedQ = q;
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(LAYERED_DECAY_SKIN);
    }
}
