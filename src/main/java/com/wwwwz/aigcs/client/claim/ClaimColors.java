package com.wwwwz.aigcs.client.claim;

import com.wwwwz.aigcs.client.map.WaypointColors;

/**
 * 64 colour palette of the chunk claim overlay.
 * <p>
 * It reuses the waypoint palette so both pickers look identical, but keeps a
 * pure white cell: claims are drawn as white stripes by default.
 */
public final class ClaimColors {
    public static final int GRID = WaypointColors.GRID;
    public static final int COUNT = GRID * GRID;
    public static final int WHITE = 0xFFFFFF;
    /** Index of the white cell; it doubles as the default claim colour. */
    public static final int DEFAULT_INDEX = COUNT - 1;

    private ClaimColors() {
    }

    public static int at(int index) {
        int normalized = Math.floorMod(index, COUNT);
        return normalized == DEFAULT_INDEX ? WHITE : WaypointColors.at(normalized);
    }

    public static int indexOf(int color) {
        if ((color & 0xFFFFFF) == WHITE) {
            return DEFAULT_INDEX;
        }
        return WaypointColors.indexOf(color & 0xFFFFFF);
    }

    public static int defaultColor() {
        return at(DEFAULT_INDEX);
    }
}
