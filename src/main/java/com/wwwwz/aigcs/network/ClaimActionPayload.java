package com.wwwwz.aigcs.network;

import java.util.List;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.core.claim.ClaimChunk;
import com.wwwwz.aigcs.server.ServerClaimManager;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -&gt; server claim management action.
 * <p>
 * Ordinary players may only change their own claims (delete, force load,
 * boundary, colour). Renaming the scan interval or deleting the claims of
 * another player requires the configured administrator permission; the server
 * re-checks every action, so a modified client cannot bypass it.
 */
@SuppressWarnings("null")
public record ClaimActionPayload(Action action, String ownerId, boolean value, int number, String text,
        List<ClaimChunk> chunks) implements CustomPacketPayload {

    public enum Action {
        /** Toggle the boundary outline of one owner. */
        SET_BOUNDARY,
        /** Change the claim colour of one owner. */
        SET_COLOR,
        /** Remove claims of one owner. */
        DELETE_CLAIMS,
        /** Clear the force load flag of some claims of one owner. */
        CLEAR_FORCE,
        /** Ask every client to upload its claims again (administrators only). */
        REQUEST_SCAN,
        /** Change the periodic scan interval (administrators only). */
        SET_INTERVAL,
        /** Rename some chunks of one owner; {@code text} is the new id. */
        RENAME_CLAIMS,
        /** Toggle the 3D boundary wall of some chunks of one owner. */
        SET_CHUNK_BOUNDARY,
        /** Toggle the "entered the chunk" hint of some chunks of one owner. */
        SET_CHUNK_MESSAGE,
        /** Merge every listed chunk into one id (the smallest id wins). */
        MERGE_CLAIMS;

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : null;
        }
    }

    public static final CustomPacketPayload.Type<ClaimActionPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "claim_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimActionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeByte(payload.action.ordinal());
                buffer.writeUtf(payload.ownerId == null ? "" : payload.ownerId,
                        ClaimPayloadCodec.MAX_ID_LENGTH);
                buffer.writeBoolean(payload.value);
                buffer.writeVarInt(Math.max(0, payload.number));
                buffer.writeUtf(payload.text == null ? "" : payload.text, ClaimChunk.MAX_ID_LENGTH);
                ClaimPayloadCodec.writeChunks(buffer, payload.chunks);
            },
            buffer -> {
                Action action = Action.byId(buffer.readUnsignedByte());
                if (action == null) {
                    throw new DecoderException("Unknown chunk claim action");
                }
                String ownerId = buffer.readUtf(ClaimPayloadCodec.MAX_ID_LENGTH);
                boolean value = buffer.readBoolean();
                int number = buffer.readVarInt();
                String text = buffer.readUtf(ClaimChunk.MAX_ID_LENGTH);
                List<ClaimChunk> chunks = ClaimPayloadCodec.readChunks(buffer);
                return new ClaimActionPayload(action, ownerId, value, number, text, chunks);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClaimActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerClaimManager manager = ServerClaimManager.get();
            if (manager != null) {
                manager.handleAction(player, payload);
            }
        });
    }
}
