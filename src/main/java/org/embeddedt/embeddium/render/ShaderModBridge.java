package org.embeddedt.embeddium.render;

import net.minecraft.client.gui.GuiScreen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public class ShaderModBridge {
    private static final MethodHandle SHADERS_ENABLED;
    private static final MethodHandle SHADERS_OPEN_SCREEN;
    private static final MethodHandle NVIDIUM_ENABLED;

    static {
        MethodHandle shadersEnabled = null, shaderOpenScreen = null;
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method instanceGetter = irisApiClass.getDeclaredMethod("getInstance");
            Object irisApiInstance = instanceGetter.invoke(null);
            shadersEnabled = MethodHandles.lookup().unreflect(irisApiClass.getDeclaredMethod("isShaderPackInUse")).bindTo(irisApiInstance);
            shaderOpenScreen =  MethodHandles.lookup().unreflect(irisApiClass.getDeclaredMethod("openMainIrisScreenObj", Object.class)).bindTo(irisApiInstance);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (Throwable ignored) {
        }
        SHADERS_ENABLED = shadersEnabled;
        SHADERS_OPEN_SCREEN = shaderOpenScreen;
        MethodHandle nvidiumEnabled = null;
        try {
            Class<?> nvidiumClass = Class.forName("me.cortex.nvidium.Nvidium");
            nvidiumEnabled = MethodHandles.lookup().findStaticGetter(nvidiumClass, "IS_ENABLED", boolean.class);
        } catch (Throwable ignored) {
        }
        NVIDIUM_ENABLED = nvidiumEnabled;
    }

    public static boolean isNvidiumEnabled() {
        if(NVIDIUM_ENABLED != null) {
            try {
                return (boolean)NVIDIUM_ENABLED.invokeExact();
            } catch(Throwable e) {
                return false;
            }
        } else {
            return false;
        }
    }

    public static boolean areShadersEnabled() {
        // Yumelium's own pipeline IS a shader pack in use, even though the real Iris API facade this probes is absent.
        // Without this every caller (Sodium's fog-occlusion chunk culling in RenderSectionManager, etc.) thinks shaders
        // are off. That made fog occlusion cull chunks past the SHADER-computed fog distance: underwater the water fog is
        // short, so distant chunks were culled outright (a hard cutoff that raising the render distance couldn't fix),
        // while on land the long atmospheric fog hid it. Shaders on → Sodium must render all chunks to the render distance
        // and let the shader fade them.
        if (isYumeliumPipelineEnabled()) {
            return true;
        }
        if(SHADERS_ENABLED != null) {
            try {
                return (boolean)SHADERS_ENABLED.invokeExact();
            } catch (Throwable e) {
                return false;
            }
        } else {
            return false;
        }
    }

    public static boolean isShaderModPresent() {
        return SHADERS_ENABLED != null;
    }

    public static boolean emulateLegacyColorBrightnessFormat() {
        return areShadersEnabled() || isNvidiumEnabled() || isYumeliumPipelineEnabled();
    }

    /**
     * True when Yumelium's own Iris pipeline is rendering — the real signal for "a shader pack drives terrain here",
     * since this port never ships the {@code net.irisshaders.iris.api.v0.IrisApi} facade {@link #areShadersEnabled()}
     * probes (so that always returns false). Without this, terrain meshed with the EMBEDDIUM colour format, which
     * multiplies AO×face-shade into the vertex-colour RGB. The pack's gbuffers expect the LEGACY/OptiFine convention
     * (RGB = biome tint only, alpha = AO), and compute their own face shading from the normal (directionShade). So the
     * baked-in face shade rode into every emission the pack derives from the albedo (e.g. glowing ores: {@code
     * emission = pow1_5(color.r) * 1.5}) → side faces glowed weaker than top faces, and vanillaAO (glColor.a) read a
     * flat 1.0 (no ambient occlusion at all). Matching {@link ChunkMeshFormats#activeCompact()}'s pipeline check keeps
     * the colour format and the vertex format switching together on the same signal.
     */
    private static boolean isYumeliumPipelineEnabled() {
        try {
            com.yumelium.yumelium.shaders.pipeline.IrisPipeline p = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance();
            return p != null && p.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object openShaderScreen(GuiScreen parentScreen) {
        if(SHADERS_OPEN_SCREEN != null) {
            try {
                return SHADERS_OPEN_SCREEN.invoke(parentScreen);
            } catch(Throwable e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
