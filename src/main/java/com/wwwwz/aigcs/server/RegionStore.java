package com.wwwwz.aigcs.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * One 32x32 chunk region file of a single dimension.
 * <p>
 * Region files are stored below the world folder and are read/written in a
 * compact, gzip compressed binary format. Keeping one file per 32x32 chunk
 * block means loading a single region stays cheap even for very large worlds.
 */
@SuppressWarnings("null")
final class RegionStore {
    static final int SHIFT = 5;
    static final int SIZE = 1 << SHIFT;

    private static final int MAGIC = 0x4D344352; // "M4CR"
    private static final int VERSION = 1;
    private static final int FLAG_TILE = 1;
    private static final int FLAG_ENTITIES = 2;

    final int regionX;
    final int regionZ;
    final long regionKey;
    final Path file;

    private final Long2ObjectOpenHashMap<StoredChunk> chunks = new Long2ObjectOpenHashMap<>();
    /** Set when the in-memory data is newer than the file on disk. */
    volatile boolean dirty;
    volatile long lastAccess = System.currentTimeMillis();

    private RegionStore(Path file, int regionX, int regionZ) {
        this.file = file;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.regionKey = ChunkPos.asLong(regionX, regionZ);
    }

    static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".m4cr";
    }

    static RegionStore load(Path dimensionDir, int regionX, int regionZ) {
        Path file = dimensionDir.resolve(fileName(regionX, regionZ));
        RegionStore store = new RegionStore(file, regionX, regionZ);
        if (!Files.isRegularFile(file)) {
            return store;
        }
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                AIGCsM4CnE.LOGGER.warn("Ignoring unsupported chunk region file {}", file);
                return new RegionStore(file, regionX, regionZ);
            }
            int storedRegionX = in.readInt();
            int storedRegionZ = in.readInt();
            if (storedRegionX != regionX || storedRegionZ != regionZ) {
                AIGCsM4CnE.LOGGER.warn("Region file {} contains region {}:{}", file, storedRegionX, storedRegionZ);
                return new RegionStore(file, regionX, regionZ);
            }
            int count = in.readInt();
            if (count < 0 || count > SIZE * SIZE) {
                throw new IOException("Invalid chunk count " + count);
            }
            for (int i = 0; i < count; i++) {
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                StoredChunk chunk = new StoredChunk(chunkX, chunkZ);
                chunk.revision = in.readLong();
                chunk.updatedAt = in.readLong();
                int flags = in.readUnsignedByte();
                if ((flags & FLAG_TILE) != 0) {
                    chunk.tile = ChunkTile.readFrom(in);
                }
                if ((flags & FLAG_ENTITIES) != 0) {
                    chunk.entities = EntityCensus.readFrom(in);
                }
                store.chunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
            }
        } catch (Exception exception) {
            AIGCsM4CnE.LOGGER.error("Failed to read chunk region file {}", file, exception);
            return new RegionStore(file, regionX, regionZ);
        }
        return store;
    }

    synchronized StoredChunk getOrCreate(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        StoredChunk chunk = this.chunks.get(key);
        if (chunk == null) {
            chunk = new StoredChunk(chunkX, chunkZ);
            this.chunks.put(key, chunk);
        }
        return chunk;
    }

    synchronized StoredChunk get(int chunkX, int chunkZ) {
        return this.chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    synchronized int chunkCount() {
        return this.chunks.size();
    }

    synchronized void forEachChunk(Consumer<StoredChunk> consumer) {
        for (StoredChunk chunk : this.chunks.values()) {
            consumer.accept(chunk);
        }
    }

    /** Aggregates {@code [chunkCount, entityCount]} and the per-type totals. */
    synchronized void aggregate(long[] totals, Map<ResourceLocation, Integer> entityCounts) {
        for (StoredChunk chunk : this.chunks.values()) {
            totals[0]++;
            EntityCensus census = chunk.entities;
            if (census == null) {
                continue;
            }
            totals[1] += census.total();
            for (Map.Entry<ResourceLocation, Integer> entry : census.counts().entrySet()) {
                entityCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
    }

    /** Serialises the whole region into its on-disk representation. */
    synchronized byte[] serialize() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(this.regionX);
            out.writeInt(this.regionZ);
            out.writeInt(this.chunks.size());
            for (StoredChunk chunk : this.chunks.values()) {
                out.writeInt(chunk.chunkX);
                out.writeInt(chunk.chunkZ);
                out.writeLong(chunk.revision);
                out.writeLong(chunk.updatedAt);
                int flags = 0;
                if (chunk.tile != null) {
                    flags |= FLAG_TILE;
                }
                if (chunk.entities != null) {
                    flags |= FLAG_ENTITIES;
                }
                out.writeByte(flags);
                if (chunk.tile != null) {
                    chunk.tile.writeTo(out);
                }
                if (chunk.entities != null) {
                    chunk.entities.writeTo(out);
                }
            }
        }
        return bytes.toByteArray();
    }
}
