package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server request for the seed of the dimension the player is in.
 * <p>
 * Only used to derive slime chunks on the client; the request is answered with
 * a {@link WorldSeedPayload}.
 */
@SuppressWarnings("null")
public record SeedRequestPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SeedRequestPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "seed_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SeedRequestPayload> STREAM_CODEC = StreamCodec
            .unit(new SeedRequestPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SeedRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.level() instanceof ServerLevel level) {
                PacketDistributor.sendToPlayer(player,
                        new WorldSeedPayload(level.dimension().location(), level.getSeed()));
            }
        });
    }
}
