package me.jellysquid.mods.sodium.client.render.chunk.terrain.material;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.MaterialParameters;

/**
 * A material provides the full configuration about how a geometry element should render. It corresponds to the vanilla
 * RenderType configured for a block. Material configuration is encoded alongside the rest of the vertex data, which
 * allows for multiple vanilla RenderTypes to be consolidated into a single terrain render pass on the CPU for greater
 * efficiency. The material configuration is recovered on the GPU within the render pass.
 */
public class Material {
    public final TerrainRenderPass pass;
    public final int packed;

    public final AlphaCutoffParameter alphaCutoff;
    public final boolean mipped;
    public final boolean desaturate;
    /** Foliage category (0 none, 1 grounded, 2 leaves, 3 upper) encoded into the vertex so the Iris terrain shader can
     * synthesize {@code mc_Entity} for native grass/leaf identification + waving. */
    public final int foliage;

    /**
     * Constructs a new Material.
     * @param pass the {@link TerrainRenderPass} to use for the base configuration
     * @param alphaCutoff the alpha level below which fragments should be discarded (only respected if
     *                    {@link TerrainRenderPass#supportsFragmentDiscard()} is true for the given pass)
     * @param mipped whether mipmapping should be enabled on geometry rendered with this material
     */
    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped) {
        this(pass, alphaCutoff, mipped, false);
    }

    /**
     * @param desaturate whether the sampled texture should be desaturated to grayscale before the vertex color is
     *                   applied. Used by water so its blue texture can be tinted to arbitrary biome colours the 1.13 way.
     */
    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped, boolean desaturate) {
        this(pass, alphaCutoff, mipped, desaturate, 0);
    }

    /**
     * @param foliage the foliage category (0 none, 1 grounded, 2 leaves, 3 upper) — encoded into the vertex material bits
     *                so the transformed Iris terrain shader can emit {@code mc_Entity} for the shader pack.
     */
    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped, boolean desaturate, int foliage) {
        this.pass = pass;
        this.packed = MaterialParameters.pack(alphaCutoff, mipped, desaturate, foliage);

        this.alphaCutoff = alphaCutoff;
        this.mipped = mipped;
        this.desaturate = desaturate;
        this.foliage = foliage;
    }

    /**
     * {@return the packed representation of this Material to be encoded in vertex data}
     */
    public int bits() {
        return this.packed;
    }
}
