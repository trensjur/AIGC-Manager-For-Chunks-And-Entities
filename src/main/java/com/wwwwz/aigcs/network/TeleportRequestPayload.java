package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.server.SafePositions;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server teleport request.
 * <p>
 * The server resolves a safe feet position (free feet and head, solid or fluid
 * floor) before teleporting, so map teleports can never place the player inside
 * a block. The permission matches the vanilla {@code /tp} requirement.
 */
@SuppressWarnings("null")
public record TeleportRequestPayload(ResourceLocation dimension, double x, double y, double z,
        boolean surface) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TeleportRequestPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "teleport_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TeleportRequestPayload> STREAM_CODEC = StreamCodec
            .of(
                    (buffer, payload) -> {
                        buffer.writeResourceLocation(payload.dimension);
                        buffer.writeDouble(payload.x);
                        buffer.writeDouble(payload.y);
                        buffer.writeDouble(payload.z);
                        buffer.writeBoolean(payload.surface);
                    },
                    buffer -> new TeleportRequestPayload(
                            buffer.readResourceLocation(),
                            buffer.readDouble(),
                            buffer.readDouble(),
                            buffer.readDouble(),
                            buffer.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TeleportRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            if (!level.dimension().location().equals(payload.dimension)) {
                player.displayClientMessage(
                        Component.translatable("screen.m4cne.status.teleport_wrong_dimension"), true);
                return;
            }
            if (!player.hasPermissions(2)) {
                player.displayClientMessage(
                        Component.translatable("screen.m4cne.status.teleport_no_permission"), true);
                return;
            }
            int blockX = Mth.floor(payload.x);
            int blockZ = Mth.floor(payload.z);
            int safeY;
            if (payload.surface && level.dimension().equals(Level.NETHER)) {
                // The Nether heightmaps point at the bedrock roof, so start at
                // the player's own level and search the whole nether height.
                safeY = SafePositions.resolve(level, blockX, blockZ, Mth.floor(player.getY()), false,
                        64);
            } else {
                int preferredY = payload.surface
                        ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ)
                        : Mth.floor(payload.y);
                safeY = SafePositions.resolve(level, blockX, blockZ, preferredY, payload.surface);
            }
            if (safeY == Integer.MIN_VALUE) {
                player.displayClientMessage(
                        Component.translatable("screen.m4cne.status.teleport_no_safe_spot"), true);
                return;
            }
            player.teleportTo(level, payload.x, safeY, payload.z, player.getYRot(), player.getXRot());
        });
    }
}
