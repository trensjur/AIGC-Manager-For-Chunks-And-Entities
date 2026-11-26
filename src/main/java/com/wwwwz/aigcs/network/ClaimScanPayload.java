package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.claim.ClaimSyncManager;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -&gt; client request to upload the own claims again.
 * <p>
 * The server sends it periodically (default every ten minutes) and whenever an
 * administrator triggers a manual scan, which makes the claims survive even if
 * a
 * client missed an upload.
 */
@SuppressWarnings("null")
public record ClaimScanPayload(boolean manual) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClaimScanPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "claim_scan"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimScanPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBoolean(payload.manual),
            buffer -> new ClaimScanPayload(buffer.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClaimScanPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClaimSyncManager.get().requestScanRound(payload.manual));
    }
}
