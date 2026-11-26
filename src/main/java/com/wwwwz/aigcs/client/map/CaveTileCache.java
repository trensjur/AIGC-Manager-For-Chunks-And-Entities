package com.wwwwz.aigcs.client.map;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wwwwz.aigcs.client.ClientChunkSampler;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.core.ChunkTile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;

/**
 * Small LRU cache of cave slices.
 * <p>
 * Cave slices depend on the player height, so they cannot be produced by the
 * normal background recorder. The world map asks for the slices it currently
 * shows and samples a handful of missing chunks every client tick. Everything
 * in this class runs on the client thread.
 */
public final class CaveTileCache {
    private record Key(ResourceLocation dimension, int chunkX, int chunkZ, int sliceY) {
    }

    private static final class Cached {
        private final ChunkTile tile;
        private final long sampledAt;

        private Cached(ChunkTile tile, long sampledAt) {
            this.tile = tile;
            this.sampledAt = sampledAt;
        }
    }

    private static final CaveTileCache INSTANCE = new CaveTileCache();

    private final Map<Key, Cached> cache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return this.size() > ClientConfig.MAP_CAVE_CACHE_CHUNKS.get();
        }
    };
    private final ClientChunkSampler sampler = new ClientChunkSampler();
    private String worldKey = "";

    private CaveTileCache() {
    }

    public static CaveTileCache get() {
        return INSTANCE;
    }

    public void ensureWorld(String world) {
        if (!world.equals(this.worldKey)) {
            this.worldKey = world;
            this.cache.clear();
        }
    }

    /** Cached tile or {@code null} when it has not been sampled yet. */
    public ChunkTile get(ResourceLocation dimension, int chunkX, int chunkZ, int sliceY) {
        Key key = new Key(dimension, chunkX, chunkZ, sliceY);
        Cached cached = this.cache.get(key);
        if (cached == null) {
            return null;
        }
        long lifetime = ClientConfig.MAP_CAVE_CACHE_SECONDS.get() * 1000L;
        if (System.currentTimeMillis() - cached.sampledAt > lifetime) {
            this.cache.remove(key);
            return null;
        }
        return cached.tile;
    }

    /**
     * Samples one cave slice if it is missing or expired.
     *
     * @return {@code true} when the map should be redrawn
     */
    public boolean sample(ClientLevel level, ResourceLocation dimension, int chunkX, int chunkZ, int sliceY) {
        Key key = new Key(dimension, chunkX, chunkZ, sliceY);
        Cached cached = this.cache.get(key);
        long lifetime = ClientConfig.MAP_CAVE_CACHE_SECONDS.get() * 1000L;
        if (cached != null && System.currentTimeMillis() - cached.sampledAt <= lifetime) {
            return false;
        }
        ChunkTile tile = new ChunkTile();
        if (!this.sampler.sampleCave(level, chunkX, chunkZ, sliceY, tile)) {
            return false;
        }
        this.cache.put(key, new Cached(tile, System.currentTimeMillis()));
        return true;
    }

    /** Drops all slices of a chunk after a block change. */
    public void invalidateChunk(int chunkX, int chunkZ) {
        Iterator<Map.Entry<Key, Cached>> iterator = this.cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Key key = iterator.next().getKey();
            if (key.chunkX() == chunkX && key.chunkZ() == chunkZ) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        this.cache.clear();
    }

    public int size() {
        return this.cache.size();
    }
}
