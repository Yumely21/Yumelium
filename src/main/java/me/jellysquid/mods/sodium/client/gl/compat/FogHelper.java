package me.jellysquid.mods.sodium.client.gl.compat;

import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

/**
 * Reads the fixed-function GL fog state so it can be copied into Sodium's terrain shader uniforms.
 *
 * <p>NOTE(yumelium): 1.16.5 read fog from {@code com.mojang.blaze3d.platform.GlStateManager.FOG} and
 * {@code FogRenderer.fogRed/Green/Blue}. 1.12.2 has neither — fog state lives in the legacy
 * {@code net.minecraft.client.renderer.GlStateManager.fogState} and the fog color in
 * {@code EntityRenderer.fogColor{Red,Green,Blue}} (all exposed via {@code yumelium_at.cfg}).
 */
public class FogHelper {
    private static final float FAR_PLANE_THRESHOLD_EXP = (float) Math.log(1.0f / 0.0019f);
    private static final float FAR_PLANE_THRESHOLD_EXP2 = MathHelper.sqrt(FAR_PLANE_THRESHOLD_EXP);

    public static float getFogEnd() {
        return GlStateManager.fogState.end;
    }

    public static float getFogStart() {
        return GlStateManager.fogState.start;
    }

    public static float getFogDensity() {
        return GlStateManager.fogState.density;
    }

    public static float getFogCutoff() {
        int mode = GlStateManager.fogState.mode;

        switch (mode) {
            case GL11.GL_LINEAR:
                return getFogEnd();
            case GL11.GL_EXP:
                return FAR_PLANE_THRESHOLD_EXP / getFogDensity();
            case GL11.GL_EXP2:
                return FAR_PLANE_THRESHOLD_EXP2 / getFogDensity();
            default:
                return 0.0f;
        }
    }

    public static float[] getFogColor() {
        EntityRenderer er = Minecraft.getMinecraft().entityRenderer;
        return new float[]{ er.fogColorRed, er.fogColorGreen, er.fogColorBlue, 1.0F };
    }

    public static ChunkFogMode getFogMode() {
        int mode = GlStateManager.fogState.mode;

        if (mode == 0 || !GlStateManager.fogState.fog.currentState) {
            return ChunkFogMode.NONE;
        }

        switch (mode) {
            case GL11.GL_EXP2:
            case GL11.GL_EXP:
                return ChunkFogMode.EXP2;
            case GL11.GL_LINEAR:
                return ChunkFogMode.SMOOTH;
            default:
                throw new UnsupportedOperationException("Unknown fog mode: " + mode);
        }
    }
}
