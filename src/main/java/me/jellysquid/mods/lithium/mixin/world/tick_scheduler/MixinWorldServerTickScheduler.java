package me.jellysquid.mods.lithium.mixin.world.tick_scheduler;

import me.jellysquid.mods.lithium.common.world.scheduler.TickSchedulerIndex;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lithium world.tick_scheduler (Design B, ported to 1.12.2): the block-tick scheduler is INLINED in
 * {@code WorldServer} (no scheduler class exists) as TreeSet/HashSet/ThisTick. Two operations are O(n):
 * {@code isBlockTickPending} does {@code List.contains} over the executing batch (the classic redstone-repeater
 * quadratic — BlockRedstoneDiode/Comparator call it per neighborChanged), and {@code getPendingBlockUpdates(SBB,Z)}
 * sweeps the ENTIRE pending population once per chunk save. This mixin keeps the three vanilla collections as the
 * untouched ordering authority and only maintains side-indexes: a per-chunk bucket map mirroring pending-set
 * membership and a counting multiset mirroring ThisTick membership. Selection order, execution order, the 65536
 * cap, the "TickNextTick list out of synch" check, the AIR-material time-0 quirk, the unloaded-chunk (and
 * out-of-Y-range) virtual {@code scheduleUpdate} reschedule, and both Forge patches (persistent-chunk 0/8 range
 * fudge in updateBlockTick, null-block guard in scheduleBlockUpdate) are untouched — every @Redirect wraps the
 * original op, so the observable outcome is bit-identical.
 *
 * <p>Design + adversarial re-verification against the Forge-patched bytecode: 2026-07-27 research workflow
 * (reference/lithium-research-2026-07-27.json, scheduler section, verdict PORT_PARTIAL/Design-B) with all six
 * adversarial minimal fixes applied here: pass-1 mirror removal on {@code getPendingBlockUpdates(sbb, true)}
 * (Flaw 1), hash-set chunk buckets (Flaw 3), positional TreeSet iteration on the remove=true cold path (Flaw 5a),
 * TimSort contract-violation fallback (Flaw 5b), counting-multiset ThisTick mirror (Flaw 5c), and cancellable
 * HEAD injects instead of @Overwrite for softer coexistence (Flaw 6). Injection sites re-verified 2026-08-01
 * against the current dev jar: TreeSet.add at updateBlockTick bci 227 / scheduleBlockUpdate bci 90; tickUpdates
 * List.add bci 138, Iterator.next bci 187, List.clear bci 378 — each unique within its method, hence require=1.
 * The research spec's Iterator.remove (bci 198) redirect is replaced by the Iterator.next (bci 187) redirect:
 * plain @Redirect cannot capture the removed entry, and bci 187→198 is straight-line with the remove()
 * unconditional, so decrementing the mirror inside next() is observably identical.</p>
 *
 * <p>getPendingBlockUpdates parity: pass-0 output is re-sorted with the natural comparator, which IS the TreeSet
 * order (compareTo = scheduledTime → signed priority → unique tickEntryID = a total order), so the returned list —
 * and therefore AnvilChunkLoader's TileTicks NBT order, which is the persisted FIFO tiebreaker across chunk
 * reload, and CommandClone's re-schedule order — is byte-identical. The null-when-empty contract and the
 * live-entry (same instance) contract are preserved. isBlockTickPending parity: the mirror is an exact multiset
 * of ThisTick under every vanilla-reachable flow, and the probe entry construction (one NextTickListEntry per
 * call) matches vanilla, keeping the static tickEntryID counter in lockstep.</p>
 *
 * <p>KNOWN CONSTRAINT (research risk #3, adversarially downgraded — Flaw 4): mods that WRITE the vanilla
 * collections directly via reflection/AT bypass the redirects; their entries stay invisible to the indexes, so
 * such entries would be omitted from chunk saves and from isBlockTickPending. Readers are unaffected. No such
 * mod is in the compat bench.</p>
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServerTickScheduler {
    @Shadow @Final private Set<NextTickListEntry> pendingTickListEntriesHashSet;
    @Shadow @Final private TreeSet<NextTickListEntry> pendingTickListEntriesTreeSet;
    @Shadow @Final private List<NextTickListEntry> pendingTickListEntriesThisTick;

    private final TickSchedulerIndex lithium$tickIndex = new TickSchedulerIndex();

    // --- Index maintenance: every entry accepted into the pending sets (both sites are reached only after the
    // vanilla hashSet dedupe passed, so chunkIndex mirrors hashSet/treeSet membership exactly). require=1: each
    // method contains exactly one TreeSet.add — a retarget regression fails at apply, per project convention.

    @Redirect(
            method = "updateBlockTick",
            require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/TreeSet;add(Ljava/lang/Object;)Z")
    )
    private boolean lithium$indexScheduledEntry(TreeSet<NextTickListEntry> treeSet, Object entry) {
        boolean added = treeSet.add((NextTickListEntry) entry);
        this.lithium$tickIndex.addPending((NextTickListEntry) entry);
        return added;
    }

    @Redirect(
            method = "scheduleBlockUpdate",
            require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/TreeSet;add(Ljava/lang/Object;)Z")
    )
    private boolean lithium$indexLoadedEntry(TreeSet<NextTickListEntry> treeSet, Object entry) {
        boolean added = treeSet.add((NextTickListEntry) entry);
        this.lithium$tickIndex.addPending((NextTickListEntry) entry);
        return added;
    }

    // --- tickUpdates selection: the entry just left treeSet (bci 116) and hashSet (bci 126) and enters ThisTick
    // here (bci 138) — straight-line bytecode between, so unlinking chunkIndex here keeps it exact.

    @Redirect(
            method = "tickUpdates",
            require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z")
    )
    private boolean lithium$trackSelectedEntry(List<NextTickListEntry> thisTick, Object entry) {
        boolean added = thisTick.add((NextTickListEntry) entry);
        NextTickListEntry e = (NextTickListEntry) entry;
        this.lithium$tickIndex.removePending(e);
        this.lithium$tickIndex.thisTickAdd(e);
        return added;
    }

    // --- tickUpdates execution: vanilla removes the entry from ThisTick (bci 198) immediately after next()
    // (bci 187) and BEFORE updateTick, which is what makes isBlockTickPending false for the currently-executing
    // entry. Mirroring the removal inside next() is equivalent (see class javadoc).

    @Redirect(
            method = "tickUpdates",
            require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;next()Ljava/lang/Object;")
    )
    private Object lithium$untrackExecutingEntry(Iterator<NextTickListEntry> iterator) {
        NextTickListEntry entry = iterator.next();
        this.lithium$tickIndex.thisTickRemove(entry);
        return entry;
    }

    @Redirect(
            method = "tickUpdates",
            require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/List;clear()V")
    )
    private void lithium$clearThisTickMirror(List<NextTickListEntry> thisTick) {
        thisTick.clear();
        this.lithium$tickIndex.thisTickClear();
    }

    // --- The two O(n) reads, replaced via cancellable HEAD injects (adversarial Flaw 6: softer coexistence than
    // @Overwrite — a conflicting mixin no longer hard-crashes at apply). Both ALWAYS cancel.

    /**
     * O(1) replacement for the O(ThisTick) List.contains scan. Probe construction matches vanilla exactly
     * (one NextTickListEntry, minting one tickEntryID — counter parity). Equality semantics are identical:
     * the mirror's containsKey uses the same equals (position + Block.isEqualTo) / hashCode (position only).
     */
    @Inject(method = "isBlockTickPending", at = @At("HEAD"), cancellable = true, require = 1)
    private void lithium$fastIsBlockTickPending(BlockPos pos, Block block, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.lithium$tickIndex.isInThisTick(new NextTickListEntry(pos, block)));
    }

    /**
     * Chunk-indexed replacement for the full-pending-population sweep (called once per chunk on EVERY chunk
     * save). The Chunk-variant overload delegates here virtually (bci 64), so AnvilChunkLoader benefits without
     * being touched. Descriptor-qualified: WorldServer has two getPendingBlockUpdates overloads.
     */
    @Inject(
            method = "getPendingBlockUpdates(Lnet/minecraft/world/gen/structure/StructureBoundingBox;Z)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void lithium$fastGetPendingBlockUpdates(StructureBoundingBox sbb, boolean remove,
                                                    CallbackInfoReturnable<List<NextTickListEntry>> cir) {
        cir.setReturnValue(this.lithium$tickIndex.getPendingBlockUpdates(sbb, remove,
                this.pendingTickListEntriesTreeSet,
                this.pendingTickListEntriesHashSet,
                this.pendingTickListEntriesThisTick));
    }
}
