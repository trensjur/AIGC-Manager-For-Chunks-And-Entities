package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.map.WaypointSyncManager;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client signal to run an immediate waypoint synchronisation.
 * <p>
 * Only the server can trigger it (via the operator command), which matches the
 * requirement that a forced synchronisation is a server side action.
 */
@SuppressWarnings("null")
public record WaypointForceSyncPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WaypointForceSyncPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "waypoint_force_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaypointForceSyncPayload> STREAM_CODEC = StreamCodec
            .unit(new WaypointForceSyncPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WaypointForceSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> WaypointSyncManager.get().requestForceSync());
    }
}
