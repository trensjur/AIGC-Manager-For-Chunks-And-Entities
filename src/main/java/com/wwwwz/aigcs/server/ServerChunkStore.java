package com.wwwwz.aigcs.server;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;
import com.wwwwz.aigcs.network.ChunkSyncPayload;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Owns the per-save chunk repository for the currently running server.
 * <p>
 * The repository is created on server start and stored below the world
 * directory, which is what isolates different save games from each other.
 */
@SuppressWarnings("null")
public final class ServerChunkStore {
    private static final int MAX_CHUNK_COORDINATE = 1_875_000;

    private static volatile ServerChunkStore active;

    private final MinecraftServer server;
    private final ChunkRepository repository;
    private final Map<UUID, SyncRateLimiter> rateLimiters = new HashMap<>();

    private int tickCounter;

    private ServerChunkStore(MinecraftServer server) {
        this.server = server;
        this.repository = new ChunkRepository(server.getWorldPath(LevelResource.ROOT).resolve("m4cne"));
    }

    public static ServerChunkStore get() {
        return active;
    }

    public static void onServerStarted(ServerStartedEvent event) {
        ServerChunkStore store = new ServerChunkStore(event.getServer());
        active = store;
        AIGCsM4CnE.LOGGER.info("Chunk repository ready at {}", store.repository.root());
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        ServerChunkStore store = active;
        if (store != null && store.server == event.getServer()) {
            active = null;
            store.repository.close();
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerChunkStore store = active;
        if (store != null) {
            store.tick();
        }
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerChunkStore store = active;
        if (store != null && event.getEntity() instanceof ServerPlayer player) {
            store.rateLimiters.remove(player.getUUID());
        }
    }

    public static void handleSync(ServerPlayer player, ChunkSyncPayload payload) {
        ServerChunkStore store = active;
        if (store != null && store.server == player.server) {
            store.accept(player, payload);
        }
    }

    private void accept(ServerPlayer player, ChunkSyncPayload payload) {
        if (!Config.ACCEPT_CLIENT_DATA.getAsBoolean()) {
            return;
        }
        if (Math.abs(payload.chunkX()) > MAX_CHUNK_COORDINATE || Math.abs(payload.chunkZ()) > MAX_CHUNK_COORDINATE) {
            return;
        }

        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, payload.dimension());
        if (!this.server.levelKeys().contains(dimension)) {
            return;
        }
        if (!player.level().dimension().equals(dimension)) {
            // A client may only report chunks of the dimension it is actually in.
            return;
        }

        ChunkPos playerChunk = player.chunkPosition();
        long deltaX = (long) payload.chunkX() - playerChunk.x;
        long deltaZ = (long) payload.chunkZ() - playerChunk.z;
        int maxDistance = Config.MAX_SYNC_DISTANCE_CHUNKS.getAsInt();
        if (deltaX * deltaX + deltaZ * deltaZ > (long) maxDistance * maxDistance) {
            return;
        }

        SyncRateLimiter limiter = this.rateLimiters.computeIfAbsent(player.getUUID(),
                id -> new SyncRateLimiter(Config.MAX_CHUNKS_PER_SECOND_PER_PLAYER.getAsInt()));
        if (!limiter.tryConsume(1)) {
            return;
        }

        ChunkTile tile = payload.toTile();
        EntityCensus census = Config.RECORD_ENTITIES.getAsBoolean() ? payload.toCensus() : null;
        if (tile == null && census == null) {
            return;
        }
        if (census != null) {
            census = this.sanitize(census);
        }
        this.repository.upsert(payload.dimension(), payload.chunkX(), payload.chunkZ(), tile, census);
    }

    private EntityCensus sanitize(EntityCensus census) {
        if (census.isEmpty()) {
            return EntityCensus.empty();
        }
        int maxTypes = Config.MAX_ENTITY_TYPES_PER_CHUNK.getAsInt();
        Map<ResourceLocation, Integer> filtered = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Integer> entry : census.counts().entrySet()) {
            if (filtered.size() >= maxTypes) {
                break;
            }
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(entry.getKey())) {
                filtered.put(entry.getKey(), Math.min(entry.getValue(), EntityCensus.MAX_COUNT));
            }
        }
        return filtered.isEmpty() ? EntityCensus.empty() : new EntityCensus(filtered);
    }

    private void tick() {
        int intervalSeconds = Math.max(10, Config.AUTOSAVE_SECONDS.getAsInt());
        if (++this.tickCounter < intervalSeconds * 20) {
            return;
        }
        this.tickCounter = 0;
        int regions = this.repository.saveDirtyAsync();
        if (regions > 0) {
            AIGCsM4CnE.LOGGER.debug("Autosaved {} chunk region(s)", regions);
        }
    }

    public List<DimensionStats> stats() {
        return this.repository.stats();
    }

    public StoredChunk getChunk(ResourceLocation dimension, int chunkX, int chunkZ) {
        return this.repository.getChunk(dimension, chunkX, chunkZ);
    }

    /**
     * Stores a tile/census pair that was sampled by the server itself, used by
     * the preload task which never goes through a client payload.
     */
    public void storeChunk(ResourceLocation dimension, int chunkX, int chunkZ, ChunkTile tile, EntityCensus census) {
        if (Math.abs(chunkX) > MAX_CHUNK_COORDINATE || Math.abs(chunkZ) > MAX_CHUNK_COORDINATE) {
            return;
        }
        if (tile == null && (census == null || census.isEmpty())) {
            return;
        }
        this.repository.upsert(dimension, chunkX, chunkZ, tile,
                census != null && !census.isEmpty() ? this.sanitize(census) : null);
    }

    public int saveNow() {
        return this.repository.saveDirtyAsync();
    }
}
