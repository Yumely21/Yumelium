package me.jellysquid.mods.sodium.client.render.chunk;

import org.joml.Matrix4fc;

/**
 * Holds the projection and model-view matrices used to render terrain.
 *
 * <p>NOTE(yumelium): upstream had a {@code from(PoseStack)} factory (1.16.5). 1.12.2 has no
 * {@code PoseStack} — the root renderer constructs this directly from the GL matrices
 * (see {@code GameRendererContext} / the ported chunk renderer). Kept as a plain record.
 */
public record ChunkRenderMatrices(Matrix4fc projection, Matrix4fc modelView) {
}
