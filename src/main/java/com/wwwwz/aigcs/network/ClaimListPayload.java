package com.wwwwz.aigcs.network;

import java.util.List;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.client.claim.ClaimSyncManager;
import com.wwwwz.aigcs.core.claim.ClaimOwner;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -&gt; client authoritative list of every claim on the server.
 * <p>
 * The payload also tells the client what the server allows: whether the player
 * may manage the claims of other players, the configured scan interval and the
 * claim / force load limits.
 */
@SuppressWarnings("null")
public record ClaimListPayload(List<ClaimOwner> owners, boolean admin, int scanIntervalSeconds,
        int maxClaims, int maxForced, boolean entityControl) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClaimListPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "claim_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimListPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                ClaimPayloadCodec.writeOwners(buffer, payload.owners);
                buffer.writeBoolean(payload.admin);
                buffer.writeVarInt(Math.max(0, payload.scanIntervalSeconds));
                buffer.writeVarInt(Math.max(0, payload.maxClaims));
                buffer.writeVarInt(Math.max(0, payload.maxForced));
                buffer.writeBoolean(payload.entityControl);
            },
            buffer -> new ClaimListPayload(ClaimPayloadCodec.readOwners(buffer), buffer.readBoolean(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClaimListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClaimStore.get().applyServerList(payload.owners);
            ClaimSyncManager.get().onListReceived(payload.admin, payload.scanIntervalSeconds,
                    payload.maxClaims, payload.maxForced, payload.entityControl);
        });
    }
}
