package me.jellysquid.mods.sodium.client.render.viewport;

import me.jellysquid.mods.sodium.client.render.viewport.frustum.Frustum;
import net.minecraft.util.math.BlockPos;
import me.jellysquid.mods.sodium.client.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.tileentity.TileEntity;
import org.joml.Vector3d;

public final class Viewport {
    private final Frustum frustum;
    private final CameraTransform transform;

    private final ChunkSectionPos chunkCoords;
    private final BlockPos blockCoords;

    public Viewport(Frustum frustum, Vector3d position) {
        this.frustum = frustum;
        this.transform = new CameraTransform(position.x, position.y, position.z);

        this.chunkCoords = ChunkSectionPos.from(
                MathHelper.floor(position.x) >> 4,
                MathHelper.floor(position.y) >> 4,
                MathHelper.floor(position.z) >> 4
        );

        this.blockCoords = new BlockPos(position.x, position.y, position.z);
    }

    public boolean isBoxVisible(AxisAlignedBB box) {
        if (box.equals(TileEntity.INFINITE_EXTENT_AABB)) {
            return true;
        }

        return this.frustum.testAab(
                (float)(box.minX - this.transform.intX) - this.transform.fracX,
                (float)(box.minY - this.transform.intY) - this.transform.fracY,
                (float)(box.minZ - this.transform.intZ) - this.transform.fracZ,
                (float)(box.maxX - this.transform.intX) - this.transform.fracX,
                (float)(box.maxY - this.transform.intY) - this.transform.fracY,
                (float)(box.maxZ - this.transform.intZ) - this.transform.fracZ
        );
    }

    public boolean isBoxVisible(int intOriginX, int intOriginY, int intOriginZ, float floatSize) {
        return isBoxVisible(intOriginX, intOriginY, intOriginZ, floatSize, floatSize, floatSize);
    }

    public boolean isBoxVisible(int intOriginX, int intOriginY, int intOriginZ, float floatSizeX, float floatSizeY, float floatSizeZ) {
        float floatOriginX = (intOriginX - this.transform.intX) - this.transform.fracX;
        float floatOriginY = (intOriginY - this.transform.intY) - this.transform.fracY;
        float floatOriginZ = (intOriginZ - this.transform.intZ) - this.transform.fracZ;

        return this.frustum.testAab(
                floatOriginX - floatSizeX,
                floatOriginY - floatSizeY,
                floatOriginZ - floatSizeZ,

                floatOriginX + floatSizeX,
                floatOriginY + floatSizeY,
                floatOriginZ + floatSizeZ
        );
    }

    public CameraTransform getTransform() {
        return this.transform;
    }

    public ChunkSectionPos getChunkCoord() {
        return this.chunkCoords;
    }

    public BlockPos getBlockCoord() {
        return this.blockCoords;
    }
}
