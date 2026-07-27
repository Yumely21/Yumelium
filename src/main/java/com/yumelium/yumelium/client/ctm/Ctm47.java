package com.yumelium.yumelium.client.ctm;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * The 47-tile connected-textures ("ctm" method) index logic. The 47 tiles enumerate the distinct connection states of a
 * face's 4 in-plane edge neighbours plus the relevant diagonal corners; this MUST match the tile order produced by the
 * offline glass generator (same enumeration). Given a block, face and world, {@link #tileIndex} returns 0..46.
 *
 * <p>Face-relative up/right vectors are used to find the in-plane neighbours; for a rotationally-symmetric frame (glass)
 * the exact up/right choice is irrelevant, only that the 4 edges map consistently to the 4 in-plane neighbours.</p>
 *
 * <p>Verified correct by measurement (2026-07-18): a continuous same-block wall gives every interior block full edge
 * connection → tile 46, and the only disconnections observed were at genuine material boundaries — plain glass next to
 * stained glass, or a glass block next to a glass pane, which are different blocks and correctly do NOT connect (this
 * matches vanilla/OptiFine). A "frame line through the middle of a wall" is therefore the wall changing material, not a
 * bug.</p>
 */
public final class Ctm47 {
    /** Packed connection state (bit0..3 = N/E/S/W edges, bit4..7 = NE/SE/SW/NW corners) for each of the 47 tiles. */
    private static final int[] CONFIG = buildConfig();
    /** state (8 bits) -> tile index. States that never occur at runtime map to 0. */
    private static final byte[] STATE_TO_TILE = buildLookup();

    // Face-relative up/right world vectors, indexed by EnumFacing.ordinal() (DOWN,UP,NORTH,SOUTH,WEST,EAST).
    private static final int[][] UP = {
            {0, 0, -1}, {0, 0, -1}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}
    };
    private static final int[][] RIGHT = {
            {1, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}
    };

    private Ctm47() {
    }

    private static int[] buildConfig() {
        int[] cfg = new int[47];
        int idx = 0;
        for (int e = 0; e < 16; e++) {
            boolean n = (e & 1) != 0, ea = (e & 2) != 0, s = (e & 4) != 0, w = (e & 8) != 0;
            boolean[] rel = {n && ea, s && ea, s && w, n && w}; // NE, SE, SW, NW
            int r = 0;
            for (boolean b : rel) {
                if (b) r++;
            }
            for (int cc = 0; cc < (1 << r); cc++) {
                boolean[] corner = new boolean[4];
                int bit = 0;
                for (int c = 0; c < 4; c++) {
                    if (rel[c]) {
                        corner[c] = ((cc >> (bit++)) & 1) != 0;
                    }
                }
                cfg[idx++] = (n ? 1 : 0) | (ea ? 2 : 0) | (s ? 4 : 0) | (w ? 8 : 0)
                        | (corner[0] ? 16 : 0) | (corner[1] ? 32 : 0) | (corner[2] ? 64 : 0) | (corner[3] ? 128 : 0);
            }
        }
        return cfg; // idx == 47
    }

    private static byte[] buildLookup() {
        byte[] lut = new byte[256];
        for (int i = 0; i < CONFIG.length; i++) {
            lut[CONFIG[i]] = (byte) i;
        }
        return lut;
    }

    /**
     * @return the connected-texture tile index (0..46) for the given face of the block at (x,y,z). A neighbour connects
     * when it matches {@code source} — the same block ({@code sameState=false}) or the exact same blockstate
     * ({@code sameState=true}, used for coloured stained glass so only the same colour merges). {@code scratch} is a
     * reusable mutable position to avoid allocation.
     */
    public static int tileIndex(IBlockAccess world, int x, int y, int z, IBlockState source, boolean sameState,
                                EnumFacing face, BlockPos.MutableBlockPos scratch) {
        int o = face.ordinal();
        int[] u = UP[o];
        int[] r = RIGHT[o];

        net.minecraft.block.Block block = source.getBlock();
        // Match colour by metadata (not object identity) so it survives actual/extended states (pane connection props).
        int meta = sameState ? block.getMetaFromState(source) : 0;

        boolean n = same(world, x + u[0], y + u[1], z + u[2], block, sameState, meta, scratch);
        boolean ea = same(world, x + r[0], y + r[1], z + r[2], block, sameState, meta, scratch);
        boolean s = same(world, x - u[0], y - u[1], z - u[2], block, sameState, meta, scratch);
        boolean w = same(world, x - r[0], y - r[1], z - r[2], block, sameState, meta, scratch);

        boolean ne = n && ea && same(world, x + u[0] + r[0], y + u[1] + r[1], z + u[2] + r[2], block, sameState, meta, scratch);
        boolean se = s && ea && same(world, x - u[0] + r[0], y - u[1] + r[1], z - u[2] + r[2], block, sameState, meta, scratch);
        boolean sw = s && w && same(world, x - u[0] - r[0], y - u[1] - r[1], z - u[2] - r[2], block, sameState, meta, scratch);
        boolean nw = n && w && same(world, x + u[0] - r[0], y + u[1] - r[1], z + u[2] - r[2], block, sameState, meta, scratch);

        int state = (n ? 1 : 0) | (ea ? 2 : 0) | (s ? 4 : 0) | (w ? 8 : 0)
                | (ne ? 16 : 0) | (se ? 32 : 0) | (sw ? 64 : 0) | (nw ? 128 : 0);
        return STATE_TO_TILE[state] & 0xFF;
    }

    private static boolean same(IBlockAccess world, int x, int y, int z, net.minecraft.block.Block block,
                                boolean sameState, int meta, BlockPos.MutableBlockPos scratch) {
        scratch.setPos(x, y, z);
        IBlockState ns = world.getBlockState(scratch);
        if (ns.getBlock() != block) {
            return false;
        }
        return !sameState || block.getMetaFromState(ns) == meta;
    }
}
