package com.wwwwz.aigcs.client.map;

import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * Slime chunk lookup based on the world seed.
 * <p>
 * The client never receives the level seed from the vanilla protocol, so the
 * mod learns it from the integrated server in single player and through a tiny
 * payload from a dedicated server. Slime chunks are then computed exactly like
 * the vanilla slime spawn logic does and cached per dimension.
 */
@SuppressWarnings("null")
public final class SlimeChunkCache {
    private static final byte UNKNOWN = 0;
    private static final byte SLIME = 1;
    private static final byte NOT_SLIME = 2;

    private static final SlimeChunkCache INSTANCE = new SlimeChunkCache();

    private final Map<ResourceLocation, Long> seeds = new HashMap<>();
    private final Map<ResourceLocation, Long2ByteOpenHashMap> cache = new HashMap<>();

    private SlimeChunkCache() {
    }

    public static SlimeChunkCache get() {
        return INSTANCE;
    }

    public void setSeed(ResourceLocation dimension, long seed) {
        Long previous = this.seeds.put(dimension, seed);
        if (previous == null || previous.longValue() != seed) {
            this.cache.remove(dimension);
        }
    }

    public boolean hasSeed(ResourceLocation dimension) {
        return this.seeds.containsKey(dimension);
    }

    public Long seed(ResourceLocation dimension) {
        return this.seeds.get(dimension);
    }

    /** {@code true}/{@code false} for a known seed, {@code null} while unknown. */
    public Boolean isSlimeChunk(ResourceLocation dimension, int chunkX, int chunkZ) {
        Long seed = this.seeds.get(dimension);
        if (seed == null) {
            return null;
        }
        Long2ByteOpenHashMap chunks = this.cache.computeIfAbsent(dimension,
                ignored -> new Long2ByteOpenHashMap());
        if (chunks.size() > 65536) {
            chunks.clear();
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        byte cached = chunks.get(key);
        if (cached == UNKNOWN) {
            boolean slime = WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed.longValue(), 987234911L)
                    .nextInt(10) == 0;
            cached = slime ? SLIME : NOT_SLIME;
            chunks.put(key, cached);
        }
        return cached == SLIME;
    }

    /**
     * Reads the seed directly from the integrated server. This keeps single
     * player worlds working without waiting for a network round trip.
     */
    public void ensureSingleplayerSeed() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.hasSingleplayerServer()) {
            return;
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(minecraft.level.dimension());
        if (level != null) {
            this.setSeed(minecraft.level.dimension().location(), level.getSeed());
        }
    }

    public void clear() {
        this.seeds.clear();
        this.cache.clear();
    }
}
