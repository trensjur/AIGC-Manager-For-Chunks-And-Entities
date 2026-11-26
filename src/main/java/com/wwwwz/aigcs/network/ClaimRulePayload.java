package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;
import com.wwwwz.aigcs.server.ServerClaimManager;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -&gt; server update of the entity control rules of one claim owner.
 * <p>
 * The payload always contains the complete rule set; the server replaces the
 * stored set after validating the permissions and the entity types. Ordinary
 * players may only change their own rules, administrators may edit every owner.
 */
@SuppressWarnings("null")
public record ClaimRulePayload(String ownerId, EntityRuleSet rules) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClaimRulePayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "claim_rules"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimRulePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.ownerId == null ? "" : payload.ownerId,
                        ClaimPayloadCodec.MAX_ID_LENGTH);
                ClaimPayloadCodec.writeRuleSet(buffer, payload.rules);
            },
            buffer -> new ClaimRulePayload(buffer.readUtf(ClaimPayloadCodec.MAX_ID_LENGTH),
                    ClaimPayloadCodec.readRuleSet(buffer)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClaimRulePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerClaimManager manager = ServerClaimManager.get();
            if (manager != null) {
                manager.handleEntityRules(player, payload.ownerId(), payload.rules());
            }
        });
    }
}
