package me.jellysquid.mods.lithium.common.world.scheduler;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.gen.structure.StructureBoundingBox;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Side-indexes for WorldServer's inlined block-tick scheduler (Lithium tick_scheduler, Design B —
 * reference/lithium-research-2026-07-27.json). The vanilla TreeSet/HashSet/ThisTick collections stay the
 * untouched ordering authority; these structures only mirror membership so the two O(n) scans
 * (isBlockTickPending's List.contains, getPendingBlockUpdates' full-TreeSet sweep per chunk save) become
 * O(1)/O(chunk). Server-thread-only by the same (absent) contract as the vanilla collections — no locking,
 * deliberately.
 */
public final class TickSchedulerIndex {
    /** Mirrors pendingTickListEntriesHashSet/TreeSet membership, bucketed by chunk. ObjectOpenHashSet buckets
     *  (adversarial Flaw 3 fix): selection unlink is O(1), and set semantics are safe because the vanilla HashSet
     *  dedupe guarantees at most one live entry per (pos,block) key. Empty buckets are dropped to track chunk churn. */
    private final Long2ObjectOpenHashMap<ObjectOpenHashSet<NextTickListEntry>> chunkIndex = new Long2ObjectOpenHashMap<>();

    /** Counting multiset mirroring pendingTickListEntriesThisTick membership (adversarial Flaw 5c fix: tickUpdates
     *  has no finally, so a mod swallowing the ReportedException can strand duplicates in the List — a plain HashSet
     *  mirror would then diverge from List.contains; a count per key cannot). */
    private final Object2IntOpenHashMap<NextTickListEntry> thisTickMirror = new Object2IntOpenHashMap<>();

    private static long keyOf(NextTickListEntry entry) {
        return ChunkPos.asLong(entry.position.getX() >> 4, entry.position.getZ() >> 4);
    }

    public void addPending(NextTickListEntry entry) {
        long key = keyOf(entry);
        ObjectOpenHashSet<NextTickListEntry> bucket = this.chunkIndex.get(key);
        if (bucket == null) {
            bucket = new ObjectOpenHashSet<>();
            this.chunkIndex.put(key, bucket);
        }
        bucket.add(entry);
    }

    public void removePending(NextTickListEntry entry) {
        long key = keyOf(entry);
        ObjectOpenHashSet<NextTickListEntry> bucket = this.chunkIndex.get(key);
        if (bucket != null && bucket.remove(entry) && bucket.isEmpty()) {
            this.chunkIndex.remove(key);
        }
    }

    public void thisTickAdd(NextTickListEntry entry) {
        this.thisTickMirror.addTo(entry, 1);
    }

    public void thisTickRemove(NextTickListEntry entry) {
        int count = this.thisTickMirror.getInt(entry);
        if (count <= 1) {
            this.thisTickMirror.removeInt(entry);
        } else {
            this.thisTickMirror.put(entry, count - 1);
        }
    }

    public void thisTickClear() {
        this.thisTickMirror.clear();
    }

    public boolean isInThisTick(NextTickListEntry probe) {
        return this.thisTickMirror.containsKey(probe);
    }

    private static boolean inBounds(NextTickListEntry entry, StructureBoundingBox sbb) {
        // Exact vanilla filter: x/z min-inclusive max-EXCLUSIVE, NO Y test (WorldServer bytecode bci 68-113).
        int x = entry.position.getX();
        int z = entry.position.getZ();
        return x >= sbb.minX && x < sbb.maxX && z >= sbb.minZ && z < sbb.maxZ;
    }

    /**
     * Replacement body for WorldServer.getPendingBlockUpdates(StructureBoundingBox, boolean).
     * Vanilla contract reproduced exactly: pass 0 = pending set in TreeSet (sorted) order, pass 1 = ThisTick in
     * list order appended to the SAME lazily-created list; returns null when nothing matched.
     */
    public List<NextTickListEntry> getPendingBlockUpdates(StructureBoundingBox sbb, boolean remove,
                                                          TreeSet<NextTickListEntry> treeSet,
                                                          Set<NextTickListEntry> hashSet,
                                                          List<NextTickListEntry> thisTick) {
        List<NextTickListEntry> result = null;
        if (!remove) {
            // HOT PATH (every chunk save via AnvilChunkLoader.writeChunkToNBT): gather from the chunk index,
            // then re-sort with the natural comparator. compareTo is a TOTAL order (unique tickEntryID tiebreak),
            // so sort(matches) == the filtered TreeSet iteration order → byte-identical TileTicks NBT.
            List<NextTickListEntry> matches = null;
            int minCX = sbb.minX >> 4;
            int maxCX = (sbb.maxX - 1) >> 4; // maxX is exclusive
            int minCZ = sbb.minZ >> 4;
            int maxCZ = (sbb.maxZ - 1) >> 4;
            long rectArea = ((long) maxCX - minCX + 1) * ((long) maxCZ - minCZ + 1);
            if (maxCX < minCX || maxCZ < minCZ || rectArea > this.chunkIndex.size()) {
                // Degenerate or world-spanning box (no vanilla/Forge caller passes one — chunk saves are ~2×2 and
                // CommandClone is volume-capped): walking the chunk rectangle would visit more keys than the index
                // holds, so vanilla's single filtered sweep is strictly better — and it is already in sorted order.
                // Also sidesteps (maxX-1) underflow for hostile boxes (maxX == Integer.MIN_VALUE).
                for (NextTickListEntry entry : treeSet) {
                    if (inBounds(entry, sbb)) {
                        if (matches == null) {
                            matches = Lists.newArrayList();
                        }
                        matches.add(entry);
                    }
                }
                return appendThisTick(matches, sbb, false, thisTick);
            }
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    ObjectOpenHashSet<NextTickListEntry> bucket = this.chunkIndex.get(ChunkPos.asLong(cx, cz));
                    if (bucket == null) {
                        continue;
                    }
                    for (NextTickListEntry entry : bucket) {
                        if (inBounds(entry, sbb)) {
                            if (matches == null) {
                                matches = Lists.newArrayList();
                            }
                            matches.add(entry);
                        }
                    }
                }
            }
            if (matches != null) {
                try {
                    Collections.sort(matches);
                } catch (IllegalArgumentException timSortContractViolation) {
                    // Adversarial Flaw 5b: mod priorities differing by >= 2^31 make compareTo (raw isub)
                    // non-transitive and TimSort may throw where vanilla's TreeSet walk would not.
                    // Fall back to the vanilla-shape filtered structural iteration (never throws).
                    matches = null;
                    for (NextTickListEntry entry : treeSet) {
                        if (inBounds(entry, sbb)) {
                            if (matches == null) {
                                matches = Lists.newArrayList();
                            }
                            matches.add(entry);
                        }
                    }
                }
                result = matches;
            }
        } else {
            // COLD PATH (no vanilla/Forge caller uses remove=true — verified): replicate vanilla's POSITIONAL
            // iteration (adversarial Flaw 5a: treeSet.remove(Object) navigates by compareTo and can miss
            // mod-mutated entries, minting a new out-of-synch ISE crash mode; Iterator.remove cannot).
            for (Iterator<NextTickListEntry> it = treeSet.iterator(); it.hasNext(); ) {
                NextTickListEntry entry = it.next();
                if (inBounds(entry, sbb)) {
                    hashSet.remove(entry);
                    it.remove();
                    this.removePending(entry); // keep chunkIndex mirroring hashSet membership
                    if (result == null) {
                        result = Lists.newArrayList();
                    }
                    result.add(entry);
                }
            }
        }
        return appendThisTick(result, sbb, remove, thisTick);
    }

    /** Pass 1 — ThisTick in list order, appended to the SAME lazily-created list (vanilla shares one loop body
     * across both passes). @return the final result; null when nothing matched anywhere — vanilla contract
     * (CommandClone null-checks it). */
    private List<NextTickListEntry> appendThisTick(List<NextTickListEntry> result, StructureBoundingBox sbb,
                                                   boolean remove, List<NextTickListEntry> thisTick) {
        for (Iterator<NextTickListEntry> it = thisTick.iterator(); it.hasNext(); ) {
            NextTickListEntry entry = it.next();
            if (inBounds(entry, sbb)) {
                if (remove) {
                    it.remove();
                    this.thisTickRemove(entry); // adversarial FLAW 1 FIX: mirror must track pass-1 removal
                }
                if (result == null) {
                    result = Lists.newArrayList();
                }
                result.add(entry);
            }
        }
        return result;
    }
}
