package com.wwwwz.aigcs.client.gui;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunk selection of the world map canvas.
 * <p>
 * The selection is always bound to one dimension and stores packed chunk
 * positions, so a rectangle drag only has to add or remove the keys of the
 * covered chunks.
 */
public final class ClaimSelection {
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private String dimension = "";

    /** Clears the selection when the map switched to another dimension. */
    public void ensureDimension(String dimension) {
        String key = dimension == null ? "" : dimension;
        if (key.equals(this.dimension)) {
            return;
        }
        this.dimension = key;
        this.chunks.clear();
    }

    public boolean isEmpty() {
        return this.chunks.isEmpty();
    }

    public int size() {
        return this.chunks.size();
    }

    public LongSet chunks() {
        return this.chunks;
    }

    public void clear() {
        this.chunks.clear();
    }

    public void set(LongSet keys) {
        this.chunks.clear();
        this.chunks.addAll(keys);
    }

    public void addAll(LongSet keys) {
        this.chunks.addAll(keys);
    }

    public void removeAll(LongSet keys) {
        this.chunks.removeAll(keys);
    }

    /** Chunk coordinates of an already covered rectangle. */
    public static LongOpenHashSet keysOf(int minX, int minZ, int maxX, int maxZ) {
        LongOpenHashSet keys = new LongOpenHashSet();
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                keys.add(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
        return keys;
    }
}
