package com.wwwwz.aigcs.network;

import java.util.List;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.core.claim.ClaimChunk;
import com.wwwwz.aigcs.server.ServerClaimManager;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -&gt; server upload of the own chunk claims.
 * <p>
 * Every round is a complete snapshot: the client sends its own claims in
 * several small batches and the server replaces the stored set of that player
 * when the last batch arrived. That keeps the server free of per chunk
 * bookkeeping and heals a client that lost its local state.
 */
@SuppressWarnings("null")
public record ClaimSyncPayload(int roundId, List<ClaimChunk> chunks, boolean lastBatch)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClaimSyncPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "claim_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(Math.max(0, payload.roundId));
                ClaimPayloadCodec.writeChunks(buffer, payload.chunks);
                buffer.writeBoolean(payload.lastBatch);
            },
            buffer -> new ClaimSyncPayload(buffer.readVarInt(),
                    ClaimPayloadCodec.readChunks(buffer), buffer.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClaimSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerClaimManager manager = ServerClaimManager.get();
            if (manager != null) {
                manager.handleSync(player, payload.roundId, payload.chunks, payload.lastBatch);
            }
        });
    }
}
