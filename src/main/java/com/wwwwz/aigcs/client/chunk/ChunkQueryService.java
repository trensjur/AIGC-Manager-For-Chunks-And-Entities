package com.wwwwz.aigcs.client.chunk;

import com.wwwwz.aigcs.client.ClientChunkStore;
import com.wwwwz.aigcs.client.map.OreScanCache;
import com.wwwwz.aigcs.client.map.SlimeChunkCache;
import com.wwwwz.aigcs.core.ChunkTile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Collects the detailed information of one requested chunk.
 * <p>
 * The query combines the already recorded map tile and entity census with
 * information that is only available on demand: the biome, the slime chunk
 * state and the ore census of the chunk. Results are kept so the world map can
 * highlight and label the queried chunk without recomputing anything.
 */
@SuppressWarnings("null")
public final class ChunkQueryService {
    public record Report(int chunkX, int chunkZ, boolean recorded, ResourceLocation biome,
            Component biomeName, Boolean slime, Integer height, int entityTotal, int entityTypes,
            OreScanCache.Result ores) {
    }

    private static final ChunkQueryService INSTANCE = new ChunkQueryService();

    private Report report;
    private long queriedAt;

    private ChunkQueryService() {
    }

    public static ChunkQueryService get() {
        return INSTANCE;
    }

    public Report report() {
        return this.report;
    }

    public long queriedAt() {
        return this.queriedAt;
    }

    public void clear() {
        this.report = null;
        this.queriedAt = 0L;
    }

    /**
     * Queries one chunk. The ore census is scanned immediately, so the call
     * blocks for a few milliseconds - it is only triggered by an explicit user
     * action.
     */
    public Report query(ClientLevel level, int chunkX, int chunkZ) {
        ResourceLocation dimension = level.dimension().location();
        SlimeChunkCache.get().ensureSingleplayerSeed();

        ClientChunkStore.Entry entry = ClientChunkStore.get().get(dimension, chunkX, chunkZ);
        ResourceLocation biome = entry != null ? entry.biome() : null;
        Integer height = null;
        int entityTotal = 0;
        int entityTypes = 0;
        if (entry != null) {
            short sampled = entry.tile().heights[ChunkTile.index(8, 8)];
            if (sampled != Short.MIN_VALUE) {
                height = (int) sampled;
            }
            entityTotal = entry.entities().total();
            entityTypes = entry.entities().counts().size();
        }
        if (biome == null) {
            int y = height != null ? height : 64;
            try {
                biome = level.getBiome(new BlockPos((chunkX << 4) + 8, y, (chunkZ << 4) + 8))
                        .unwrapKey().map(key -> key.location()).orElse(null);
            } catch (RuntimeException exception) {
                biome = null;
            }
        }
        Boolean slime = SlimeChunkCache.get().isSlimeChunk(dimension, chunkX, chunkZ);
        OreScanCache.Result ores = level.getChunkSource().hasChunk(chunkX, chunkZ)
                ? OreScanCache.get().scanNow(level, dimension, chunkX, chunkZ)
                : OreScanCache.get().getOrEmpty(dimension, chunkX, chunkZ);

        Component biomeName = biome != null
                ? Component.translatable(biome.toLanguageKey("biome"))
                : Component.translatable("screen.m4cne.query.unknown");
        Report result = new Report(chunkX, chunkZ, entry != null, biome, biomeName, slime, height,
                entityTotal, entityTypes, ores);
        this.report = result;
        this.queriedAt = System.currentTimeMillis();
        return result;
    }
}
