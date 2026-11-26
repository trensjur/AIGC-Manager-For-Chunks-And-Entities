package com.wwwwz.aigcs.client.map;

import net.minecraft.util.Mth;

/**
 * 64 colour palette used by the waypoint editor.
 * <p>
 * The palette is an 8x8 grid: every column is one base hue and every row is a
 * brightness / saturation step, so the colour picker can be drawn as a compact
 * square grid without hard coded hex values.
 */
public final class WaypointColors {
    /** Side length of the square palette. */
    public static final int GRID = 8;
    /** Number of colours of the palette. */
    public static final int[] PALETTE = new int[GRID * GRID];

    static {
        float[] hues = { 0.0F, 0.055F, 0.12F, 0.25F, 0.42F, 0.52F, 0.62F, 0.78F };
        for (int column = 0; column < GRID; column++) {
            for (int row = 0; row < GRID; row++) {
                float saturation = row == GRID - 1 ? 0.12F : (row >= 6 ? 0.55F : 0.90F);
                float value = 0.30F + row * 0.09F;
                PALETTE[column * GRID + row] = Mth.hsvToRgb(hues[column], saturation,
                        Math.min(1.0F, value));
            }
        }
    }

    private WaypointColors() {
    }

    public static int at(int index) {
        return PALETTE[Math.floorMod(index, PALETTE.length)];
    }

    /**
     * Palette index of a stored colour, or a neutral fallback when it is unknown.
     */
    public static int indexOf(int color) {
        int rgb = color & 0xFFFFFF;
        for (int index = 0; index < PALETTE.length; index++) {
            if ((PALETTE[index] & 0xFFFFFF) == rgb) {
                return index;
            }
        }
        return 3;
    }

    /** Picks a stable default colour for the next waypoint. */
    public static int nextDefault(int count) {
        return at(count * 7 + 3);
    }
}
