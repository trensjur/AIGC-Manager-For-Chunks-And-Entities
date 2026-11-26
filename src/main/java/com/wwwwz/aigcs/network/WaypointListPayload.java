package com.wwwwz.aigcs.network;

import java.util.ArrayList;
import java.util.List;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.ClientWorldKey;
import com.wwwwz.aigcs.client.map.WaypointStore;
import com.wwwwz.aigcs.client.map.WaypointSyncManager;
import com.wwwwz.aigcs.core.WaypointData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client authoritative waypoint list. */
@SuppressWarnings("null")
public record WaypointListPayload(List<WaypointData> waypoints) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WaypointListPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "waypoint_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaypointListPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.waypoints.size());
                for (WaypointData data : payload.waypoints) {
                    WaypointPayloadCodec.write(buffer, data);
                }
            },
            buffer -> {
                int count = Math.min(buffer.readVarInt(), WaypointPayloadCodec.MAX_WAYPOINTS_PER_LIST);
                List<WaypointData> waypoints = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    waypoints.add(WaypointPayloadCodec.read(buffer));
                }
                return new WaypointListPayload(waypoints);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WaypointListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            WaypointStore store = WaypointStore.get();
            store.ensureWorld(ClientWorldKey.current());
            store.applyServerList(payload.waypoints);
            WaypointSyncManager.get().onListReceived();
        });
    }
}
