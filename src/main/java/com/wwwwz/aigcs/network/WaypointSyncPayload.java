package com.wwwwz.aigcs.network;

import java.util.ArrayList;
import java.util.List;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.core.WaypointData;
import com.wwwwz.aigcs.server.ServerWaypointStore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server batch of waypoint changes.
 * <p>
 * The client uploads slowly in small batches; {@code lastBatch} marks the end
 * of one round, which makes the server broadcast the merged list back to every
 * player exactly once per round.
 */
@SuppressWarnings("null")
public record WaypointSyncPayload(List<WaypointData> upserts, List<String> deletes, boolean lastBatch)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WaypointSyncPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "waypoint_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaypointSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.upserts.size());
                for (WaypointData data : payload.upserts) {
                    WaypointPayloadCodec.write(buffer, data);
                }
                buffer.writeVarInt(payload.deletes.size());
                for (String id : payload.deletes) {
                    buffer.writeUtf(id == null ? "" : id, WaypointPayloadCodec.MAX_ID_LENGTH);
                }
                buffer.writeBoolean(payload.lastBatch);
            },
            buffer -> {
                int upsertCount = Math.min(buffer.readVarInt(),
                        WaypointPayloadCodec.MAX_WAYPOINTS_PER_PAYLOAD);
                List<WaypointData> upserts = new ArrayList<>(upsertCount);
                for (int index = 0; index < upsertCount; index++) {
                    upserts.add(WaypointPayloadCodec.read(buffer));
                }
                int deleteCount = Math.min(buffer.readVarInt(),
                        WaypointPayloadCodec.MAX_WAYPOINTS_PER_PAYLOAD);
                List<String> deletes = new ArrayList<>(deleteCount);
                for (int index = 0; index < deleteCount; index++) {
                    deletes.add(buffer.readUtf(WaypointPayloadCodec.MAX_ID_LENGTH));
                }
                return new WaypointSyncPayload(upserts, deletes, buffer.readBoolean());
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WaypointSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerWaypointStore store = ServerWaypointStore.get();
            if (store != null) {
                store.handle(player, payload.upserts, payload.deletes, payload.lastBatch);
            }
        });
    }
}
