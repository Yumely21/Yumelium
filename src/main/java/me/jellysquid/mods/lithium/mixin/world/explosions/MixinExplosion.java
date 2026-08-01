package me.jellysquid.mods.lithium.mixin.world.explosions;

import net.minecraft.block.state.IBlockState;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.init.Blocks;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lithium world.explosions (ported to 1.12.2): the {@code doExplosionA} density sweep fires 1352 rays × ~12-23
 * steps ≈ 20-30k {@code World.getBlockState} calls per power-4 explosion, each paying the full
 * provider/chunk-map lookup. This redirects the sweep's single getBlockState call through a one-entry per-instance
 * chunk cache + direct section read — everything else (per-step Forge resistance/destroy hooks with their exact
 * call COUNTS, HashSet membership incl. air positions, iteration order → drop order, both RNG streams, event
 * placement) is deliberately untouched, so the observable outcome is bit-identical.
 *
 * <p>Design + independent adversarial re-verification against the Forge-patched bytecode (2026-07-27 research
 * workflow, archived in reference/lithium-research-2026-07-27.json): the sweep's ONLY getBlockState sits at
 * bci 223 with owner {@code World}; first touch of each chunk still goes through the same virtual
 * {@code world.getChunk} → provideChunk path (load/generate side effects preserved; vanilla's repeat calls were
 * pure map hits — the one elided write is ChunkProviderServer.getLoadedChunk's per-call {@code unloadQueued=false}
 * reset, benign under vanilla+Forge since nothing queues unloads mid-sweep). Cache is reset at HEAD (a held
 * Explosion re-invoked in a later tick must not read an orphaned chunk) and dropped at RETURN (mods retain
 * Explosion instances via the Forge events — a pinned Chunk would be a leak vanilla cannot produce). Exotic chunk
 * implementations (Cubic Chunks etc.) fall back to vanilla wholesale via the getClass() guard. Lithium's further
 * tricks (LongOpenHashSet, hook dedup, air skipping) are DELIBERATELY not ported — each would change observable
 * order/counts/packet contents.</p>
 *
 * <p>Phase 2 (2026-08-01, same research doc): the sweep's ONLY {@code new BlockPos(DDD)} (bci 202) allocates per
 * STEP, but with a 0.3 step against 1.0 blocks ~2/3 of consecutive steps floor to the SAME coords — reuse the
 * previous step's instance when they match. Value-wise bit-identical ({@code BlockPos(DDD)} floors via
 * {@code MathHelper.floor} in {@code Vec3i(DDD)} — replicated exactly); the only deviation is object IDENTITY:
 * consecutive same-block steps hand the hooks one shared immutable instance instead of fresh equal ones
 * (research risk 8 — a mod comparing hook positions by {@code ==} across calls would notice; none known, and
 * such a comparison is semantically wrong against immutable value types).</p>
 */
@Mixin(Explosion.class)
public abstract class MixinExplosion {
    private Chunk lithium$prevChunk;
    private int lithium$prevChunkX = Integer.MIN_VALUE;
    private int lithium$prevChunkZ = Integer.MIN_VALUE;
    private BlockPos lithium$prevPos;

    @Inject(method = "doExplosionA", at = @At("HEAD"))
    private void lithium$resetChunkCache(CallbackInfo ci) {
        this.lithium$prevChunk = null;
        this.lithium$prevChunkX = Integer.MIN_VALUE;
        this.lithium$prevChunkZ = Integer.MIN_VALUE;
        this.lithium$prevPos = null;
    }

    @Inject(method = "doExplosionA", at = @At("RETURN"))
    private void lithium$dropChunkCache(CallbackInfo ci) {
        this.lithium$prevChunk = null;
        this.lithium$prevChunkX = Integer.MIN_VALUE;
        this.lithium$prevChunkZ = Integer.MIN_VALUE;
        this.lithium$prevPos = null;
    }

    // Phase 2: replaces the sweep's single `new BlockPos(D,D,D)`. First NEW redirect in the project — the desc-form
    // target is the canonical Mixin spelling for a constructor by signature; require=1 so a retarget regression
    // fails at apply instead of silently reverting to per-step allocation.
    @Redirect(
            method = "doExplosionA",
            require = 1,
            at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/util/math/BlockPos;")
    )
    private BlockPos lithium$reuseStepPos(double x, double y, double z) {
        // Exactly BlockPos(DDD)'s own flooring (Vec3i(DDD) → MathHelper.floor per axis).
        int bx = net.minecraft.util.math.MathHelper.floor(x);
        int by = net.minecraft.util.math.MathHelper.floor(y);
        int bz = net.minecraft.util.math.MathHelper.floor(z);
        BlockPos prev = this.lithium$prevPos;
        if (prev != null && prev.getX() == bx && prev.getY() == by && prev.getZ() == bz) {
            return prev;
        }
        BlockPos pos = new BlockPos(bx, by, bz);
        this.lithium$prevPos = pos;
        return pos;
    }

    @Redirect(
            method = "doExplosionA",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"
            )
    )
    private IBlockState lithium$cachedBlockState(World world, BlockPos pos) {
        int y = pos.getY();
        // World.getBlockState's isOutsideBuildHeight fast path.
        if (y < 0 || y >= 256) {
            return Blocks.AIR.getDefaultState();
        }
        // The debug world's block states come from Chunk.getBlockState's special branch — take vanilla wholesale.
        if (world.getWorldType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            return world.getBlockState(pos);
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        Chunk chunk = this.lithium$prevChunk;
        if (chunk == null || chunkX != this.lithium$prevChunkX || chunkZ != this.lithium$prevChunkZ) {
            chunk = world.getChunk(chunkX, chunkZ); // same virtual provideChunk path as vanilla's own lookup
            this.lithium$prevChunk = chunk;
            this.lithium$prevChunkX = chunkX;
            this.lithium$prevChunkZ = chunkZ;
        }
        // A chunk implementation we haven't proven value-identical (Cubic Chunks, transformed Chunk subclasses)
        // keeps full vanilla behaviour at the cost of one getClass() compare.
        if (chunk.getClass() != Chunk.class && chunk.getClass() != EmptyChunk.class) {
            return world.getBlockState(pos);
        }
        try {
            ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
            int sectionIndex = y >> 4;
            if (sectionIndex < sections.length) {
                ExtendedBlockStorage section = sections[sectionIndex];
                if (section != Chunk.NULL_BLOCK_STORAGE) {
                    return section.get(pos.getX() & 15, y & 15, pos.getZ() & 15);
                }
            }
            return Blocks.AIR.getDefaultState();
        } catch (Throwable cause) {
            // Crash-fidelity with Chunk.getBlockState's own handler.
            CrashReport report = CrashReport.makeCrashReport(cause, "Getting block state");
            CrashReportCategory category = report.makeCategory("Block being got");
            category.addCrashSection("Location", CrashReportCategory.getCoordinateInfo(pos));
            throw new ReportedException(report);
        }
    }
}
