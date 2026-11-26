package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.preload.PreloadController;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client snapshot of the preload task.
 * <p>
 * It is sent on every control action and then a few times per second while a
 * task is running, so the world map panel always shows the live counters.
 * {@code hint} carries a small status code the client turns into a localised
 * line (see {@code screen.m4cne.preload.hint.*}).
 * <p>
 * {@code permitted} tells the client whether it may control the feature; the
 * server re-checks the permission of every control payload, so the flag only
 * drives the UI. {@code skipped} counts the chunks of the last sync that the
 * server did not send because the client already had them.
 */
@SuppressWarnings("null")
public record PreloadStatusPayload(
        int state,
        ResourceLocation dimension,
        int centerChunkX,
        int centerChunkZ,
        int radius,
        int shape,
        int completed,
        int total,
        int imported,
        int importTotal,
        int threads,
        int priority,
        int memoryMb,
        boolean c2me,
        boolean chunky,
        int skipped,
        boolean permitted,
        int permissionLevel,
        int hint) implements CustomPacketPayload {

    public static final int HINT_NONE = 0;
    public static final int HINT_DIMENSION_UNAVAILABLE = 1;
    public static final int HINT_CHUNKY_ACTIVE = 2;
    public static final int HINT_C2ME_ACTIVE = 3;
    public static final int HINT_MEMORY_LIMIT = 4;
    public static final int HINT_NO_TASK = 5;
    public static final int HINT_NO_PERMISSION = 6;
    public static final int HINT_NOT_READY = 7;

    public static final CustomPacketPayload.Type<PreloadStatusPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "preload_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PreloadStatusPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.state);
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeInt(payload.centerChunkX);
                buffer.writeInt(payload.centerChunkZ);
                buffer.writeVarInt(payload.radius);
                buffer.writeVarInt(payload.shape);
                buffer.writeVarInt(payload.completed);
                buffer.writeVarInt(payload.total);
                buffer.writeVarInt(payload.imported);
                buffer.writeVarInt(payload.importTotal);
                buffer.writeVarInt(payload.threads);
                buffer.writeVarInt(payload.priority);
                buffer.writeVarInt(payload.memoryMb);
                buffer.writeBoolean(payload.c2me);
                buffer.writeBoolean(payload.chunky);
                buffer.writeVarInt(payload.skipped);
                buffer.writeBoolean(payload.permitted);
                buffer.writeVarInt(payload.permissionLevel);
                buffer.writeVarInt(payload.hint);
            },
            buffer -> new PreloadStatusPayload(
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
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PreloadStatusPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PreloadController.get().onStatus(payload));
    }
}
