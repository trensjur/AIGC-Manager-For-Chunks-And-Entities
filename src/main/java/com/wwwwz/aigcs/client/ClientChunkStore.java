package com.wwwwz.aigcs.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * Client side cache of every chunk the recorder has sampled.
 * <p>
 * This is the data source for the world map / chunk overview. It only lives in
 * memory and is cleared whenever the client switches worlds, so data of two
 * different saves can never be mixed.
 */
public final class ClientChunkStore {
    /**
     * @param biome             the biome sampled at the chunk centre, {@code null}
     *                          when
     *                          it could not be resolved
     * @param tileFingerprint   fingerprint of {@code tile}, cached so lookups and
     *                          comparison never re-hash the 16x16 tile
     * @param censusFingerprint fingerprint of {@code entities}
     */
    public record Entry(ChunkTile tile, EntityCensus entities, ResourceLocation biome,
            long tileFingerprint, long censusFingerprint) {
    }

    /**
     * Chunks are grouped into 32x32 chunk regions. The map only walks the regions
     * that intersect the visible area, so an unlimited cache stays fast.
     */
    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;
    private static final int REGION_MASK = REGION_SIZE - 1;

    private static final class DimensionStore {
        private final Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<Entry>> regions = new Long2ObjectOpenHashMap<>();
        private int size;
    }

    private static final ClientChunkStore INSTANCE = new ClientChunkStore();

    private final Map<ResourceLocation, DimensionStore> dimensions = new HashMap<>();
    private String worldKey = "";
    private ResourceLocation currentDimension;
    /**
     * Incremented on every mutation so the map texture only rebuilds when needed.
     */
    private long version;

    private ClientChunkStore() {
    }

    public static ClientChunkStore get() {
        return INSTANCE;
    }

    public void ensureContext(String world, ResourceLocation dimension) {
        if (!world.equals(this.worldKey)) {
            this.worldKey = world;
            this.dimensions.clear();
            this.version++;
        }
        this.currentDimension = dimension;
    }

    public void put(ResourceLocation dimension, int chunkX, int chunkZ, ChunkTile tile, EntityCensus entities,
            ResourceLocation biome) {
        this.put(dimension, chunkX, chunkZ, tile, entities, biome, tile.fingerprint(), entities.fingerprint());
    }

    /**
     * Stores a sample whose fingerprints are already known. The recorder computes
     * both hashes anyway, so this avoids hashing every tile twice.
     */
    public void put(ResourceLocation dimension, int chunkX, int chunkZ, ChunkTile tile, EntityCensus entities,
            ResourceLocation biome, long tileFingerprint, long censusFingerprint) {
        DimensionStore store = this.dimensions.computeIfAbsent(dimension, ignored -> new DimensionStore());
        int regionX = chunkX >> REGION_SHIFT;
        int regionZ = chunkZ >> REGION_SHIFT;
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        Int2ObjectOpenHashMap<Entry> region = store.regions.get(regionKey);
        int localKey = ((chunkX & REGION_MASK) << REGION_SHIFT) | (chunkZ & REGION_MASK);
        Entry previous = region != null ? region.get(localKey) : null;
        if (previous != null
                && previous.tileFingerprint() == tileFingerprint
                && previous.censusFingerprint() == censusFingerprint
                && Objects.equals(previous.biome(), biome)) {
            // Identical sample: keep the existing entry. No allocation and no
            // map texture rebuild are needed.
            return;
        }
        if (region == null) {
            region = new Int2ObjectOpenHashMap<>();
            store.regions.put(regionKey, region);
        }
        if (previous == null) {
            store.size++;
        }
        region.put(localKey, new Entry(tile, entities, biome, tileFingerprint, censusFingerprint));
        this.version++;
    }

    public Entry get(ResourceLocation dimension, int chunkX, int chunkZ) {
        DimensionStore store = this.dimensions.get(dimension);
        if (store == null) {
            return null;
        }
        Int2ObjectOpenHashMap<Entry> region = store.regions.get(
                ChunkPos.asLong(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT));
        return region != null
                ? region.get(((chunkX & REGION_MASK) << REGION_SHIFT) | (chunkZ & REGION_MASK))
                : null;
    }

    public Entry getCurrent(int chunkX, int chunkZ) {
        return this.currentDimension != null ? this.get(this.currentDimension, chunkX, chunkZ) : null;
    }

    public int size(ResourceLocation dimension) {
        DimensionStore store = this.dimensions.get(dimension);
        return store != null ? store.size : 0;
    }

    public void forEach(ResourceLocation dimension, BiConsumer<Long, Entry> consumer) {
        DimensionStore store = this.dimensions.get(dimension);
        if (store == null) {
            return;
        }
        for (Long2ObjectMap.Entry<Int2ObjectOpenHashMap<Entry>> regionEntry : store.regions.long2ObjectEntrySet()) {
            long regionKey = regionEntry.getLongKey();
            int baseChunkX = ChunkPos.getX(regionKey) << REGION_SHIFT;
            int baseChunkZ = ChunkPos.getZ(regionKey) << REGION_SHIFT;
            for (Int2ObjectMap.Entry<Entry> chunkEntry : regionEntry.getValue().int2ObjectEntrySet()) {
                int localKey = chunkEntry.getIntKey();
                int chunkX = baseChunkX + (localKey >> REGION_SHIFT);
                int chunkZ = baseChunkZ + (localKey & REGION_MASK);
                consumer.accept(ChunkPos.asLong(chunkX, chunkZ), chunkEntry.getValue());
            }
        }
    }

    /**
     * Visits every cached chunk whose coordinates intersect the inclusive chunk
     * rectangle. Only the overlapping 32x32 regions are visited, so rendering a
     * small view stays cheap even when the cache holds millions of chunks.
     */
    public void forEachRegion(ResourceLocation dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            BiConsumer<Long, Entry> consumer) {
        DimensionStore store = this.dimensions.get(dimension);
        if (store == null) {
            return;
        }
        int minRegionX = minChunkX >> REGION_SHIFT;
        int minRegionZ = minChunkZ >> REGION_SHIFT;
        int maxRegionX = maxChunkX >> REGION_SHIFT;
        int maxRegionZ = maxChunkZ >> REGION_SHIFT;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                Int2ObjectOpenHashMap<Entry> region = store.regions.get(ChunkPos.asLong(regionX, regionZ));
                if (region == null) {
                    continue;
                }
                int baseChunkX = regionX << REGION_SHIFT;
                int baseChunkZ = regionZ << REGION_SHIFT;
                for (Int2ObjectMap.Entry<Entry> chunkEntry : region.int2ObjectEntrySet()) {
                    int localKey = chunkEntry.getIntKey();
                    int chunkX = baseChunkX + (localKey >> REGION_SHIFT);
                    int chunkZ = baseChunkZ + (localKey & REGION_MASK);
                    if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                        continue;
                    }
                    consumer.accept(ChunkPos.asLong(chunkX, chunkZ), chunkEntry.getValue());
                }
            }
        }
    }

    public ResourceLocation currentDimension() {
        return this.currentDimension;
    }

    public String worldKey() {
        return this.worldKey;
    }

    public long version() {
        return this.version;
    }

    public void clear() {
        this.dimensions.clear();
        this.worldKey = "";
        this.currentDimension = null;
        this.version++;
    }
}
