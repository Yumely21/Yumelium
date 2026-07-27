package me.jellysquid.mods.lithium.mixin.entity.collisions;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Lithium entity.collisions (ported to 1.12.2, after ChunkAwareBlockCollisionSweeper): rewrites the PRIVATE block
 * collision core {@code World.getCollisionBoxes(Entity, AABB, boolean, List)} — the funnel behind every
 * {@code Entity.move} step, {@code collidesWithAnyBlock} and {@code checkBlockCollision} — to be CHUNK-AWARE:
 *
 * <ul>
 * <li>the chunk is fetched ONCE per column run instead of once per block (vanilla's per-block
 *     {@code world.getBlockState} does a full provider/chunk-map lookup for every block in the swept volume);</li>
 * <li>all-air chunk sections (null or {@code blockRefCount == 0}) skip their whole 16-block y-span at once — an
 *     entity falling/jumping/levitating through open air (or an END island's void neighbourhood) sweeps almost
 *     nothing.</li>
 * </ul>
 *
 * The FORGE-PATCHED semantics are preserved exactly (verified against the patched bytecode, not vanilla source):
 * the {@code gatherCollisionBoxes} event fires once up-front and then after every added box in "collides" mode
 * (early-true exit); the world-border wall substitutes STONE for out-of-border columns when the entity is inside
 * the border; {@code setOutsideBorder} side effect kept; corner columns of the +1 ring skipped and edge columns
 * skip their topmost y; unloaded columns ({@code isBlockLoaded} at y=64, per column like vanilla) contribute
 * nothing; iteration order (x → z → y ascending) and therefore the output list order is IDENTICAL to vanilla, so
 * downstream axis-resolution in {@code Entity.move} sees exactly the same boxes in the same order.
 */
@Mixin(World.class)
public abstract class MixinWorldCollisions {
    @Shadow
    public abstract WorldBorder getWorldBorder();

    @Shadow
    public abstract boolean isInsideWorldBorder(Entity entityIn);

    @Shadow
    public abstract boolean isBlockLoaded(BlockPos pos);

    @Shadow
    public abstract Chunk getChunk(int chunkX, int chunkZ);

    /**
     * @author jellysquid3 (Lithium's chunk-aware sweep), ported to the 1.12.2 Forge core
     * @reason chunk-cached block access + empty-section skipping in the hottest collision path
     */
    @Overwrite
    private boolean getCollisionBoxes(@Nullable Entity entityIn, AxisAlignedBB aabb, boolean flagMode, @Nullable List<AxisAlignedBB> outList) {
        int minX = MathHelper.floor(aabb.minX) - 1;
        int maxX = MathHelper.ceil(aabb.maxX) + 1;
        int minY = MathHelper.floor(aabb.minY) - 1;
        int maxY = MathHelper.ceil(aabb.maxY) + 1;
        int minZ = MathHelper.floor(aabb.minZ) - 1;
        int maxZ = MathHelper.ceil(aabb.maxZ) + 1;
        WorldBorder border = this.getWorldBorder();
        boolean entityOutsideBorderFlag = entityIn != null && entityIn.isOutsideBorder();
        boolean entityInsideBorder = entityIn != null && this.isInsideWorldBorder(entityIn);
        IBlockState stone = Blocks.STONE.getDefaultState();
        World self = (World) (Object) this;

        // Forge: in "collides" mode the event fires up-front so handlers can inject boxes before any scan.
        if (flagMode && !ForgeEventFactory.gatherCollisionBoxes(self, entityIn, aabb, outList)) {
            return true;
        }

        BlockPos.PooledMutableBlockPos pos = BlockPos.PooledMutableBlockPos.retain();
        // One-entry chunk cache: z is the inner column loop, so consecutive columns share a chunk up to 16 times.
        Chunk cachedChunk = null;
        int cachedChunkX = Integer.MIN_VALUE, cachedChunkZ = Integer.MIN_VALUE;

        try {
            for (int x = minX; x < maxX; ++x) {
                boolean edgeX = x == minX || x == maxX - 1;

                for (int z = minZ; z < maxZ; ++z) {
                    boolean edgeZ = z == minZ || z == maxZ - 1;
                    // Corner columns of the +1 ring are never scanned (vanilla), nor are unloaded columns.
                    if ((edgeX && edgeZ) || !this.isBlockLoaded(pos.setPos(x, 64, z))) {
                        continue;
                    }

                    // Vanilla runs these once per block; both are per-COLUMN constants (the ±30M guard and the
                    // border test only involve x/z, and the y range below is never empty), so hoist them.
                    if (flagMode) {
                        if (x < -30000000 || x >= 30000000 || z < -30000000 || z >= 30000000) {
                            return true;
                        }
                    } else if (entityIn != null && entityOutsideBorderFlag == entityInsideBorder) {
                        entityIn.setOutsideBorder(!entityInsideBorder);
                    }

                    // Edge columns skip their topmost y (vanilla's `!flag2 && !flag3 || i2 != l - 1`).
                    int yEnd = (edgeX || edgeZ) ? maxY - 1 : maxY;

                    // World-border wall: with the entity INSIDE the border, every block of an out-of-border column
                    // reads as STONE (never in "collides" mode — vanilla only substitutes when !flagMode).
                    if (!flagMode && entityInsideBorder && !border.contains(pos.setPos(x, 64, z))) {
                        for (int y = minY; y < yEnd; ++y) {
                            pos.setPos(x, y, z);
                            stone.addCollisionBoxToList(self, pos, aabb, outList, entityIn, false);
                        }
                        continue;
                    }

                    int chunkX = x >> 4, chunkZ = z >> 4;
                    if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                        cachedChunk = this.getChunk(chunkX, chunkZ);
                        cachedChunkX = chunkX;
                        cachedChunkZ = chunkZ;
                    }
                    ExtendedBlockStorage[] sections = cachedChunk.getBlockStorageArray();

                    for (int y = Math.max(minY, 0); y < yEnd; ++y) {
                        int sectionIndex = y >> 4;
                        if (sectionIndex >= sections.length) {
                            break; // above the world: air only from here up
                        }
                        ExtendedBlockStorage section = sections[sectionIndex];
                        if (section == Chunk.NULL_BLOCK_STORAGE || section.isEmpty()) {
                            y = (sectionIndex << 4) + 15; // whole section is air — nothing can add a box
                            continue;
                        }

                        IBlockState state = section.get(x & 15, y & 15, z & 15);
                        pos.setPos(x, y, z);
                        state.addCollisionBoxToList(self, pos, aabb, outList, entityIn, false);

                        // Forge: per added-box early exit in "collides" mode (the event sees the list each time).
                        if (flagMode && !ForgeEventFactory.gatherCollisionBoxes(self, entityIn, aabb, outList)) {
                            return true;
                        }
                    }
                }
            }
        } finally {
            pos.release();
        }

        return !outList.isEmpty();
    }
}
