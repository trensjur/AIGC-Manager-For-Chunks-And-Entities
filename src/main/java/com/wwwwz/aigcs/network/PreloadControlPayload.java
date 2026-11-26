package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.server.preload.ServerPreloadManager;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server control message of the chunk preload feature.
 * <p>
 * The client picks the geometry and the performance budget, the server owns the
 * task itself. Every field is validated again on the server; a client can not
 * request a radius or a thread count above the limits of the feature.
 */
@SuppressWarnings("null")
public record PreloadControlPayload(
        int action,
        ResourceLocation dimension,
        int centerChunkX,
        int centerChunkZ,
        int radius,
        int shape,
        int threads,
        int priority,
        int memoryMb,
        int importMode,
        int importBudget) implements CustomPacketPayload {

    public static final int ACTION_START = 0;
    public static final int ACTION_PAUSE = 1;
    public static final int ACTION_RESUME = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_QUERY = 4;
    public static final int ACTION_IMPORT = 5;

    public static final int IMPORT_OFF = 0;
    public static final int IMPORT_CHUNKY = 1;
    public static final int IMPORT_M4CNE = 2;

    public static final CustomPacketPayload.Type<PreloadControlPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "preload_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PreloadControlPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.action);
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeInt(payload.centerChunkX);
                buffer.writeInt(payload.centerChunkZ);
                buffer.writeVarInt(payload.radius);
                buffer.writeVarInt(payload.shape);
                buffer.writeVarInt(payload.threads);
                buffer.writeVarInt(payload.priority);
                buffer.writeVarInt(payload.memoryMb);
                buffer.writeVarInt(payload.importMode);
                buffer.writeVarInt(payload.importBudget);
            },
            buffer -> new PreloadControlPayload(
                    buffer.readVarInt(),
                    buffer.readResourceLocation(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PreloadControlPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> ServerPreloadManager.handleControl(player, payload));
        }
    }
}
