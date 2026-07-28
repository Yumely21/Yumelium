package me.jellysquid.mods.sodium.mixin.core.model.quad;

import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFlags;
import me.jellysquid.mods.sodium.client.util.ModelQuadUtil;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes vanilla's {@link BakedQuad} implement Embeddium's {@link BakedQuadView}, so the chunk mesher can read quad
 * geometry directly (see {@code BlockRenderer#renderQuadList} casting {@code (BakedQuadView) quads.get(i)}).
 *
 * <p>NOTE(yumelium): the 1.16.5 original ({@code BakedQuadMixin}) assumed the 8-int {@code DefaultVertexFormat.BLOCK}
 * layout (position, color, tex, lightmap, normal) with fixed {@link ModelQuadUtil} indices. In 1.12.2, baked block
 * quads use {@code DefaultVertexFormats.ITEM} (7 ints: position@0, color@3, tex@4, normal@6 — <b>no lightmap</b>), and
 * the target fields are renamed ({@code vertices→vertexData}, {@code direction→face}, {@code shade→applyDiffuseLighting}).
 * To stay robust across ITEM/BLOCK/modded formats we derive per-element int offsets from the quad's own
 * {@link VertexFormat} once at construction instead of using hardcoded indices. Quads without a lightmap element report
 * {@code getLight()==0}, which the light pipeline interprets as "use computed lighting".
 */
@Mixin(BakedQuad.class)
public abstract class BakedQuadMixin implements BakedQuadView {
    @Shadow
    @Final
    protected int[] vertexData;

    // ALL vertex reads must go through this METHOD, never the field above: Forge's UnpackedBakedQuad ("pipeline"
    // quads — e.g. the fluid submodels inside The Betweenlands' ModelCombined) packs its float data into the int[]
    // LAZILY inside getVertexData(). Reading the raw field on a never-packed quad returns zeros → degenerate
    // zero-area geometry that silently renders nothing (2026-07-29: invisible water surfaces at every BTL
    // plant-with-embedded-fluid cell). Plain BakedQuads just return the field, so this costs nothing there.
    @Shadow
    public abstract int[] getVertexData();

    @Shadow
    @Final
    protected int tintIndex;

    @Shadow
    @Final
    protected EnumFacing face; // This is really the light face, but we can't rename it.

    @Shadow
    @Final
    protected boolean applyDiffuseLighting;

    // getSprite is provided below from a @Unique capture of the constructor's sprite argument instead of shadowing
    // or overwriting the class's own method — because the method may not EXIST at mixin-apply time. Production
    // (Cleanroom 0.6.7 + FermiumASM squashBakedQuads, 2026-07-28) reaches mixin apply with BakedQuad stripped of
    // getSprite (proven: @Overwrite failed "not located in the target class"; the earlier AbstractMethodError at
    // BakedQuad.getSprite was this same absence surfacing through the unimplemented interface method). FermiumASM
    // could not be disabled from our side: its transformer lives in a second classloader copy where our reflective
    // flip and the sodium-port marker probe are both invisible (measured: our flip logged `was false` yet the queue
    // line still printed). A PLAIN (unannotated) mixin method is the one shape that works in every environment:
    // target has getSprite (dev, classic loaders) → implicit overwrite; target lacks it (squashed prod) → added.
    @Unique
    private TextureAtlasSprite embeddium$sprite;

    public TextureAtlasSprite getSprite() {
        return this.embeddium$sprite;
    }

    // Per-format int offsets, resolved once from the quad's VertexFormat at construction. -1 means the element is absent.
    @Unique
    private int embeddium$stride;
    @Unique
    private int embeddium$colorOffset;
    @Unique
    private int embeddium$texOffset;
    @Unique
    private int embeddium$lightOffset;
    @Unique
    private int embeddium$normalOffset;

    @Unique
    private int embeddium$flags;

    @Unique
    private int embeddium$normal;

    @Unique
    private ModelQuadFacing embeddium$normalFace;

    @Inject(
            method = "<init>([IILnet/minecraft/util/EnumFacing;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZLnet/minecraft/client/renderer/vertex/VertexFormat;)V",
            at = @At("RETURN")
    )
    private void embeddium$init(int[] vertexData, int tintIndex, EnumFacing face, TextureAtlasSprite sprite,
                                boolean applyDiffuseLighting, VertexFormat format, CallbackInfo ci) {
        this.embeddium$sprite = sprite;
        // VertexFormat offsets are in bytes; every element in these formats is 4-byte aligned, so /4 gives the int index.
        this.embeddium$stride = format.getIntegerSize();
        this.embeddium$colorOffset = format.hasColor() ? format.getColorOffset() / 4 : -1;
        this.embeddium$texOffset = format.hasUvOffset(0) ? format.getUvOffsetById(0) / 4 : -1;
        this.embeddium$lightOffset = format.hasUvOffset(1) ? format.getUvOffsetById(1) / 4 : -1;
        this.embeddium$normalOffset = format.hasNormal() ? format.getNormalOffset() / 4 : -1;
    }

    @Unique
    private int embeddium$base(int idx) {
        return idx * this.embeddium$stride;
    }

    @Override
    public float getX(int idx) {
        // Position is always the first element (byte/int offset 0) in these formats.
        return Float.intBitsToFloat(this.getVertexData()[embeddium$base(idx)]);
    }

    @Override
    public float getY(int idx) {
        return Float.intBitsToFloat(this.getVertexData()[embeddium$base(idx) + 1]);
    }

    @Override
    public float getZ(int idx) {
        return Float.intBitsToFloat(this.getVertexData()[embeddium$base(idx) + 2]);
    }

    @Override
    public int getColor(int idx) {
        int offset = this.embeddium$colorOffset;
        return offset == -1 ? 0xFFFFFFFF : this.getVertexData()[embeddium$base(idx) + offset];
    }

    @Override
    public float getTexU(int idx) {
        int offset = this.embeddium$texOffset;
        return offset == -1 ? 0.0F : Float.intBitsToFloat(this.getVertexData()[embeddium$base(idx) + offset]);
    }

    @Override
    public float getTexV(int idx) {
        int offset = this.embeddium$texOffset;
        return offset == -1 ? 0.0F : Float.intBitsToFloat(this.getVertexData()[embeddium$base(idx) + offset + 1]);
    }

    @Override
    public int getLight(int idx) {
        // 1.12.2 block quads (ITEM format) carry no lightmap; 0 tells the light pipeline to use computed lighting.
        int offset = this.embeddium$lightOffset;
        return offset == -1 ? 0 : this.getVertexData()[embeddium$base(idx) + offset];
    }

    @Override
    public int getForgeNormal(int idx) {
        int offset = this.embeddium$normalOffset;
        return offset == -1 ? 0 : this.getVertexData()[embeddium$base(idx) + offset];
    }

    @Override
    public int getFlags() {
        int f = this.embeddium$flags;
        if ((f & ModelQuadFlags.IS_POPULATED) == 0) {
            this.embeddium$flags = f = (f | ModelQuadFlags.getQuadFlags(this, this.face));
        }
        return f;
    }

    @Override
    public void setFlags(int flags) {
        this.embeddium$flags = flags;
    }

    @Override
    public int getColorIndex() {
        return this.tintIndex;
    }

    @Override
    public ModelQuadFacing getNormalFace() {
        ModelQuadFacing normalFace = this.embeddium$normalFace;
        if (normalFace == null) {
            this.embeddium$normalFace = normalFace = ModelQuadUtil.findNormalFace(getComputedFaceNormal());
        }
        return normalFace;
    }

    @Override
    public int getComputedFaceNormal() {
        int n = this.embeddium$normal;
        if (n == 0) {
            this.embeddium$normal = n = ModelQuadUtil.calculateNormal(this);
        }
        return n;
    }

    @Override
    public EnumFacing getLightFace() {
        return this.face;
    }

    @Override
    public boolean hasShade() {
        return this.applyDiffuseLighting;
    }
}
