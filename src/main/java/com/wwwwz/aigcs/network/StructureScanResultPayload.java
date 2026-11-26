package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.map.StructureScanController;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client result of a structure scan. */
@SuppressWarnings("null")
public record StructureScanResultPayload(String structureId, boolean found, ResourceLocation dimension,
        int x, int y, int z, int distance, int hint) implements CustomPacketPayload {

    public static final int HINT_NONE = 0;
    public static final int HINT_UNKNOWN = 1;
    public static final int HINT_NOT_FOUND = 2;

    public static final CustomPacketPayload.Type<StructureScanResultPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "structure_scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureScanResultPayload> STREAM_CODEC = StreamCodec
            .of(
                    (buffer, payload) -> {
                        buffer.writeUtf(payload.structureId == null ? "" : payload.structureId, 48);
                        buffer.writeBoolean(payload.found);
                        buffer.writeResourceLocation(payload.dimension);
                        buffer.writeInt(payload.x);
                        buffer.writeInt(payload.y);
                        buffer.writeInt(payload.z);
                        buffer.writeVarInt(payload.distance);
                        buffer.writeVarInt(payload.hint);
                    },
                    buffer -> new StructureScanResultPayload(
                            buffer.readUtf(48),
                            buffer.readBoolean(),
                            buffer.readResourceLocation(),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StructureScanResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> StructureScanController.get().onResult(payload));
    }
}
