package com.wwwwz.aigcs.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.preload.PreloadController;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;
import com.wwwwz.aigcs.core.EntityCount;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client batch of preloaded chunk data.
 * <p>
 * The payload feeds the "import preloaded chunks and draw the map" feature: the
 * server streams the sampled map tiles (and the entity census when it knows it)
 * of the preload region to the client, which stores them in the normal client
 * chunk cache. The map therefore renders those chunks even though they are far
 * outside of the player's render distance.
 */
@SuppressWarnings("null")
public record PreloadChunkPayload(ResourceLocation dimension, List<Entry> chunks) implements CustomPacketPayload {
    /**
     * Chunks per payload; the per tick budget decides how many payloads are sent.
     */
    public static final int MAX_CHUNKS = 32;
    private static final int MAX_ENTITY_TYPES = EntityCensus.MAX_TYPES;
    private static final int[] EMPTY_INTS = new int[0];
    private static final short[] EMPTY_SHORTS = new short[0];
    private static final byte[] EMPTY_BYTES = new byte[0];

    public record Entry(int chunkX, int chunkZ, int[] colors, short[] heights, byte[] shade, byte[] skyLight,
            byte[] blockLight, List<EntityCount> entities) {
        public Entry {
            colors = colors != null ? colors : EMPTY_INTS;
            heights = heights != null ? heights : EMPTY_SHORTS;
            shade = shade != null ? shade : EMPTY_BYTES;
            skyLight = skyLight != null ? skyLight : EMPTY_BYTES;
            blockLight = blockLight != null ? blockLight : EMPTY_BYTES;
            entities = entities != null ? List.copyOf(entities) : List.of();
            if (colors.length != ChunkTile.AREA || heights.length != ChunkTile.AREA
                    || shade.length != ChunkTile.AREA || skyLight.length != ChunkTile.AREA
                    || blockLight.length != ChunkTile.AREA) {
                throw new IllegalArgumentException("tile arrays must have " + ChunkTile.AREA + " entries");
            }
            if (entities.size() > MAX_ENTITY_TYPES) {
                throw new IllegalArgumentException("too many entity types: " + entities.size());
            }
        }

        public ChunkTile toTile() {
            ChunkTile tile = new ChunkTile();
            System.arraycopy(this.colors, 0, tile.colors, 0, ChunkTile.AREA);
            System.arraycopy(this.heights, 0, tile.heights, 0, ChunkTile.AREA);
            System.arraycopy(this.shade, 0, tile.shade, 0, ChunkTile.AREA);
            System.arraycopy(this.skyLight, 0, tile.skyLight, 0, ChunkTile.AREA);
            System.arraycopy(this.blockLight, 0, tile.blockLight, 0, ChunkTile.AREA);
            return tile;
        }

        public EntityCensus toCensus() {
            Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
            for (EntityCount count : this.entities) {
                if (count.type() != null && count.count() > 0) {
                    counts.merge(count.type(), count.count(), Integer::sum);
                }
            }
            return counts.isEmpty() ? EntityCensus.empty() : new EntityCensus(counts);
        }
    }

    public static final CustomPacketPayload.Type<PreloadChunkPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "preload_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PreloadChunkPayload> STREAM_CODEC = StreamCodec.of(
            PreloadChunkPayload::write,
            PreloadChunkPayload::decode);

    public PreloadChunkPayload {
        chunks = chunks != null ? List.copyOf(chunks) : List.of();
        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("too many chunks in one payload: " + chunks.size());
        }
    }

    private static void write(RegistryFriendlyByteBuf buffer, PreloadChunkPayload payload) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeVarInt(payload.chunks.size());
        for (Entry entry : payload.chunks) {
            buffer.writeInt(entry.chunkX());
            buffer.writeInt(entry.chunkZ());
            for (int color : entry.colors()) {
                buffer.writeInt(color);
            }
            for (short height : entry.heights()) {
                buffer.writeShort(height);
            }
            buffer.writeByteArray(entry.shade());
            buffer.writeByteArray(entry.skyLight());
            buffer.writeByteArray(entry.blockLight());
            buffer.writeVarInt(entry.entities().size());
            for (EntityCount count : entry.entities()) {
                buffer.writeUtf(count.type().toString(), 256);
                buffer.writeVarInt(count.count());
            }
        }
    }

    private static PreloadChunkPayload decode(RegistryFriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_CHUNKS) {
            throw new DecoderException("Invalid preload chunk count " + size);
        }
        Entry[] entries = new Entry[size];
        for (int index = 0; index < size; index++) {
            int chunkX = buffer.readInt();
            int chunkZ = buffer.readInt();
            int[] colors = new int[ChunkTile.AREA];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = buffer.readInt();
            }
            short[] heights = new short[ChunkTile.AREA];
            for (int i = 0; i < heights.length; i++) {
                heights[i] = buffer.readShort();
            }
            byte[] shade = readChannel(buffer, "shade");
            byte[] skyLight = readChannel(buffer, "skyLight");
            byte[] blockLight = readChannel(buffer, "blockLight");
            int entityCount = buffer.readVarInt();
            if (entityCount < 0 || entityCount > MAX_ENTITY_TYPES) {
                throw new DecoderException("Invalid entity census size " + entityCount);
            }
            EntityCount[] entities = new EntityCount[entityCount];
            for (int i = 0; i < entityCount; i++) {
                entities[i] = new EntityCount(ResourceLocation.tryParse(buffer.readUtf(256)), buffer.readVarInt());
            }
            entries[index] = new Entry(chunkX, chunkZ, colors, heights, shade, skyLight, blockLight,
                    List.of(entities));
        }
        return new PreloadChunkPayload(dimension, List.of(entries));
    }

    private static byte[] readChannel(RegistryFriendlyByteBuf buffer, String name) {
        byte[] data = buffer.readByteArray(ChunkTile.AREA);
        if (data.length != ChunkTile.AREA) {
            throw new DecoderException("Invalid " + name + " length " + data.length);
        }
        return data;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PreloadChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PreloadController.get().acceptChunks(payload));
    }
}
