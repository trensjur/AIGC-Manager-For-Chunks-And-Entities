package com.wwwwz.aigcs.client.map;

import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.core.ChunkTile;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;

/**
 * Bakes a {@link ChunkTile} into ready to blit ARGB colours.
 * <p>
 * Rasterising the world map used to evaluate shading, height tint and light for
 * every screen pixel on every rebuild. That made panning and zooming stutter.
 * The bake result only depends on the sampled tile and the visual settings, so
 * it is cached per tile fingerprint and the renderer can simply copy ints.
 * Day/night brightness is applied as a translucent overlay by the screen, which
 * keeps the cache valid while the sun moves.
 */
@SuppressWarnings("null")
public final class ChunkColorCache {
    private static final ChunkColorCache INSTANCE = new ChunkColorCache();
    private static final int MAX_ENTRIES = 32768;

    private record Baked(int style, int[] colors, int average) {
    }

    private final Long2ObjectOpenHashMap<Baked> baked = new Long2ObjectOpenHashMap<>();
    private int styleVersion = 1;

    private ChunkColorCache() {
    }

    public static ChunkColorCache get() {
        return INSTANCE;
    }

    /** All settings that change the baked colours bump this value. */
    public void bumpStyle() {
        this.styleVersion++;
    }

    public int styleVersion() {
        return this.styleVersion;
    }

    /**
     * Returns the 16x16 ARGB colours of {@code tile}, baking them on the first
     * call for the current tile content and style version.
     */
    public int[] colors(ChunkTile tile) {
        return this.colors(tile, tile.fingerprint());
    }

    /**
     * Same as {@link #colors(ChunkTile)} but reuses a fingerprint that was already
     * computed when the tile was stored. The map renderer calls this for every
     * visible chunk on every rebuild, so skipping the 16x16x5 hash matters.
     */
    public int[] colors(ChunkTile tile, long fingerprint) {
        return this.baked(tile, fingerprint).colors();
    }

    /**
     * One averaged ARGB colour of the tile, used when many chunks share a single
     * texture pixel. It avoids keeping a 16x16 colour array per visible chunk.
     */
    public int averageColor(ChunkTile tile, long fingerprint) {
        return this.baked(tile, fingerprint).average();
    }

    private Baked baked(ChunkTile tile, long fingerprint) {
        int style = this.styleVersion;
        Baked cached = this.baked.get(fingerprint);
        if (cached != null && cached.style() == style) {
            return cached;
        }

        int[] colors = bake(tile);
        Baked value = new Baked(style, colors, average(colors));
        if (this.baked.size() >= MAX_ENTRIES) {
            this.baked.clear();
        }
        this.baked.put(fingerprint, value);
        return value;
    }

    private static int average(int[] colors) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int count = 0;
        for (int color : colors) {
            if (color == 0) {
                continue;
            }
            red += color & 0xFF;
            green += (color >>> 8) & 0xFF;
            blue += (color >>> 16) & 0xFF;
            count++;
        }
        if (count == 0) {
            return 0;
        }
        return 0xFF000000 | ((int) (blue / count) << 16) | ((int) (green / count) << 8)
                | (int) (red / count);
    }

    public void clear() {
        this.baked.clear();
    }

    private static int[] bake(ChunkTile tile) {
        boolean shading = ClientConfig.MAP_SHADING.getAsBoolean();
        boolean heightColor = ClientConfig.MAP_HEIGHT_COLOR.getAsBoolean();
        boolean dayNight = ClientConfig.MAP_DAY_NIGHT.getAsBoolean();
        double shadingStrength = ClientConfig.MAP_SHADING_STRENGTH.get();
        double heightBlend = ClientConfig.MAP_HEIGHT_BLEND.get();
        double lightIntensity = ClientConfig.MAP_LIGHT_INTENSITY.get();

        int[] colors = new int[ChunkTile.AREA];
        for (int index = 0; index < ChunkTile.AREA; index++) {
            int color = tile.colors[index];
            if (color == 0) {
                continue;
            }
            double factor = 1.0D;
            if (shading) {
                factor *= 1.0D + tile.shade[index] / 160.0D * shadingStrength;
            }
            if (heightColor) {
                short height = tile.heights[index];
                if (height != Short.MIN_VALUE) {
                    double normalised = Mth.clamp((height - 64.0D) / 160.0D, -0.5D, 0.5D);
                    factor *= 1.0D + normalised * heightBlend * 0.5D;
                }
            }
            if (dayNight) {
                double sky = tile.skyLight[index] / 15.0D;
                double block = tile.blockLight[index] / 15.0D;
                double light = Math.max(sky, block);
                factor *= 1.0D - (1.0D - (0.40D + 0.60D * light)) * lightIntensity;
            }
            colors[index] = scale(color, factor);
        }
        return colors;
    }

    /**
     * Applies a brightness factor to a colour of the {@link ChunkTile} layout
     * ({@code 0xAABBGGRR}, red in the lowest byte) and keeps that layout so the
     * native image upload receives exactly what the tile contains.
     */
    private static int scale(int abgr, double factor) {
        int red = Mth.clamp((int) Math.round((abgr & 0xFF) * factor), 0, 255);
        int green = Mth.clamp((int) Math.round(((abgr >>> 8) & 0xFF) * factor), 0, 255);
        int blue = Mth.clamp((int) Math.round(((abgr >>> 16) & 0xFF) * factor), 0, 255);
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }
}
