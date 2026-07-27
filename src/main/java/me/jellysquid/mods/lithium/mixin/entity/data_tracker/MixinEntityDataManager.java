package me.jellysquid.mods.lithium.mixin.entity.data_tracker;

import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.ReportedException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;
import java.util.Map;

/**
 * Lithium entity.data_tracker.use_arrays (ported to 1.12.2): mirrors the tracker's entries into a flat array indexed
 * by parameter id, so {@code getEntry} — the funnel behind EVERY {@code get}/{@code set}/{@code setDirty}, called for
 * each entity flag read every render frame and AI tick — becomes a bounds-checked array load with no Integer boxing
 * and no ReadWriteLock round-trip.
 *
 * <p>Unlike 1.16 (where Lithium also nulls the lock outright), 1.12.2 REALLY does serialize live trackers off-thread:
 * {@code SPacketSpawnMob.writePacketData} calls {@code writeEntries(PacketBuffer)} on the Netty thread. So the vanilla
 * lock is kept for the map-iterating paths (writeEntries/getDirty/getAll) and only {@code getEntry} goes lock-free —
 * safe because the map/array STRUCTURE is only mutated during entity construction (register), before the entity is
 * ever visible to another thread; value updates mutate the entry object, not the structure, exactly as in vanilla.</p>
 */
@Mixin(EntityDataManager.class)
public abstract class MixinEntityDataManager {
    private static final int DEFAULT_ENTRY_COUNT = 10, GROW_FACTOR = 8;

    /** Mirrors the vanilla backing entries map; each DataEntry is reachable through its parameter id. */
    private EntityDataManager.DataEntry<?>[] yumelium$entriesArray = new EntityDataManager.DataEntry<?>[DEFAULT_ENTRY_COUNT];

    /**
     * Mirror every registration into the array. {@code setEntry} is the single place vanilla inserts into the map,
     * and it only runs from {@code register} during entity construction. Type-erasure forces the raw generic shapes.
     */
    @Redirect(
            method = "setEntry",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            )
    )
    private Object yumelium$mirrorPut(Map<Object, Object> map, Object keyRaw, Object valueRaw) {
        int k = (Integer) keyRaw;
        EntityDataManager.DataEntry<?> v = (EntityDataManager.DataEntry<?>) valueRaw;

        EntityDataManager.DataEntry<?>[] storage = this.yumelium$entriesArray;

        // Grow to accommodate the new key range: 8 entries of headroom, capped at the vanilla id limit (254 + 1).
        if (storage.length <= k) {
            int newSize = Math.min(k + GROW_FACTOR, 255);
            this.yumelium$entriesArray = storage = Arrays.copyOf(storage, newSize);
        }

        storage[k] = v;

        // Keep the vanilla backing map updated — the packet serialization/iteration paths still read it (locked).
        return map.put(keyRaw, valueRaw);
    }

    /**
     * @author JellySquid
     * @reason Avoid integer boxing/unboxing + the per-read lock round-trip; use array-based storage
     */
    @Overwrite
    private <T> EntityDataManager.DataEntry<T> getEntry(DataParameter<T> key) {
        try {
            EntityDataManager.DataEntry<?>[] array = this.yumelium$entriesArray;

            int id = key.getId();

            // Vanilla's map simply returns null for an unknown id; an array would throw OOB — preserve null semantics.
            if (id < 0 || id >= array.length) {
                return null;
            }

            // The cast can fail if a key belonging to a different tracker is passed — that matches vanilla behaviour.
            //noinspection unchecked
            return (EntityDataManager.DataEntry<T>) array[id];
        } catch (Throwable cause) {
            // Kept in a separate method so this one stays small enough to inline well.
            throw yumelium$onGetException(cause, key);
        }
    }

    private static <T> ReportedException yumelium$onGetException(Throwable cause, DataParameter<T> key) {
        CrashReport report = CrashReport.makeCrashReport(cause, "Getting synched entity data");
        CrashReportCategory category = report.makeCategory("Synched entity data");
        category.addCrashSection("Data ID", key);
        return new ReportedException(report);
    }
}
