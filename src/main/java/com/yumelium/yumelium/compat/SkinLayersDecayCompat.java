package com.yumelium.yumelium.compat;

import com.yumelium.yumelium.YumeliumMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 3dSkinLayers × Betweenlands decay compat — paints the decay overlay onto the 3D skin layers too.
 *
 * <p>Betweenlands draws decay as {@code DecayRenderHandler$LayerDecay}, re-rendering the VANILLA
 * {@code ModelPlayer} with {@code player_decay.png} at a pulsing alpha. 3dSkinLayers replaces the flat second
 * skin layer with extruded geometry that pass knows nothing about, so at high decay the body rots while the
 * hat, jacket and sleeves stay clean.</p>
 *
 * <p>ORDER MATTERS, and it is not the obvious one (established from in-game diagnostics, 2026-08-05):</p>
 * <ol>
 *   <li>Betweenlands' decay layer runs FIRST. At that moment {@code PlayerSettings.getSkinLayers()} is still
 *       {@code null} — 3dSkinLayers builds its geometry lazily inside its own {@code doRenderLayer}. An overlay
 *       issued from the Betweenlands pass therefore has nothing to paint on. All we do there is
 *       {@link #captureDecayAlpha} the colour it sets.</li>
 *   <li>3dSkinLayers' body and head layers run afterwards. At the RETURN of each, the parts exist and are posed,
 *       so {@link #drawBodyDecay} / {@link #drawHeadDecay} re-draw them with the decay texture and the captured
 *       alpha. Nothing of Betweenlands' pulse formula is duplicated.</li>
 * </ol>
 *
 * <p>WHY A SYNTHESISED TEXTURE: {@code player_decay.png} is a 64×64 skin layout whose SECOND-layer regions are
 * fully transparent (measured: head overlay 0% opaque, body overlay 0%, against 75%/92% for the base regions).
 * The 3D parts sample exactly those regions, so binding the file as-is draws nothing. A derived texture is built
 * once, copying each base part into its matching overlay slot; the six copies are disjoint, so it is done in
 * place.</p>
 *
 * <p>Reflection rather than a compile dependency, matching {@link BetweenlandsCompat}. 3dSkinLayers does not
 * register its renderers as vanilla layers — {@code PlayerRendererMixin} keeps them in private fields on
 * {@code RenderPlayer} and exposes them through {@code PlayerEntityModelAccessor}. Its two render entry points
 * bind no texture and touch no colour (verified in 1.2.0 bytecode), so inheriting our GL state is safe. Any
 * resolution failure sets {@link #broken} and stands down permanently.</p>
 */
public final class SkinLayersDecayCompat {

    private static final String MOD_ID = "skinlayers3d";
    private static final String BODY_LAYER_CLASS = "dev.tr7zw.skinlayers.renderlayers.BodyLayerFeatureRenderer";
    private static final String HEAD_LAYER_CLASS = "dev.tr7zw.skinlayers.renderlayers.HeadLayerFeatureRenderer";
    private static final String PLAYER_SETTINGS_CLASS = "dev.tr7zw.skinlayers.accessor.PlayerSettings";
    private static final String MODEL_ACCESSOR_CLASS = "dev.tr7zw.skinlayers.accessor.PlayerEntityModelAccessor";

    private static final ResourceLocation DECAY_SKIN =
            new ResourceLocation("thebetweenlands", "textures/entity/player_decay.png");
    private static final ResourceLocation LAYERED_DECAY_SKIN =
            new ResourceLocation("yumelium", "decay_skin_layers");

    /** dstX, dstY, srcX, srcY, w, h — 1.8+ layout, every second-layer slot filled from its base part. */
    private static final int[][] OVERLAY_REGIONS = {
            {32, 0, 0, 0, 32, 16},      // hat          <- head
            {16, 32, 16, 16, 24, 16},   // jacket       <- body
            {40, 32, 40, 16, 16, 16},   // right sleeve <- right arm
            {0, 32, 0, 16, 16, 16},     // right leg    <- right leg
            {48, 48, 32, 48, 16, 16},   // left sleeve  <- left arm
            {0, 48, 16, 48, 16, 16},    // left leg     <- left leg
    };

    private static final Set<String> diagnosed = new HashSet<>();

    private static boolean broken;
    private static boolean resolved;
    private static boolean textureReady;
    private static float decayAlpha = -1.0F;

    private static Class<?> accessorClass;
    private static Method getBodyLayer;
    private static Method getHeadLayer;
    private static Method getSkinLayers;
    private static Method renderLayers;
    private static Method renderCustomHelmet;

    private SkinLayersDecayCompat() {
    }

    /**
     * Called for every {@code GlStateManager.color} the Betweenlands decay pass issues. Its pre-draw call carries
     * the pulsing alpha; its post-draw opaque-white reset is IGNORED — clearing on it was the original bug
     * (2026-08-06): the reset fires inside the same {@code doRenderLayer}, so the snapshot was gone before
     * 3dSkinLayers' layers (which run AFTER the decay layer) could consume it — body rotted, layers stayed clean,
     * and the log stayed silent (the draw hook exits before its first diagnostic when no alpha is set).
     */
    public static void captureDecayAlpha(float alpha) {
        if (alpha < 1.0F) {
            decayAlpha = alpha;
        }
    }

    /**
     * Called at the HEAD of every {@code LayerDecay.doRenderLayer} — i.e. once per rendered player, BEFORE that
     * player's decay pass decides whether to draw. A decayed player re-captures immediately afterwards; a
     * decay-free player leaves the snapshot cleared, so their 3dSkinLayers parts are never painted with a
     * previous player's alpha.
     */
    public static void clearDecayAlpha() {
        decayAlpha = -1.0F;
    }

    public static void drawBodyDecay(AbstractClientPlayer player, float scale) {
        draw(player, scale, true);
    }

    public static void drawHeadDecay(AbstractClientPlayer player, float scale) {
        draw(player, scale, false);
    }

    private static void draw(AbstractClientPlayer player, float scale, boolean body) {
        if (broken || decayAlpha < 0.0F) {
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
            Object layer = (body ? getBodyLayer : getHeadLayer).invoke(renderer);
            if (layer == null) {
                return; // that half is switched off in 3dSkinLayers' own config
            }
            Object parts = body ? getSkinLayers.invoke(player) : null;
            if (body && parts == null) {
                diagOnce("noParts", "geometry still null at the 3dSkinLayers RETURN hook");
                return;
            }
            if (!ensureTexture()) {
                return;
            }
            diagOnce(body ? "drawBody" : "drawHead",
                    "drawing decay over the " + (body ? "body" : "head") + " layers, alpha=" + decayAlpha);
            // SAVE the state this draw touches. Leaking it was the "base body renders broken" bug (2026-08-06):
            // while the alpha capture was broken this draw never ran, but the moment it started running every
            // frame, the blend-enable + the decay texture left bound bled into everything rendered after the
            // skin-layer hooks — including the player's own base model on subsequent frames.
            boolean blendWasOn = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
            int prevBlendSrc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_BLEND_SRC);
            int prevBlendDst = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_BLEND_DST);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            Minecraft.getMinecraft().getTextureManager().bindTexture(LAYERED_DECAY_SKIN);
            GlStateManager.color(1.0F, 1.0F, 1.0F, decayAlpha);
            try {
                if (body) {
                    renderLayers.invoke(layer, player, parts, scale);
                } else {
                    renderCustomHelmet.invoke(layer, player, player, scale);
                }
            } finally {
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                GlStateManager.blendFunc(prevBlendSrc, prevBlendDst);
                if (!blendWasOn) {
                    GlStateManager.disableBlend();
                }
                // Re-bind the player's skin — the texture every later layer/pass of this entity expects.
                Minecraft.getMinecraft().getTextureManager().bindTexture(player.getLocationSkin());
            }
        } catch (Throwable t) {
            broken = true;
            YumeliumMod.LOGGER.warn("[compat] 3dSkinLayers decay overlay disabled", t);
        }
    }

    private static boolean textureBroken;

    /**
     * First-person hand path (MixinDecayRenderHandlerHand): Betweenlands' {@code renderArmFirstPersonWithDecay}
     * binds the raw {@code player_decay.png}, whose SECOND-LAYER regions are empty — so the first-person sleeve
     * showed no decay (the same asset gap the synthesised texture fixes for the 3D layers). Swap in the derived
     * texture, but ONLY for the decay bind: the method also binds the player's skin for the arm base, so the
     * caller redirects every bind and this gate matches by VALUE (no Betweenlands class references).
     */
    public static ResourceLocation handDecayTexture(ResourceLocation original) {
        if (!textureBroken && DECAY_SKIN.equals(original)) {
            try {
                if (ensureTexture()) {
                    return LAYERED_DECAY_SKIN;
                }
            } catch (Throwable t) {
                textureBroken = true;
                YumeliumMod.LOGGER.warn("[compat] decay hand-texture synthesis failed — first-person sleeve keeps"
                        + " the stock (empty) overlay regions", t);
            }
        }
        return original;
    }

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
        Class<?> settings = Class.forName(PLAYER_SETTINGS_CLASS);
        Class<?> parts = Class.forName("[Ldev.tr7zw.skinlayers.render.CustomizableModelPart;");
        getSkinLayers = settings.getMethod("getSkinLayers");
        renderLayers = Class.forName(BODY_LAYER_CLASS)
                .getMethod("renderLayers", AbstractClientPlayer.class, parts, float.class);
        renderCustomHelmet = Class.forName(HEAD_LAYER_CLASS)
                .getMethod("renderCustomHelmet", settings, AbstractClientPlayer.class, float.class);
        accessorClass = Class.forName(MODEL_ACCESSOR_CLASS);
        getBodyLayer = accessorClass.getMethod("getBodyLayer");
        getHeadLayer = accessorClass.getMethod("getHeadLayer");
        resolved = true;
        YumeliumMod.LOGGER.info("[compat] 3dSkinLayers decay overlay active");
        return true;
    }

    private static boolean ensureTexture() throws Exception {
        if (textureReady) {
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
        mc.getTextureManager().loadTexture(LAYERED_DECAY_SKIN, new DynamicTexture(image));
        textureReady = true;
        return true;
    }

}
