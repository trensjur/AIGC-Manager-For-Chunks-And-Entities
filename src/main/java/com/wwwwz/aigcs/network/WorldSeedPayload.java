package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.map.SlimeChunkCache;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client answer carrying the seed of one dimension. */
@SuppressWarnings("null")
public record WorldSeedPayload(ResourceLocation dimension, long seed) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WorldSeedPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "world_seed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldSeedPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeLong(payload.seed);
            },
            buffer -> new WorldSeedPayload(buffer.readResourceLocation(), buffer.readLong()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WorldSeedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SlimeChunkCache.get().setSeed(payload.dimension, payload.seed));
    }
}
