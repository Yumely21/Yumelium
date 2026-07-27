package com.yumelium.yumelium.client.cloud;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.IRenderHandler;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Yumelium custom FANCY cloud renderer, registered via Forge's {@code WorldProvider.setCloudRenderer} so it fully
 * REPLACES vanilla's {@code renderCloudsFancy} (which we could not mixin into in this runtime, and which draws all six
 * faces of every cloud box with culling disabled → the top/bottom faces cross in screen space as a faint diagonal seam).
 *
 * <p>Two wins over vanilla:</p>
 * <ul>
 *   <li><b>No seams/diagonal:</b> the cloud shape is read from {@code clouds.png} into a boolean grid, and we emit only
 *   the EXPOSED faces (a side only where the neighbour cell is empty; tops/bottoms only for cloud cells) and render with
 *   BACK-FACE CULLING. No interior/coincident faces, no front/back crossing → no diagonal, no double-blend.</li>
 *   <li><b>Optimised:</b> the whole 256×256 cloud tile is meshed ONCE into a static {@link VertexBuffer} (VBO) and just
 *   translated per-frame — vanilla rebuilds the mesh into a {@code BufferBuilder} every single frame.</li>
 * </ul>
 */
public class YumeliumCloudRenderer extends IRenderHandler {
    private static final ResourceLocation CLOUDS_TEXTURE = new ResourceLocation("textures/environment/clouds.png");

    private static final int TILE = 256;          // cloud texture is 256×256 cells and tiles
    private static final float CELL = 12.0F;       // horizontal size of one cloud cell (blocks)
    private static final float THICKNESS = 4.0F;   // cloud layer thickness (blocks)

    // Per-face brightness (baked into the vertex colour). The vertex colour also carries the translucency alpha; the
    // dynamic day/night/sunset cloud colour is multiplied in via a 1×1 texture (GL_MODULATE), because with a per-vertex
    // colour array the fixed-function pipeline IGNORES the global glColour — so we can't tint through GlStateManager.color.
    private static final float TOP = 1.0F, BOTTOM = 0.7F, NS = 0.9F, EW = 0.8F;
    private static final float ALPHA = 0.8F;       // cloud translucency (baked into the vertex alpha)

    private VertexBuffer vbo;
    private int vertexCount;
    private int colorTex = -1;
    private final ByteBuffer colorPixel = BufferUtils.createByteBuffer(4); // reused each frame (no per-frame allocation)
    private boolean buildFailed;

    @Override
    public void render(float partialTicks, WorldClient world, Minecraft mc) {
        if (this.buildFailed) {
            return; // fall back to nothing rather than crash; vanilla path is already skipped by the hook
        }
        // Shader packs declare `clouds = off` when they render their own volumetric clouds (Complementary does) —
        // drawing this host cloud layer on top of them doubles the sky. Honoured the way Iris does: no host clouds
        // while such a pack is active; instantly back when shaders are toggled off.
        if (com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().suppressVanillaClouds()) {
            return;
        }
        if (this.vbo == null) {
            build(mc);
            if (this.buildFailed) {
                return;
            }
        }

        double px = mc.player.prevPosX + (mc.player.posX - mc.player.prevPosX) * partialTicks;
        double py = mc.player.prevPosY + (mc.player.posY - mc.player.prevPosY) * partialTicks;
        double pz = mc.player.prevPosZ + (mc.player.posZ - mc.player.prevPosZ) * partialTicks;

        float cloudHeight = world.provider.getCloudHeight();
        double tileBlocks = TILE * CELL; // 3072 — the tile is far larger than the cloud view distance
        // Cloud drift (blocks) along +X, wrapped to one tile so it stays continuous without float-precision loss.
        double drift = (((double) (world.getTotalWorldTime() % 102400L)) + partialTicks) * 0.03;

        // Centre the 256-cell tile on the player and snap its origin to whole tiles (in the drifting frame) so the cloud
        // pattern is WORLD-FIXED (parallax as you move) and seamless across the tile edge (the mesh wraps at the seam).
        double centerTileX = Math.floor((px - drift) / tileBlocks + 0.5) * tileBlocks;
        double centerTileZ = Math.floor(pz / tileBlocks + 0.5) * tileBlocks;
        double offX = (centerTileX + drift - tileBlocks / 2.0) - px;
        double offZ = (centerTileZ - tileBlocks / 2.0) - pz;

        Vec3d c = world.getCloudColour(partialTicks);

        GlStateManager.enableCull(); // FANCY: cull back faces so only the outward shell draws (no diagonal)
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.disableAlpha();
        GlStateManager.depthMask(true);
        // 1×1 texture holding the dynamic cloud colour, MODULATED by the per-vertex shade+alpha → tinted, translucent clouds.
        bindCloudColorTexture((float) c.x, (float) c.y, (float) c.z);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.pushMatrix();
        // Position the cloud layer: translate to cloud height, then to the wrapped tile origin, then scale to cell size.
        GlStateManager.translate(offX, cloudHeight - py, offZ);
        GlStateManager.scale(CELL, 1.0F, CELL);

        this.vbo.bindBuffer();
        setupVertexPointers();
        this.vbo.drawArrays(GL11.GL_QUADS);
        this.vbo.unbindBuffer();
        clearVertexPointers();

        GlStateManager.popMatrix();

        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void setupVertexPointers() {
        int stride = DefaultVertexFormats.POSITION_COLOR.getSize();
        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, stride, 0L);
        GlStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, stride, 12L);
    }

    private static void clearVertexPointers() {
        GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_COLOR_ARRAY);
    }

    /** Uploads the current cloud colour into a 1×1 texture and binds it, so GL_MODULATE tints the per-vertex shade+alpha. */
    private void bindCloudColorTexture(float r, float g, float b) {
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        this.colorPixel.clear();
        this.colorPixel.put((byte) ((int) (r * 255.0F) & 0xFF)).put((byte) ((int) (g * 255.0F) & 0xFF))
                .put((byte) ((int) (b * 255.0F) & 0xFF)).put((byte) 255).flip();
        if (this.colorTex < 0) {
            this.colorTex = GL11.glGenTextures();
            GlStateManager.bindTexture(this.colorTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.colorPixel);
        } else {
            GlStateManager.bindTexture(this.colorTex);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.colorPixel);
        }
    }

    private void build(Minecraft mc) {
        try {
            boolean[][] cloud = readMask(mc);
            // A DEDICATED buffer (not Tessellator's shared one) so this large one-time mesh doesn't permanently bloat the
            // global BufferBuilder that particles/etc. reuse. Sized generously; it grows if needed.
            BufferBuilder buf = new BufferBuilder(0x100000);
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

            // Geometry is in CELL units on X/Z (scaled at draw time) and blocks on Y.
            float y0 = 0.0F;
            float y1 = THICKNESS;
            int count = 0;
            for (int cx = 0; cx < TILE; cx++) {
                for (int cz = 0; cz < TILE; cz++) {
                    if (!cloud[cx][cz]) {
                        continue;
                    }
                    float x0 = cx, x1 = cx + 1;
                    float z0 = cz, z1 = cz + 1;

                    // Top (y1, +Y) and bottom (y0, -Y) — always exposed for a cloud cell.
                    quad(buf, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, TOP);        // top, facing up
                    quad(buf, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, BOTTOM);     // bottom, facing down
                    count += 2;

                    // Sides only where the neighbour cell is empty (exposed edge) — wound outward for back-face culling.
                    if (!at(cloud, cx - 1, cz)) { quad(buf, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, EW); count++; } // -X
                    if (!at(cloud, cx + 1, cz)) { quad(buf, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, EW); count++; } // +X
                    if (!at(cloud, cx, cz - 1)) { quad(buf, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, NS); count++; } // -Z
                    if (!at(cloud, cx, cz + 1)) { quad(buf, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, NS); count++; } // +Z
                }
            }

            buf.finishDrawing();
            this.vertexCount = count * 4;
            this.vbo = new VertexBuffer(DefaultVertexFormats.POSITION_COLOR);
            this.vbo.bufferData(buf.getByteBuffer());
            buf.reset();
            SodiumClientMod.logger().info("[Yumelium] custom cloud renderer built VBO: " + count + " quads (exposed faces, back-face culled)");
        } catch (Throwable t) {
            this.buildFailed = true;
            SodiumClientMod.logger().error("[Yumelium] custom cloud renderer build failed; clouds disabled", t);
        }
    }

    private static boolean at(boolean[][] cloud, int x, int z) {
        return cloud[(x % TILE + TILE) % TILE][(z % TILE + TILE) % TILE];
    }

    /** One quad, 4 vertices (v0..v3 wound CCW when viewed from outside), shaded grey by {@code shade}. */
    private static void quad(BufferBuilder buf,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float shade) {
        int s = (int) (shade * 255.0F) & 0xFF;
        int a = (int) (ALPHA * 255.0F) & 0xFF; // translucency baked into the vertex alpha (the global glColour is ignored)
        buf.pos(ax, ay, az).color(s, s, s, a).endVertex();
        buf.pos(bx, by, bz).color(s, s, s, a).endVertex();
        buf.pos(cx, cy, cz).color(s, s, s, a).endVertex();
        buf.pos(dx, dy, dz).color(s, s, s, a).endVertex();
    }

    private static boolean[][] readMask(Minecraft mc) throws Exception {
        BufferedImage img;
        try (java.io.InputStream in = mc.getResourceManager().getResource(CLOUDS_TEXTURE).getInputStream()) {
            img = ImageIO.read(in);
        }
        int w = Math.min(TILE, img.getWidth());
        int h = Math.min(TILE, img.getHeight());
        boolean[][] cloud = new boolean[TILE][TILE];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                int argb = img.getRGB(x, z);
                int a = (argb >>> 24) & 0xFF;
                cloud[x][z] = a > 128; // opaque texel = cloud cell
            }
        }
        return cloud;
    }

    public void destroy() {
        if (this.vbo != null) {
            this.vbo.deleteGlBuffers();
            this.vbo = null;
        }
        if (this.colorTex >= 0) {
            GL11.glDeleteTextures(this.colorTex);
            this.colorTex = -1;
        }
    }
}
