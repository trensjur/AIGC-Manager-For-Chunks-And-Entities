package com.wwwwz.aigcs.client.map;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wwwwz.aigcs.client.ClientConfig;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.common.Tags;

/**
 * Per chunk ore census.
 * <p>
 * Ore veins can only be derived by looking at the actual blocks, so the whole
 * chunk is scanned once and the result is cached. Scanning is budgeted: the map
 * only requests the chunks it currently shows and {@link #tick} scans a small
 * number of them per client tick. A block change invalidates the chunk so the
 * next frame picks the new content up.
 */
@SuppressWarnings("null")
public final class OreScanCache {
    /** @param counts per block id, empty when the chunk contains no ore */
    public record Result(Map<ResourceLocation, Integer> counts, int total, long scannedAt) {
        public boolean isEmpty() {
            return this.counts.isEmpty();
        }
    }

    private static final OreScanCache INSTANCE = new OreScanCache();
    private static final Result EMPTY = new Result(Map.of(), 0, 0L);

    private final Map<ResourceLocation, Long2ObjectOpenHashMap<Result>> results = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private ResourceLocation queueDimension;
    private int resultVersion;

    private OreScanCache() {
    }

    public static OreScanCache get() {
        return INSTANCE;
    }

    public int version() {
        return this.resultVersion;
    }

    /** Cached result or {@code null} when the chunk was not scanned yet. */
    public Result get(ResourceLocation dimension, int chunkX, int chunkZ) {
        Long2ObjectOpenHashMap<Result> chunks = this.results.get(dimension);
        return chunks != null ? chunks.get(ChunkPos.asLong(chunkX, chunkZ)) : null;
    }

    public Result getOrEmpty(ResourceLocation dimension, int chunkX, int chunkZ) {
        Result result = this.get(dimension, chunkX, chunkZ);
        return result != null ? result : EMPTY;
    }

    /** Queues a chunk for a budgeted scan when it has no fresh result yet. */
    public void request(ResourceLocation dimension, int chunkX, int chunkZ) {
        if (this.get(dimension, chunkX, chunkZ) != null) {
            return;
        }
        if (!dimension.equals(this.queueDimension)) {
            this.queue.clear();
            this.queued.clear();
            this.queueDimension = dimension;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (this.queued.add(key) && this.queue.size() < 8192) {
            this.queue.addLast(key);
        }
    }

    /** Scans at most {@code overlay.oreScanBudget} queued chunks. */
    public void tick(ClientLevel level, ResourceLocation dimension) {
        if (this.queue.isEmpty()) {
            return;
        }
        if (!dimension.equals(this.queueDimension)) {
            this.queue.clear();
            this.queued.clear();
            this.queueDimension = dimension;
            return;
        }
        int budget = ClientConfig.OVERLAY_ORE_SCAN_BUDGET.getAsInt();
        while (budget-- > 0) {
            Long key = this.queue.pollFirst();
            if (key == null) {
                break;
            }
            this.queued.remove(key.longValue());
            this.scan(level, dimension, ChunkPos.getX(key), ChunkPos.getZ(key));
        }
    }

    /** Scans a chunk immediately, used by the chunk information query. */
    public Result scanNow(ClientLevel level, ResourceLocation dimension, int chunkX, int chunkZ) {
        Result result = this.scan(level, dimension, chunkX, chunkZ);
        return result != null ? result : EMPTY;
    }

    private Result scan(ClientLevel level, ResourceLocation dimension, int chunkX, int chunkZ) {
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return null;
        }
        LevelChunk chunk;
        try {
            chunk = level.getChunk(chunkX, chunkZ);
        } catch (RuntimeException exception) {
            return null;
        }
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            section.getStates().count((state, count) -> {
                if (state.isAir() || !state.is(Tags.Blocks.ORES)) {
                    return;
                }
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                counts.merge(id, count, Integer::sum);
            });
        }
        int total = 0;
        for (int amount : counts.values()) {
            total += amount;
        }
        Result result = new Result(Map.copyOf(counts), total, System.currentTimeMillis());
        Long2ObjectOpenHashMap<Result> chunks = this.results.computeIfAbsent(dimension,
                ignored -> new Long2ObjectOpenHashMap<>());
        if (chunks.size() > 16384) {
            chunks.clear();
        }
        chunks.put(ChunkPos.asLong(chunkX, chunkZ), result);
        this.resultVersion++;
        return result;
    }

    /** Drops every result of a chunk after a block change. */
    public void invalidate(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        for (Long2ObjectOpenHashMap<Result> chunks : this.results.values()) {
            if (chunks.remove(key) != null) {
                this.resultVersion++;
            }
        }
        this.queued.remove(key);
    }

    public void clear() {
        this.results.clear();
        this.queue.clear();
        this.queued.clear();
        this.queueDimension = null;
        this.resultVersion++;
    }
}
