package com.yumelium.yumelium.nvidium.gl;

import java.util.Map;
import java.util.TreeMap;

/**
 * A simple byte-offset sub-allocator over a fixed-capacity buffer's address space, used to pack per-section geometry
 * into one large device buffer. First-fit allocation with neighbour coalescing on free. Offsets are aligned so shader
 * reads stay aligned.
 *
 * <p>Nvidium/Alphadium port (LGPL-3.0) — see {@link NvidiumGl}. This is an original, simplified allocator.</p>
 */
public class BufferArena {
    private static final long ALIGNMENT = 256L;

    private long capacity;
    private final TreeMap<Long, Long> freeByOffset = new TreeMap<>(); // offset -> free-run length
    private long used;

    public BufferArena(long capacity) {
        this.capacity = align(capacity);
        this.freeByOffset.put(0L, this.capacity);
    }

    private static long align(long v) {
        return (v + ALIGNMENT - 1L) & ~(ALIGNMENT - 1L);
    }

    /** @return the allocated byte offset, or -1 if there is no run large enough. */
    public long alloc(long bytes) {
        long need = align(bytes);
        for (Map.Entry<Long, Long> e : this.freeByOffset.entrySet()) {
            if (e.getValue() >= need) {
                long offset = e.getKey();
                long run = e.getValue();
                this.freeByOffset.remove(offset);
                if (run > need) {
                    this.freeByOffset.put(offset + need, run - need);
                }
                this.used += need;
                return offset;
            }
        }
        return -1L;
    }

    public void free(long offset, long bytes) {
        long len = align(bytes);
        this.used -= len;

        // Coalesce with the immediately-following free run.
        Long succ = this.freeByOffset.remove(offset + len);
        if (succ != null) {
            len += succ;
        }
        // Coalesce with the immediately-preceding free run.
        Map.Entry<Long, Long> pred = this.freeByOffset.floorEntry(offset - 1L);
        if (pred != null && pred.getKey() + pred.getValue() == offset) {
            this.freeByOffset.put(pred.getKey(), pred.getValue() + len);
        } else {
            this.freeByOffset.put(offset, len);
        }
    }

    /** Extend the arena's capacity, adding the new tail as a free run (coalescing with any trailing free run). */
    public void grow(long newCapacity) {
        long newCap = align(newCapacity);
        if (newCap <= this.capacity) {
            return;
        }
        long tailOffset = this.capacity;
        long tailLen = newCap - this.capacity;
        Map.Entry<Long, Long> pred = this.freeByOffset.floorEntry(tailOffset - 1L);
        if (pred != null && pred.getKey() + pred.getValue() == tailOffset) {
            this.freeByOffset.put(pred.getKey(), pred.getValue() + tailLen);
        } else {
            this.freeByOffset.put(tailOffset, tailLen);
        }
        this.capacity = newCap;
    }

    public long capacity() {
        return this.capacity;
    }

    public long used() {
        return this.used;
    }
}
