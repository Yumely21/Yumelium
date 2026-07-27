package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: feeds the pack's {@code currentRenderedItemId} uniform (item.properties id) as each item MODEL is
 * about to draw. {@code renderItem(ItemStack, IBakedModel)} is the single funnel every in-world item render goes
 * through — the first-person held item, dropped items, item frames, mob-held/armor items — so hooking it keeps the id
 * honest per model under the one bound gbuffers program. The pack's irisIPBR dispatches every item material off this
 * id. No-ops for GUI item rendering (no gbuffers pass is live there).
 */
@Mixin(RenderItem.class)
public class MixinRenderItemIris {
    /** DIAGNOSTIC (sea lantern hotbar icon broken with shaders on): at the EXACT icon draw, dump the GL state + the
     * baked model's quad UVs. The sprite's atlas rect is known (≈[0.781, 0.219] + 0.031); a quad UV outside it means
     * the BAKED MODEL is sampling the wrong atlas region — not a GL state leak at all. */
    private static final boolean YUMELIUM$DIAG_ICON = false;
    private static long yumelium$lastIconDiag;

    /** The enchantment-glint overlay for ITEM models (held items, dropped items, item frames, mob-held): swap in the
     * pack's dedicated gbuffers_armor_glint around it. No-ops in the GUI (no gbuffers pass live). */
    @Inject(method = "renderEffect", at = @At("HEAD"))
    private void yumelium$beginGlint(IBakedModel model, CallbackInfo ci) {
        IrisPipeline.instance().beginArmorGlint();
    }

    @Inject(method = "renderEffect", at = @At("RETURN"))
    private void yumelium$endGlint(IBakedModel model, CallbackInfo ci) {
        IrisPipeline.instance().endArmorGlint();
    }

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
            at = @At("HEAD"))
    private void yumelium$itemUniform(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        IrisPipeline.instance().onItemRender(stack);
        if (YUMELIUM$DIAG_ICON && stack != null && !stack.isEmpty()
                && stack.getItem() instanceof net.minecraft.item.ItemBlock
                && ((net.minecraft.item.ItemBlock) stack.getItem()).getBlock() == net.minecraft.init.Blocks.SEA_LANTERN
                && !IrisPipeline.instance().isCapturingWorld()
                && System.currentTimeMillis() - yumelium$lastIconDiag > 1000L) {
            yumelium$lastIconDiag = System.currentTimeMillis();
            yumelium$dumpIconDraw(model);
        }
    }

    private static void yumelium$dumpIconDraw(IBakedModel model) {
        try {
            StringBuilder sb = new StringBuilder("[ICON DIAG] sea lantern GUI draw:");
            sb.append(" program=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM));
            sb.append(" blend=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND));
            sb.append(" alpha=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_ALPHA_TEST));
            sb.append(" lighting=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_LIGHTING));
            sb.append(" tex2D=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_TEXTURE_2D));
            sb.append(" boundTex=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D));
            java.nio.FloatBuffer col = org.lwjgl.BufferUtils.createFloatBuffer(16);
            org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_CURRENT_COLOR, col);
            sb.append(String.format(" curColor=(%.2f,%.2f,%.2f,%.2f)", col.get(0), col.get(1), col.get(2), col.get(3)));
            sb.append(" glErr=0x").append(Integer.toHexString(org.lwjgl.opengl.GL11.glGetError()));
            sb.append(" depthTest=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST));
            sb.append(" depthFunc=0x").append(Integer.toHexString(
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DEPTH_FUNC)));
            sb.append(" depthMask=").append(org.lwjgl.opengl.GL11.glGetBoolean(org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK));
            sb.append(" rescaleNormal=").append(org.lwjgl.opengl.GL11.glIsEnabled(0x803A));
            sb.append(" normalize=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_NORMALIZE));
            sb.append(" colorMaterial=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_COLOR_MATERIAL));
            // The GUI icon transform: a Z-collapsed or otherwise degenerate matrix flattens the cube (edges-only look)
            // while leaving flat 2D item sprites intact. Compare these between shaders ON and OFF.
            java.nio.FloatBuffer mv = org.lwjgl.BufferUtils.createFloatBuffer(16);
            org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, mv);
            sb.append(String.format(" | MV diag=(%.2f,%.2f,%.2f) trans=(%.1f,%.1f,%.1f)",
                    mv.get(0), mv.get(5), mv.get(10), mv.get(12), mv.get(13), mv.get(14)));
            java.nio.FloatBuffer pr = org.lwjgl.BufferUtils.createFloatBuffer(16);
            org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, pr);
            sb.append(String.format(" | PROJ diag=(%.5f,%.5f,%.5f) m32=%.3f m33=%.1f",
                    pr.get(0), pr.get(5), pr.get(10), pr.get(14), pr.get(15)));
            sb.append(" | fbo=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING));
            // Identity + content check: is the bound texture REALLY the block atlas, and is the sea lantern's cell
            // (sprite at 400,112; centre ≈ 405,117) opaque at the mips the icon could sample? Read straight from GL.
            int expectedAtlas = net.minecraft.client.Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId();
            int bound = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            sb.append(" expectedAtlas=").append(expectedAtlas)
                    .append(bound == expectedAtlas ? " (MATCH)" : " *** BOUND TEXTURE IS NOT THE ATLAS ***");
            java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
            for (int lvl = 0; lvl <= 2; lvl++) {
                px.clear();
                org.lwjgl.opengl.GL45.glGetTextureSubImage(bound, lvl, 405 >> lvl, 117 >> lvl, 0, 1, 1, 1,
                        org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
                sb.append(String.format(" mip%d=(%d,%d,%d,a=%d)", lvl,
                        px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF, px.get(3) & 0xFF));
            }
            // Vertex-array machinery: the tessellator draws GUI icons through CLIENT arrays; a leftover VAO (shaders-on
            // terrain path) or broken normal-array state would corrupt the icon's normals → flat-dark lit faces.
            sb.append(" | vao=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING));
            sb.append(" arrayBuf=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING));
            sb.append(" normArr=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_NORMAL_ARRAY));
            java.nio.FloatBuffer cn = org.lwjgl.BufferUtils.createFloatBuffer(16);
            org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_CURRENT_NORMAL, cn);
            sb.append(String.format(" curNormal=(%.2f,%.2f,%.2f)", cn.get(0), cn.get(1), cn.get(2)));
            // First UP-facing quad's UV corners (DefaultVertexFormats.ITEM stride = 7 ints; UV floats at ints 4..5).
            java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                    model.getQuads(null, net.minecraft.util.EnumFacing.UP, 0L);
            if (!quads.isEmpty()) {
                int[] vd = quads.get(0).getVertexData();
                float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                for (int v = 0; v < 4; v++) {
                    float u = Float.intBitsToFloat(vd[v * 7 + 4]);
                    float vv = Float.intBitsToFloat(vd[v * 7 + 5]);
                    minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                    minV = Math.min(minV, vv); maxV = Math.max(maxV, vv);
                }
                sb.append(String.format(" | UP quad UV=[%.4f..%.4f, %.4f..%.4f] (sprite expected ≈[0.781..0.813, 0.219..0.250])",
                        minU, maxU, minV, maxV));
            } else {
                sb.append(" | UP quads: NONE");
            }
            me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info(sb.toString());
        } catch (Throwable t) {
            me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info("[ICON DIAG] failed: " + t);
        }
    }
}
