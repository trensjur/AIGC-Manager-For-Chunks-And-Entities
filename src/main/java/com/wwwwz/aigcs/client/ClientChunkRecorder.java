package com.wwwwz.aigcs.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wwwwz.aigcs.client.map.CaveTileCache;
import com.wwwwz.aigcs.client.map.OreScanCache;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;
import com.wwwwz.aigcs.network.ChunkSyncPayload;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side chunk recorder.
 * <p>
 * It keeps a small, budgeted work queue of loaded chunks. A chunk is sampled
 * when it is first seen, when a block update in it was observed (the mixin in
 * {@code LevelChunkMixin} reports those) and periodically to catch entity
 * changes. Only differences are sent to the server.
 * <p>
 * Everything in this class runs on the client thread.
 */
@SuppressWarnings("null")
public final class ClientChunkRecorder {
    private static final long SWEEP_INTERVAL_MS = 1000L;
    private static final int MAX_QUEUE = 16384;

    private static final ClientChunkRecorder INSTANCE = new ClientChunkRecorder();

    /** Last sampled tile hash per chunk. */
    private final Long2LongOpenHashMap tileHashes = new Long2LongOpenHashMap();
    /** Last sampled entity census hash per chunk. */
    private final Long2LongOpenHashMap censusHashes = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap lastScanAt = new Long2LongOpenHashMap();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final ClientChunkSampler sampler = new ClientChunkSampler();

    private String worldKey = "";
    private ResourceLocation dimension;
    private long lastSweepAt;

    private ClientChunkRecorder() {
        this.tileHashes.defaultReturnValue(Long.MIN_VALUE);
        this.censusHashes.defaultReturnValue(Long.MIN_VALUE);
        this.lastScanAt.defaultReturnValue(Long.MIN_VALUE);
    }

    public static ClientChunkRecorder get() {
        return INSTANCE;
    }

    // -------------------------------------------------------------- lifecycle

    public static void onClientTick(ClientTickEvent.Post event) {
        INSTANCE.tick();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.reset();
    }

    /** Drops all queues and caches, used on logout and on world changes. */
    public void reset() {
        this.resetInternal();
    }

    private void resetInternal() {
        this.tileHashes.clear();
        this.censusHashes.clear();
        this.lastScanAt.clear();
        this.queued.clear();
        this.queue.clear();
        this.worldKey = "";
        this.dimension = null;
        this.lastSweepAt = 0L;
        ClientWorldKey.invalidate();
        ClientChunkStore.get().clear();
        CaveTileCache.get().clear();
    }

    /** Asks the next client tick to scan the loaded chunks immediately. */
    public void requestImmediateSweep() {
        this.lastSweepAt = 0L;
    }

    /**
     * Drops the client side sampling caches. The next sweep records every
     * loaded chunk again, which also gives the server a fresh copy.
     */
    public void clearSampledCache() {
        this.tileHashes.clear();
        this.censusHashes.clear();
        this.lastScanAt.clear();
        this.queued.clear();
        this.queue.clear();
        ClientChunkStore.get().clear();
        CaveTileCache.get().clear();
        this.lastSweepAt = 0L;
    }

    // ------------------------------------------------------------------ ticks

    private void tick() {
        if (!ClientConfig.ENABLED.getAsBoolean()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            this.resetInternal();
            return;
        }
        if (minecraft.getConnection() == null) {
            this.resetInternal();
            return;
        }

        String world = ClientWorldKey.current();
        ResourceLocation currentDimension = level.dimension().location();
        if (!world.equals(this.worldKey) || !currentDimension.equals(this.dimension)) {
            this.resetInternal();
            this.worldKey = world;
            this.dimension = currentDimension;
            ClientChunkStore.get().ensureContext(world, currentDimension);
        }

        long now = System.currentTimeMillis();
        if (now - this.lastSweepAt >= SWEEP_INTERVAL_MS) {
            this.lastSweepAt = now;
            this.sweep(level, minecraft, now);
        }
        this.processQueue(level, now);
    }

    private void sweep(ClientLevel level, Minecraft minecraft, long now) {
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        int radius = Math.max(2, Math.min(32, minecraft.options.renderDistance().get() + 1));
        ChunkPos center = player.chunkPosition();
        int rescanMillis = Math.max(2, ClientConfig.RESCAN_INTERVAL_SECONDS.getAsInt()) * 1000;

        List<Long> candidates = new ArrayList<>();
        for (int deltaX = -radius; deltaX <= radius; deltaX++) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                int chunkX = center.x + deltaX;
                int chunkZ = center.z + deltaZ;
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                long key = ChunkPos.asLong(chunkX, chunkZ);
                if (this.queued.contains(key)) {
                    continue;
                }
                if (this.lastScanAt.containsKey(key) && now - this.lastScanAt.get(key) < rescanMillis) {
                    continue;
                }
                candidates.add(key);
            }
        }
        candidates.sort(Comparator.comparingLong(key -> {
            long deltaX = ChunkPos.getX(key) - center.x;
            long deltaZ = ChunkPos.getZ(key) - center.z;
            return deltaX * deltaX + deltaZ * deltaZ;
        }));
        for (Long key : candidates) {
            this.enqueue(key, false);
        }
    }

    private void processQueue(ClientLevel level, long now) {
        if (this.queue.isEmpty()) {
            return;
        }
        int budget = ClientConfig.UPDATE_BUDGET_PER_TICK.getAsInt();
        List<Long> batch = new ArrayList<>(budget);
        while (batch.size() < budget) {
            Long key = this.queue.poll();
            if (key == null) {
                break;
            }
            this.queued.remove(key.longValue());
            batch.add(key);
        }
        if (batch.isEmpty()) {
            return;
        }

        Map<Long, EntityCensus> censuses = this.buildCensus(level, batch);
        for (Long key : batch) {
            this.processChunk(level, key, censuses.getOrDefault(key, EntityCensus.empty()), now);
        }
    }

    private void processChunk(ClientLevel level, long key, EntityCensus census, long now) {
        int chunkX = ChunkPos.getX(key);
        int chunkZ = ChunkPos.getZ(key);
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            // Keep the old hashes: the sweep will put the chunk back once it loads.
            return;
        }

        ChunkTile tile = new ChunkTile();
        this.sampler.sample(level, chunkX, chunkZ, tile);

        long tileHash = tile.fingerprint();
        long censusHash = census.fingerprint();
        boolean tileChanged = !this.tileHashes.containsKey(key) || this.tileHashes.get(key) != tileHash;
        boolean censusChanged = !this.censusHashes.containsKey(key) || this.censusHashes.get(key) != censusHash;

        if (tileChanged) {
            this.tileHashes.put(key, tileHash);
        }
        if (censusChanged) {
            this.censusHashes.put(key, censusHash);
        }
        this.lastScanAt.put(key, now);

        // Both hashes were computed above, so the store does not have to hash the
        // tile and the census a second time.
        ClientChunkStore.get().put(this.dimension, chunkX, chunkZ, tile, census,
                this.sampleBiome(level, chunkX, chunkZ, tile), tileHash, censusHash);

        if (tileChanged || censusChanged) {
            PacketDistributor.sendToServer(ChunkSyncPayload.of(this.dimension, chunkX, chunkZ,
                    tileChanged ? tile : null, censusChanged ? census : null));
        }
    }

    /**
     * Resolves the biome at the sampled surface of a chunk. The value is only
     * cached on the client (it never travels with the chunk payload) and feeds
     * the biome overlay and the chunk information query.
     */
    private ResourceLocation sampleBiome(ClientLevel level, int chunkX, int chunkZ, ChunkTile tile) {
        try {
            short height = tile.heights[ChunkTile.index(8, 8)];
            int sampleY = height != Short.MIN_VALUE ? height : 64;
            BlockPos pos = new BlockPos((chunkX << 4) + 8, sampleY, (chunkZ << 4) + 8);
            return level.getBiome(pos).unwrapKey().map(key -> key.location()).orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Counts all entities of the requested chunks in one pass. The batch is
     * small (one tick worth of chunks), so only those chunks allocate a census.
     */
    private Map<Long, EntityCensus> buildCensus(ClientLevel level, List<Long> batch) {
        if (batch.isEmpty() || !ClientConfig.RECORD_ENTITIES.getAsBoolean()) {
            return Map.of();
        }
        LongOpenHashSet wanted = new LongOpenHashSet(batch.size());
        for (Long key : batch) {
            wanted.add(key.longValue());
        }

        boolean includePlayers = ClientConfig.RECORD_PLAYERS.getAsBoolean();
        boolean includeNonLiving = ClientConfig.RECORD_NON_LIVING.getAsBoolean();
        Map<Long, Map<ResourceLocation, Integer>> counts = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved()) {
                continue;
            }
            if (!includePlayers && entity instanceof Player) {
                continue;
            }
            if (!includeNonLiving && !(entity instanceof LivingEntity)) {
                continue;
            }
            ChunkPos chunkPos = entity.chunkPosition();
            long key = ChunkPos.asLong(chunkPos.x, chunkPos.z);
            if (!wanted.contains(key)) {
                continue;
            }
            ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            Map<ResourceLocation, Integer> types = counts.computeIfAbsent(key, ignored -> new HashMap<>());
            if (types.size() >= EntityCensus.MAX_TYPES && !types.containsKey(type)) {
                continue;
            }
            types.merge(type, 1, Integer::sum);
        }

        if (counts.isEmpty()) {
            return Map.of();
        }
        Map<Long, EntityCensus> result = new HashMap<>(counts.size());
        for (Map.Entry<Long, Map<ResourceLocation, Integer>> entry : counts.entrySet()) {
            result.put(entry.getKey(), new EntityCensus(entry.getValue()));
        }
        return result;
    }

    // ----------------------------------------------------------- change hooks

    /**
     * Called from the client chunk mixin for every changed block. A change on a
     * chunk border also affects the relief shading of the neighbouring chunk,
     * so those neighbours are queued as well.
     */
    public void markBlockChanged(ChunkPos chunkPos, BlockPos pos) {
        if (!ClientConfig.ENABLED.getAsBoolean() || this.dimension == null) {
            return;
        }
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;

        CaveTileCache.get().invalidateChunk(chunkX, chunkZ);
        OreScanCache.get().invalidate(chunkX, chunkZ);
        this.enqueue(ChunkPos.asLong(chunkX, chunkZ), true);

        boolean west = localX == 0;
        boolean east = localX == 15;
        boolean north = localZ == 0;
        boolean south = localZ == 15;
        if (west) {
            this.enqueue(ChunkPos.asLong(chunkX - 1, chunkZ), true);
        }
        if (east) {
            this.enqueue(ChunkPos.asLong(chunkX + 1, chunkZ), true);
        }
        if (north) {
            this.enqueue(ChunkPos.asLong(chunkX, chunkZ - 1), true);
        }
        if (south) {
            this.enqueue(ChunkPos.asLong(chunkX, chunkZ + 1), true);
        }
        if (west && north) {
            this.enqueue(ChunkPos.asLong(chunkX - 1, chunkZ - 1), true);
        }
        if (west && south) {
            this.enqueue(ChunkPos.asLong(chunkX - 1, chunkZ + 1), true);
        }
        if (east && north) {
            this.enqueue(ChunkPos.asLong(chunkX + 1, chunkZ - 1), true);
        }
        if (east && south) {
            this.enqueue(ChunkPos.asLong(chunkX + 1, chunkZ + 1), true);
        }
    }

    private void enqueue(long key, boolean urgent) {
        if (!urgent && this.queue.size() >= MAX_QUEUE) {
            return;
        }
        if (this.queued.add(key)) {
            if (urgent) {
                this.queue.addFirst(key);
            } else {
                this.queue.addLast(key);
            }
        }
    }
}
