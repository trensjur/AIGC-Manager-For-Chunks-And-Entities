package com.wwwwz.aigcs.util;

/** Small colour helpers for the map tile pipeline. */
public final class MapColors {
    private MapColors() {
    }

    /** {@code 0xRRGGBB} -> {@code 0xAABBGGRR}, the layout used by native images. */
    public static int rgbToAbgr(int rgb) {
        return 0xFF000000
                | ((rgb & 0xFF) << 16)
                | (rgb & 0xFF00)
                | ((rgb >> 16) & 0xFF);
    }

    /** {@code 0xAABBGGRR} -> {@code 0xRRGGBB}. */
    public static int abgrToRgb(int abgr) {
        return ((abgr & 0xFF) << 16)
                | (abgr & 0xFF00)
                | ((abgr >> 16) & 0xFF);
    }

    public static int scaleRgb(int rgb, double factor) {
        int red = clamp((int) Math.round(((rgb >> 16) & 0xFF) * factor));
        int green = clamp((int) Math.round(((rgb >> 8) & 0xFF) * factor));
        int blue = clamp((int) Math.round((rgb & 0xFF) * factor));
        return (red << 16) | (green << 8) | blue;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
