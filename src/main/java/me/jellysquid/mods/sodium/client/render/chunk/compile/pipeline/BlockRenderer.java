package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import com.yumelium.yumelium.client.ctm.CtmQuad;
import com.yumelium.yumelium.client.ctm.CtmRegistry;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.model.color.ColorProvider;
import me.jellysquid.mods.sodium.client.model.color.ColorProviderRegistry;
import me.jellysquid.mods.sodium.client.model.light.LightMode;
import me.jellysquid.mods.sodium.client.model.light.LightPipeline;
import me.jellysquid.mods.sodium.client.model.light.LightPipelineProvider;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import me.jellysquid.mods.sodium.client.util.DirectionUtil;
import me.jellysquid.mods.sodium.client.util.ModelQuadUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import org.embeddedt.embeddium.render.chunk.ChunkColorWriter;

import java.util.Arrays;
import java.util.List;

/**
 * The Embeddium equivalent to vanilla's ModelBlockRenderer. This is the primary component of the chunk meshing logic;
 * it is responsible for accepting {@link BlockRenderContext} and generating the appropriate geometry.
 *
 * <p>NOTE(yumelium): stripped Embeddium/1.16.5-only features that don't apply to a base 1.12.2 port —
 * custom BlockRendererRegistry renderers (+ CCL SinkingVertexBuilder), the Fabric FRAPI pipeline, the sprite
 * transparency render-pass optimization, and EmbeddiumBakedModelExtension. Adapted to 1.12.2 model API:
 * {@code IBakedModel#getQuads(IBlockState, EnumFacing, long)}, {@code IBlockState#getOffset}, Vec3d, IBlockAccess.
 */
public class BlockRenderer {
    private final ColorProviderRegistry colorProviderRegistry;
    private final BlockOcclusionCache occlusionCache;

    private final QuadLightData quadLightData = new QuadLightData();

    private final LightPipelineProvider lighters;

    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    private final boolean useAmbientOcclusion;
    // Leaves Quality = FAST: cull faces between adjacent leaf blocks (Cull-Leaves / OptiFine "Trees: Smart" style)
    // while keeping the fancy leaf texture/layer. Read once per BlockRenderer — the option's REQUIRES_RENDERER_RELOAD
    // flag rebuilds the ChunkBuilder (and thus this object) whenever it changes.
    private final boolean cullLeavesInnerFaces;

    private final int[] quadColors = new int[4];

    // Reused for better-grass neighbour lookups.
    private final BlockPos.MutableBlockPos bgScratch = new BlockPos.MutableBlockPos();
    // Reused for connected-texture neighbour lookups.
    private final BlockPos.MutableBlockPos ctmScratch = new BlockPos.MutableBlockPos();

    private boolean useReorienting;

    private final ChunkColorWriter colorEncoder = ChunkColorWriter.get();

    public BlockRenderer(ColorProviderRegistry colorRegistry, LightPipelineProvider lighters) {
        this.colorProviderRegistry = colorRegistry;
        this.lighters = lighters;

        this.occlusionCache = new BlockOcclusionCache();
        this.useAmbientOcclusion = Minecraft.isAmbientOcclusionEnabled();
        this.cullLeavesInnerFaces = !SodiumClientMod.options().quality.leavesQuality
                .isFancy(Minecraft.getMinecraft().gameSettings.fancyGraphics);
    }

    public void renderModel(BlockRenderContext ctx, ChunkBuildBuffers buffers) {
        // Tag foliage blocks (grass/leaves/...) so the transformed Iris terrain shader can synthesize mc_Entity and
        // Complementary identifies them natively (waving + non-directional foliage lighting). Same render pass as the base
        // material, so the mesh builder is unchanged; only the per-vertex material bits differ.
        var material = DefaultMaterials.forFoliage(DefaultMaterials.forRenderLayer(ctx.renderLayer()), foliageType(ctx.state()));
        var meshBuilder = buffers.get(material);

        ColorProvider<IBlockState> colorizer = this.colorProviderRegistry.getColorProvider(ctx.state().getBlock());

        LightMode mode = this.getLightingMode(ctx.state(), ctx.model(), ctx.localSlice(), ctx.pos());
        LightPipeline lighter = this.lighters.getLighter(mode);
        Vec3d renderOffset = ctx.state().getOffset(ctx.localSlice(), ctx.pos());

        boolean canReorientNullCullface = true;

        // Better grass: for a grass-like block, its top sprite (+ tint) is used on qualifying side faces.
        SodiumGameOptions.BetterGrassMode grassMode = SodiumClientMod.options().yumeliumPlus.betterGrass;
        BetterGrass.TopFace grassTop = (grassMode != SodiumGameOptions.BetterGrassMode.OFF && BetterGrass.isGrassLike(ctx.state()))
                ? BetterGrass.topFace(ctx.model(), ctx.state(), ctx.seed())
                : null;

        for (EnumFacing face : DirectionUtil.ALL_DIRECTIONS) {
            List<BakedQuad> quads = this.getGeometry(ctx, face);

            if (!quads.isEmpty() && this.isFaceVisible(ctx, face)) {
                BetterGrass.TopFace faceGrass = null;
                if (grassTop != null && face.getAxis().isHorizontal()
                        && BetterGrass.shouldReplaceSide(grassMode, ctx.world(), ctx.state(), this.bgScratch,
                                ctx.pos().getX(), ctx.pos().getY(), ctx.pos().getZ(), face)) {
                    faceGrass = grassTop;
                }

                this.useReorienting = true;
                this.renderQuadList(ctx, material, lighter, colorizer, renderOffset, meshBuilder, quads, face, faceGrass);
                if (!this.useReorienting) {
                    canReorientNullCullface = false;
                }
            }
        }

        List<BakedQuad> all = this.getGeometry(ctx, null);

        if (!all.isEmpty()) {
            this.useReorienting = canReorientNullCullface;
            this.renderQuadList(ctx, material, lighter, colorizer, renderOffset, meshBuilder, all, null, null);
        }
    }

    private List<BakedQuad> getGeometry(BlockRenderContext ctx, EnumFacing face) {
        return ctx.model().getQuads(ctx.state(), face, ctx.seed());
    }

    /**
     * Classifies a block into a foliage category (see {@link DefaultMaterials}) so its quads carry a {@code mc_Entity} the
     * shader pack understands: grounded plants (grass/ferns/saplings/crops/dead bush/flowers/double-plant-lower) → 10005,
     * leaves → 10009, double-plant upper half → 10021. Mirrors Complementary's {@code block.properties} for 1.12.2 so the
     * pack waves + shades them as foliage. Non-foliage blocks return {@link DefaultMaterials#FOLIAGE_NONE}.
     */
    private static int foliageType(IBlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        if (block instanceof net.minecraft.block.BlockLeaves) {
            return DefaultMaterials.FOLIAGE_LEAVES;
        }
        if (block instanceof net.minecraft.block.BlockDoublePlant) {
            try {
                return state.getValue(net.minecraft.block.BlockDoublePlant.HALF)
                        == net.minecraft.block.BlockDoublePlant.EnumBlockHalf.UPPER
                        ? DefaultMaterials.FOLIAGE_UPPER : DefaultMaterials.FOLIAGE_GROUNDED;
            } catch (Throwable ignored) {
                return DefaultMaterials.FOLIAGE_GROUNDED;
            }
        }
        if (block instanceof net.minecraft.block.BlockFlower
                || block instanceof net.minecraft.block.BlockTallGrass
                || block instanceof net.minecraft.block.BlockDeadBush
                || block instanceof net.minecraft.block.BlockSapling
                || block instanceof net.minecraft.block.BlockCrops
                || block instanceof net.minecraft.block.BlockStem) {
            return DefaultMaterials.FOLIAGE_GROUNDED;
        }
        return DefaultMaterials.FOLIAGE_NONE;
    }

    /**
     * Classifies a block to its Complementary {@code block.properties} id (exposed to the shader as {@code mc_Entity.x}),
     * so the pack identifies foliage (waving) AND light sources (colored-lighting voxelization in the shadow pass). Only
     * the common 1.12.2 light emitters are mapped here (the ids come from the pack's {@code lightVoxelization.glsl}
     * GetVoxelIDs); foliage reuses {@link #foliageType}. 0 = unidentified (a plain block — not voxelized as a light).
     */
    /**
     * The pack's own block.properties mapping (id per block state), installed by IrisPipeline when a pack loads; null
     * when the pack ships none. It supersedes the hardcoded table below, which only ever covered light sources + foliage
     * — every other block resolved to 0, leaving Complementary's IPBR with no material to work from (no gloss on
     * obsidian/ice/metal, smoothnessD = 0, and hence no deferred reflection on solids at all).
     */
    private static volatile com.yumelium.yumelium.shaders.pack.BlockIdMapper blockIdMapper;

    public static void setBlockIdMapper(com.yumelium.yumelium.shaders.pack.BlockIdMapper mapper) {
        blockIdMapper = mapper;
    }

    /** @return the active block.properties mapper (also resolves {@code blockEntityId} for TESRs), or null. */
    public static com.yumelium.yumelium.shaders.pack.BlockIdMapper getBlockIdMapper() {
        return blockIdMapper;
    }

    /**
     * Soul-fire emulation: 1.12.2 has no {@code soul_fire} block (it arrived in 1.16), so fire burning on soul sand is
     * plain {@code minecraft:fire} and the shader has no way to tell it apart. The mesher does know — it has the
     * position — so it hands those quads the pack's {@code soul_fire} id and the pack's whole soul-fire path lights up:
     * {@code lightVoxelization.glsl} maps that id to mat 27 → {@code soulFireSpecialColor}, giving real coloured light.
     *
     * <p>Cosmetic only: the fire still behaves like ordinary fire. And the pack does NOT tint soul fire — on 1.16+ the
     * vanilla texture is already blue — so the blue look itself is added by {@code IrisPipeline.injectSoulFireTint}.
     * Both halves are needed; without the tint this would be orange flames casting blue light.</p>
     */
    // Temporarily disabled together with connected textures (see ClientProxy): both depend on the CTM tile set, whose
    // frames sit on the very pixel edge and bleed into a faint seam at mipmap distances. Re-enable once the tile set is
    // regenerated with a transparent margin. The whole implementation is kept intact behind this flag.
    private static final boolean SOUL_FIRE_EMULATION = false;

    /**
     * @return true when this block should render as soul fire — fire burning on soul sand, AND the textures to draw it
     * with actually present.
     *
     * <p>The single source of truth for both halves of the emulation: {@link #blockId} uses it to hand the quads the
     * shader pack's {@code soul_fire} material (which is what makes the LIGHT blue), and the mesher uses it to swap in
     * the soul-fire sprites (which is what makes the FLAME blue). They must agree, so the texture check belongs HERE
     * rather than only at the swap — leaving it out assigned the soul material with no pack installed, and an orange
     * flame casting blue light is worse than plain fire. Either the whole emulation engages or none of it does.</p>
     */
    private static boolean isSoulFire(BlockRenderContext ctx) {
        return SOUL_FIRE_EMULATION
                && com.yumelium.yumelium.client.soulfire.SoulFireTextures.isAvailable()
                && ctx.state().getBlock() == net.minecraft.init.Blocks.FIRE
                && ctx.world().getBlockState(ctx.pos().down()).getBlock() == net.minecraft.init.Blocks.SOUL_SAND;
    }

    private static int blockId(BlockRenderContext ctx) {
        IBlockState state = ctx.state();
        // Prefer the pack's real table; fall back to the hardcoded ids for anything it doesn't assign (and for packs
        // that ship no block.properties at all).
        com.yumelium.yumelium.shaders.pack.BlockIdMapper mapper = blockIdMapper;
        if (mapper != null) {
            if (isSoulFire(ctx)) {
                int soulFireId = mapper.idForPackName("soul_fire"); // 0 if the pack has no such material → no emulation
                if (soulFireId != 0) {
                    return soulFireId;
                }
            }
            int id = mapper.idFor(state);
            if (id != 0) {
                return id;
            }
        }
        net.minecraft.block.Block b = state.getBlock();
        // Light sources (ids must match the pack's GetVoxelIDs / block.properties).
        if (b == net.minecraft.init.Blocks.TORCH) return 10496;
        if (b == net.minecraft.init.Blocks.REDSTONE_TORCH) return 10604;      // lit redstone torch
        if (b == net.minecraft.init.Blocks.GLOWSTONE) return 10412;
        if (b == net.minecraft.init.Blocks.SEA_LANTERN) return 10448;
        if (b == net.minecraft.init.Blocks.LIT_PUMPKIN) return 10396;          // jack o'lantern
        if (b == net.minecraft.init.Blocks.LAVA || b == net.minecraft.init.Blocks.FLOWING_LAVA) return 10068;
        if (b == net.minecraft.init.Blocks.FIRE) return 10072;
        if (b == net.minecraft.init.Blocks.LIT_REDSTONE_LAMP) return 10640;
        if (b == net.minecraft.init.Blocks.END_ROD) return 10500;
        if (b == net.minecraft.init.Blocks.MAGMA) return 10452;
        if (b == net.minecraft.init.Blocks.PORTAL) return 30020;              // nether portal
        if (b == net.minecraft.init.Blocks.BEACON) return 32016;
        if (b == net.minecraft.init.Blocks.LIT_FURNACE) return 10516;
        if (b == net.minecraft.init.Blocks.LIT_REDSTONE_ORE) return 10616;
        if (b == net.minecraft.init.Blocks.END_PORTAL) return 10556;
        // Foliage (waving) — map the category to its block.properties id.
        switch (foliageType(state)) {
            case DefaultMaterials.FOLIAGE_GROUNDED: return 10005;
            case DefaultMaterials.FOLIAGE_LEAVES:   return 10009;
            case DefaultMaterials.FOLIAGE_UPPER:    return 10021;
            default:                                return 0;
        }
        // NOTE: opaque solid blocks intentionally return 0 (not a generic "10000"). mc_Entity feeds BOTH the colored-light
        // voxelization (UpdateVoxelMap → occluder) AND the WSR scene voxelization (UpdateSceneVoxelMap → matM stored in
        // wsr_img → terrainIPBR material handling on reflection read). A fake 10000 would make walls occlude coloured light,
        // but it pollutes the WSR material channel for every solid block (mat=10000 ≠ its real block.properties id), which
        // risks the reflection material handling. Giving solids their REAL block.properties ids (so both systems agree) is
        // the correct fix and is deferred; until then, 0 keeps WSR reflections correct at the cost of some indoor light leak.
    }

    private boolean isFaceVisible(BlockRenderContext ctx, EnumFacing face) {
        // Connected glass: a glass face that meets another glass block or an opaque block is invisible and would
        // z-fight (translucent, no depth-write) with the neighbour — cull it for a seamless connected-glass look.
        if (CtmRegistry.instance().isActive() && isGlassBlock(ctx.state().getBlock())) {
            this.ctmScratch.setPos(ctx.pos().getX() + face.getXOffset(),
                    ctx.pos().getY() + face.getYOffset(), ctx.pos().getZ() + face.getZOffset());
            net.minecraft.block.state.IBlockState neighbor = ctx.world().getBlockState(this.ctmScratch);
            if (isGlassBlock(neighbor.getBlock()) || neighbor.isOpaqueCube()) {
                return false;
            }
        }
        // Leaves inner-face culling (Cull-Leaves / OptiFine "Trees: Smart" style), active when Leaves Quality is
        // FAST: a leaf face touching another leaf block is interior canopy detail — skip it. Both blocks drop their
        // shared faces, so the canopy interior vanishes from the mesh; camera pass, shadow pass and Nvidium all
        // share that mesh, so all three get the quad reduction (~40-70% fewer leaf quads in dense forests).
        // Any-leaves vs any-leaves, like vanilla FAST across the BlockOldLeaf variants and Embeddium/Cull Leaves;
        // instanceof BlockLeaves covers modded subclasses (Betweenlands). isFullCube on BOTH sides guards exotic
        // non-full-cube leaves subclasses from opening visible gaps.
        if (this.cullLeavesInnerFaces
                && ctx.state().getBlock() instanceof net.minecraft.block.BlockLeaves
                && ctx.state().isFullCube()) {
            this.ctmScratch.setPos(ctx.pos().getX() + face.getXOffset(),
                    ctx.pos().getY() + face.getYOffset(), ctx.pos().getZ() + face.getZOffset());
            net.minecraft.block.state.IBlockState neighbor = ctx.world().getBlockState(this.ctmScratch);
            if (neighbor.getBlock() instanceof net.minecraft.block.BlockLeaves && neighbor.isFullCube()) {
                return false;
            }
        }
        return this.occlusionCache.shouldDrawSide(ctx.state(), ctx.localSlice(), ctx.pos(), face);
    }

    private static boolean isGlassBlock(net.minecraft.block.Block block) {
        return block == net.minecraft.init.Blocks.GLASS || block == net.minecraft.init.Blocks.STAINED_GLASS;
    }

    private static int computeLightFlagMask(BakedQuad quad) {
        return quad.shouldApplyDiffuseLighting() ? 2 : 0;
    }

    private static boolean checkQuadsHaveSameLightingConfig(List<BakedQuad> quads) {
        int quadsSize = quads.size();

        if (quadsSize >= 2) {
            int flagMask = -1;
            for (int i = 0; i < quadsSize; i++) {
                int newFlag = computeLightFlagMask(quads.get(i));
                if (flagMask == -1) {
                    flagMask = newFlag;
                } else if (newFlag != flagMask) {
                    return false;
                }
            }
        }

        return true;
    }

    private void renderQuadList(BlockRenderContext ctx, Material material, LightPipeline lighter, ColorProvider<IBlockState> colorizer, Vec3d offset,
                                ChunkModelBuilder builder, List<BakedQuad> quads, EnumFacing cullFace, BetterGrass.TopFace grassTop) {
        if (!checkQuadsHaveSameLightingConfig(quads)) {
            this.useReorienting = false;
        }

        CtmRegistry ctm = CtmRegistry.instance();
        boolean ctmActive = ctm.isActive();
        // Once per block, not per quad: fire has several quads and the world lookup is the expensive part.
        boolean soulFire = isSoulFire(ctx);
        // Connected glass panes: cull the pane's top/bottom edge faces (glass_pane_top) when another pane of the same
        // block is directly above/below, so a vertical stack of panes is seamless instead of showing an edge line.
        boolean paneEdgeCull = ctmActive && CtmRegistry.isPane(ctx.state().getBlock());

        for (int i = 0, quadsSize = quads.size(); i < quadsSize; i++) {
            BakedQuadView quad = (BakedQuadView) quads.get(i);

            if (paneEdgeCull) {
                EnumFacing qf = quad.getLightFace();
                if (qf != null && qf.getAxis() == EnumFacing.Axis.Y) {
                    this.ctmScratch.setPos(ctx.pos().getX(), ctx.pos().getY() + qf.getYOffset(), ctx.pos().getZ());
                    if (ctx.world().getBlockState(this.ctmScratch).getBlock() == ctx.state().getBlock()) {
                        continue;
                    }
                }
            }

            if (grassTop != null) {
                quad = new BetterGrassQuad(quad, grassTop.sprite, grassTop.tintIndex);
            }

            // Soul fire: re-texture the flame when it burns on soul sand. 1.12.2 has no soul_fire block, so the mesher's
            // knowledge of the POSITION is the only thing that can tell it from ordinary fire. Reuses CtmQuad — the UV
            // remap is exactly the connected-textures one, only the reason for swapping differs. Inert unless a resource
            // pack supplied the textures (see SoulFireTextures).
            if (soulFire && quad.getSprite() != null) {
                TextureAtlasSprite soulSprite = com.yumelium.yumelium.client.soulfire.SoulFireTextures
                        .replacementFor(quad.getSprite().getIconName());
                if (soulSprite != null) {
                    quad = new CtmQuad(quad, soulSprite);
                }
            }

            if (ctmActive && quad.getSprite() != null) {
                // Full blocks have a cull face; non-cube quads (glass panes) don't, so fall back to the quad's facing.
                EnumFacing ctmFace = cullFace != null ? cullFace : quad.getLightFace();
                TextureAtlasSprite tile = ctm.tileFor(ctx.state(), quad.getSprite().getIconName(), ctmFace,
                        ctx.world(), ctx.pos().getX(), ctx.pos().getY(), ctx.pos().getZ(), this.ctmScratch);
                if (tile != null) {
                    // Panes need local-position UV (their arms map only half the sprite); blocks remap the UV directly.
                    quad = CtmRegistry.isPane(ctx.state().getBlock()) && ctmFace != null
                            ? new com.yumelium.yumelium.client.ctm.PaneCtmQuad(quad, tile, ctmFace)
                            : new CtmQuad(quad, tile);
                }
            }

            final var lightData = this.getVertexLight(ctx, quad.hasAmbientOcclusion() ? lighter : this.lighters.getLighter(LightMode.FLAT), cullFace, quad);
            final var vertexColors = this.getVertexColors(ctx, colorizer, quad);

            this.writeGeometry(ctx, builder, offset, material, quad, vertexColors, lightData);

            TextureAtlasSprite sprite = quad.getSprite();

            if (sprite != null) {
                builder.addSprite(sprite);
            }
        }
    }

    private QuadLightData getVertexLight(BlockRenderContext ctx, LightPipeline lighter, EnumFacing cullFace, BakedQuadView quad) {
        QuadLightData light = this.quadLightData;
        lighter.calculate(quad, ctx.pos(), light, cullFace, quad.getLightFace(), quad.hasShade());

        return light;
    }

    private int[] getVertexColors(BlockRenderContext ctx, ColorProvider<IBlockState> colorProvider, BakedQuadView quad) {
        final int[] vertexColors = this.quadColors;

        if (colorProvider != null && quad.hasColor()) {
            colorProvider.getColors(ctx.world(), ctx.pos(), ctx.state(), quad, vertexColors);
            // Force full alpha on all colors
            for (int i = 0; i < vertexColors.length; i++) {
                vertexColors[i] |= 0xFF000000;
            }
        } else {
            Arrays.fill(vertexColors, 0xFFFFFFFF);
        }

        return vertexColors;
    }

    private void writeGeometry(BlockRenderContext ctx,
                               ChunkModelBuilder builder,
                               Vec3d offset,
                               Material material,
                               BakedQuadView quad,
                               int[] colors,
                               QuadLightData light) {
        ModelQuadOrientation orientation = this.useReorienting ? ModelQuadOrientation.orientByBrightness(light.br, light.lm) : ModelQuadOrientation.NORMAL;
        var vertices = this.vertices;

        ModelQuadFacing normalFace = quad.getNormalFace();

        // Iris shader mode: a packed geometric face normal (NormI8) for the whole quad. Ignored by the compact/vanilla
        // encoders; consumed only by the normal-extended format (a_Normal). Computed from the quad plane (Newell) so it
        // is correct for angled geometry too (cross-plants, stairs, fences), not just axis-aligned cube faces. Cross-plant
        // foliage keeps its real (horizontal) normal, matching real Iris: the "grass black from certain angles" turned out
        // to be atlas-mipmap black fringes (fixed by the per-material mip bias in the terrain transformer), NOT the sun
        // NdotL, so the earlier up-normal substitution was unnecessary and had side effects (grass forming rain puddles +
        // reflecting like a horizontal mirror, since both key on NdotU = dot(normal, up)). mc_Entity=10005 still drives the
        // pack's foliage waving + subsurface.
        int packedNormal = ModelQuadUtil.calculateNormal(quad);

        // Quad-centre (sprite mid) texture coordinate, the same for all 4 vertices. The Iris terrain shader exposes it as
        // mc_midTexCoord so the pack's foliage waving can tell top (V above mid → sways) from bottom (anchored) vertices.
        float midU = 0.25f * (quad.getTexU(0) + quad.getTexU(1) + quad.getTexU(2) + quad.getTexU(3));
        float midV = 0.25f * (quad.getTexV(0) + quad.getTexV(1) + quad.getTexV(2) + quad.getTexV(3));

        // DIAGNOSTIC: name the blocks whose quads produce an absurd |texCoord - mid| ("textureRad"). The pack derives a
        // reflected face's sprite bounds from that radius; a 16px sprite on the 1024x512 atlas should give ~0.0078, but the
        // WSR SSBO readback showed radii up to 0.4531, and the ray trace tags those faces as sampling the atlas at ~(0,0)
        // (transparent → hit rejected → getWSR misses). Logs each offending block state once.
        if (DIAG_MIDTEX) {
            float rad = 0.0f;
            for (int i = 0; i < 4; i++) {
                rad = Math.max(rad, Math.max(Math.abs(quad.getTexU(i) - midU), Math.abs(quad.getTexV(i) - midV)));
            }
            // Track the max rad over ALL quads too, so a silent "0 offenders" can be told apart from "this code never
            // ran" (a cached chunk mesh would produce no quads at all, and an empty report would look like a clean bill).
            DIAG_MIDTEX_QUADS.incrementAndGet();
            DIAG_MIDTEX_MAXRAD.accumulateAndGet((int) (rad * 10000.0f), Math::max);
            if (rad > 0.05f && DIAG_MIDTEX_SEEN.add(String.valueOf(ctx.state()))) {
                SodiumClientMod.logger().info(String.format(
                        "[midTex DIAG] rad=%.4f state=%s sprite=%s | U=[%.4f %.4f %.4f %.4f] V=[%.4f %.4f %.4f %.4f]"
                                + " mid=(%.4f,%.4f)",
                        rad, ctx.state(), quad.getSprite() == null ? "null" : quad.getSprite().getIconName(),
                        quad.getTexU(0), quad.getTexU(1), quad.getTexU(2), quad.getTexU(3),
                        quad.getTexV(0), quad.getTexV(1), quad.getTexV(2), quad.getTexV(3), midU, midV));
            }
        }

        // Complementary block id (mc_Entity.x) for foliage waving + light-source voxelization; same on all 4 vertices.
        int bid = blockId(ctx);

        // UV-aligned tangent (+ handedness) for the pack's TBN basis (nether-portal parallax / PBR); same on all 4 vertices.
        int packedTangent = computeTangent(quad);

        for (int dstIndex = 0; dstIndex < 4; dstIndex++) {
            int srcIndex = orientation.getVertexIndex(dstIndex);

            var out = vertices[dstIndex];
            out.x = ctx.origin().x() + quad.getX(srcIndex) + (float) offset.x;
            out.y = ctx.origin().y() + quad.getY(srcIndex) + (float) offset.y;
            out.z = ctx.origin().z() + quad.getZ(srcIndex) + (float) offset.z;

            out.color = colorEncoder.writeColor(ModelQuadUtil.mixARGBColors(colors[srcIndex], quad.getColor(srcIndex)), light.br[srcIndex]);

            out.u = quad.getTexU(srcIndex);
            out.v = quad.getTexV(srcIndex);

            out.light = ModelQuadUtil.mergeBakedLight(quad.getLight(srcIndex), light.lm[srcIndex]);

            out.normal = packedNormal;
            out.midU = midU;
            out.midV = midV;
            out.blockId = bid;
            out.tangent = packedTangent;
            // at_midBlock: offset from THIS vertex to the block centre (local 0.5), ×64, as 3 signed bytes. The render
            // offset cancels (it shifts both the vertex and the centre equally), so use the raw model-local position.
            int mbx = clampByte(Math.round((0.5f - quad.getX(srcIndex)) * 64.0f));
            int mby = clampByte(Math.round((0.5f - quad.getY(srcIndex)) * 64.0f));
            int mbz = clampByte(Math.round((0.5f - quad.getZ(srcIndex)) * 64.0f));
            out.midBlock = (mbx & 0xFF) | ((mby & 0xFF) << 8) | ((mbz & 0xFF) << 16);
        }

        var vertexBuffer = builder.getVertexBuffer(normalFace);
        vertexBuffer.push(vertices, material);
    }

    /**
     * @return the quad's UV-aligned tangent packed as NormI8 (signed bytes X|Y|Z|W, W = handedness ±1), computed the
     * standard way from two edges + their UV deltas. Exposed to the shader as {@code at_tangent} for the pack's TBN basis
     * (nether-portal parallax, generated normals, POM, custom PBR). Degenerate UVs fall back to a normal-derived tangent.
     *
     * <p>Takes a {@link ModelQuadView} (not just BakedQuadView) so FluidRenderer can share it: water needs a real
     * per-quad tangent too, since gbuffers_water builds its TBN from {@code cross(at_tangent.xyz, gl_Normal.xyz) *
     * at_tangent.w} and feeds it to viewVector → the water's parallax + ripple-normal lookup.</p>
     */
    public static int computeTangent(me.jellysquid.mods.sodium.client.model.quad.ModelQuadView quad) {
        float dx1 = quad.getX(1) - quad.getX(0), dy1 = quad.getY(1) - quad.getY(0), dz1 = quad.getZ(1) - quad.getZ(0);
        float dx2 = quad.getX(2) - quad.getX(0), dy2 = quad.getY(2) - quad.getY(0), dz2 = quad.getZ(2) - quad.getZ(0);
        float du1 = quad.getTexU(1) - quad.getTexU(0), dv1 = quad.getTexV(1) - quad.getTexV(0);
        float du2 = quad.getTexU(2) - quad.getTexU(0), dv2 = quad.getTexV(2) - quad.getTexV(0);
        float det = du1 * dv2 - du2 * dv1;
        float tx, ty, tz, w;
        if (Math.abs(det) < 1.0e-9f) {
            // Degenerate UV mapping — fall back to a horizontal tangent perpendicular to the quad normal.
            int n = ModelQuadUtil.calculateNormal(quad);
            float nx = (byte) (n & 0xFF), ny = (byte) ((n >> 8) & 0xFF), nz = (byte) ((n >> 16) & 0xFF);
            if (Math.abs(ny) > 0.99f * 127f) { tx = 1f; ty = 0f; tz = 0f; }
            else { tx = nz; ty = 0f; tz = -nx; } // cross(normal, up)
            w = 1f;
        } else {
            float r = 1.0f / det;
            tx = (dv2 * dx1 - dv1 * dx2) * r;
            ty = (dv2 * dy1 - dv1 * dy2) * r;
            tz = (dv2 * dz1 - dv1 * dz2) * r;
            // Handedness for the pack's binormal = cross(at_tangent, normal) * at_tangent.w. Because that cross order is
            // the negative of the standard cross(normal, tangent), the pack wants w = -sign(det), not +sign(det).
            w = det < 0.0f ? 1.0f : -1.0f;
        }
        float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < 1.0e-9f) { tx = 1f; ty = 0f; tz = 0f; len = 1f; }
        tx /= len; ty /= len; tz /= len;
        int px = Math.round(tx * 127f) & 0xFF;
        int py = Math.round(ty * 127f) & 0xFF;
        int pz = Math.round(tz * 127f) & 0xFF;
        int pw = Math.round(w * 127f) & 0xFF;
        return px | (py << 8) | (pz << 16) | (pw << 24);
    }

    /** One-shot report of blocks whose quad UVs give an impossible sprite radius (see the use site). */
    private static final boolean DIAG_MIDTEX = false;
    private static final java.util.Set<String> DIAG_MIDTEX_SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Quads examined + the largest radius seen (×10000), so "no offenders" can be distinguished from "never ran". */
    public static final java.util.concurrent.atomic.AtomicLong DIAG_MIDTEX_QUADS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicInteger DIAG_MIDTEX_MAXRAD =
            new java.util.concurrent.atomic.AtomicInteger();

    static {
        // AtomicInteger has no accumulate(); wrap the max-update so the use site stays readable.
    }

    private static int clampByte(int v) {
        return v < -128 ? -128 : (v > 127 ? 127 : v);
    }

    private LightMode getLightingMode(IBlockState state, IBakedModel model, IBlockAccess world, BlockPos pos) {
        if (this.useAmbientOcclusion && model.isAmbientOcclusion(state) && state.getLightValue(world, pos) == 0) {
            return LightMode.SMOOTH;
        } else {
            return LightMode.FLAT;
        }
    }
}
