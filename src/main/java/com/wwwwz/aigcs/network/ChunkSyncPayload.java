package com.wwwwz.aigcs.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;
import com.wwwwz.aigcs.core.EntityCount;
import com.wwwwz.aigcs.server.ServerChunkStore;
import com.wwwwz.aigcs.server.preload.ServerPreloadManager;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server chunk record.
 * <p>
 * The client is the only side that can cheaply sample the chunks it has already
 * loaded, so it sends the sampled map tile and/or the entity census whenever
 * something changed. Both parts are optional: an entity-only update does not
 * repeat the (much larger) tile arrays.
 */
@SuppressWarnings("null")
public record ChunkSyncPayload(
        ResourceLocation dimension,
        int chunkX,
        int chunkZ,
        boolean hasTile,
        int[] colors,
        short[] heights,
        byte[] shade,
        byte[] skyLight,
        byte[] blockLight,
        boolean hasEntities,
        List<EntityCount> entities) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChunkSyncPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "chunk_sync"));

    private static final int[] EMPTY_INTS = new int[0];
    private static final short[] EMPTY_SHORTS = new short[0];
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int MAX_ENTITY_TYPES = EntityCensus.MAX_TYPES;

    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkSyncPayload> STREAM_CODEC = StreamCodec.of(
            ChunkSyncPayload::write,
            ChunkSyncPayload::decode);

    public ChunkSyncPayload {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        colors = colors != null ? colors : EMPTY_INTS;
        heights = heights != null ? heights : EMPTY_SHORTS;
        shade = shade != null ? shade : EMPTY_BYTES;
        skyLight = skyLight != null ? skyLight : EMPTY_BYTES;
        blockLight = blockLight != null ? blockLight : EMPTY_BYTES;
        entities = entities != null ? List.copyOf(entities) : List.of();
        if (hasTile && (colors.length != ChunkTile.AREA
                || heights.length != ChunkTile.AREA
                || shade.length != ChunkTile.AREA
                || skyLight.length != ChunkTile.AREA
                || blockLight.length != ChunkTile.AREA)) {
            throw new IllegalArgumentException("tile arrays must have " + ChunkTile.AREA + " entries");
        }
        if (hasEntities && entities.size() > MAX_ENTITY_TYPES) {
            throw new IllegalArgumentException("too many entity types: " + entities.size());
        }
    }

    /**
     * Builds a payload containing only the parts that changed. Passing
     * {@code null} for a part omits it.
     */
    public static ChunkSyncPayload of(ResourceLocation dimension, int chunkX, int chunkZ,
            ChunkTile tile, EntityCensus census) {
        if (tile == null && census == null) {
            throw new IllegalArgumentException("payload must contain a tile or a census");
        }
        return new ChunkSyncPayload(
                dimension,
                chunkX,
                chunkZ,
                tile != null,
                tile != null ? tile.colors : EMPTY_INTS,
                tile != null ? tile.heights : EMPTY_SHORTS,
                tile != null ? tile.shade : EMPTY_BYTES,
                tile != null ? tile.skyLight : EMPTY_BYTES,
                tile != null ? tile.blockLight : EMPTY_BYTES,
                census != null,
                census != null ? census.toList() : List.of());
    }

    public ChunkTile toTile() {
        if (!this.hasTile) {
            return null;
        }
        ChunkTile tile = new ChunkTile();
        System.arraycopy(this.colors, 0, tile.colors, 0, ChunkTile.AREA);
        System.arraycopy(this.heights, 0, tile.heights, 0, ChunkTile.AREA);
        System.arraycopy(this.shade, 0, tile.shade, 0, ChunkTile.AREA);
        System.arraycopy(this.skyLight, 0, tile.skyLight, 0, ChunkTile.AREA);
        System.arraycopy(this.blockLight, 0, tile.blockLight, 0, ChunkTile.AREA);
        return tile;
    }

    public EntityCensus toCensus() {
        if (!this.hasEntities) {
            return null;
        }
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (EntityCount entry : this.entities) {
            if (entry.type() != null && entry.count() > 0) {
                counts.merge(entry.type(), entry.count(), Integer::sum);
            }
        }
        return new EntityCensus(counts);
    }

    private static void write(RegistryFriendlyByteBuf buffer, ChunkSyncPayload payload) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeInt(payload.chunkX);
        buffer.writeInt(payload.chunkZ);

        buffer.writeBoolean(payload.hasTile);
        if (payload.hasTile) {
            for (int color : payload.colors) {
                buffer.writeInt(color);
            }
            for (short height : payload.heights) {
                buffer.writeShort(height);
            }
            buffer.writeByteArray(payload.shade);
            buffer.writeByteArray(payload.skyLight);
            buffer.writeByteArray(payload.blockLight);
        }

        buffer.writeBoolean(payload.hasEntities);
        if (payload.hasEntities) {
            buffer.writeVarInt(payload.entities.size());
            for (EntityCount entry : payload.entities) {
                buffer.writeUtf(entry.type().toString(), 256);
                buffer.writeVarInt(entry.count());
            }
        }
    }

    private static ChunkSyncPayload decode(RegistryFriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        int chunkX = buffer.readInt();
        int chunkZ = buffer.readInt();

        boolean hasTile = buffer.readBoolean();
        int[] colors = EMPTY_INTS;
        short[] heights = EMPTY_SHORTS;
        byte[] shade = EMPTY_BYTES;
        byte[] skyLight = EMPTY_BYTES;
        byte[] blockLight = EMPTY_BYTES;
        if (hasTile) {
            colors = new int[ChunkTile.AREA];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = buffer.readInt();
            }
            heights = new short[ChunkTile.AREA];
            for (int i = 0; i < heights.length; i++) {
                heights[i] = buffer.readShort();
            }
            shade = readChannel(buffer, "shade");
            skyLight = readChannel(buffer, "skyLight");
            blockLight = readChannel(buffer, "blockLight");
        }

        boolean hasEntities = buffer.readBoolean();
        List<EntityCount> entities = List.of();
        if (hasEntities) {
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_ENTITY_TYPES) {
                throw new DecoderException("Invalid entity census size " + size);
            }
            EntityCount[] decoded = new EntityCount[size];
            for (int i = 0; i < size; i++) {
                ResourceLocation type = ResourceLocation.tryParse(buffer.readUtf(256));
                int count = buffer.readVarInt();
                decoded[i] = new EntityCount(type, count);
            }
            entities = List.of(decoded);
        }

        return new ChunkSyncPayload(dimension, chunkX, chunkZ, hasTile, colors, heights, shade, skyLight,
                blockLight, hasEntities, entities);
    }

    private static byte[] readChannel(RegistryFriendlyByteBuf buffer, String name) {
        byte[] data = buffer.readByteArray(ChunkTile.AREA);
        if (data.length != ChunkTile.AREA) {
            throw new DecoderException("Invalid " + name + " length " + data.length);
        }
        return data;
    }

    public static void handle(ChunkSyncPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                ServerChunkStore.handleSync(player, payload);
                // The preload sync uses this knowledge to skip chunks the
                // client has already loaded and recorded itself.
                ServerPreloadManager.onClientChunkSync(player, payload.dimension(), payload.chunkX(),
                        payload.chunkZ());
            });
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
