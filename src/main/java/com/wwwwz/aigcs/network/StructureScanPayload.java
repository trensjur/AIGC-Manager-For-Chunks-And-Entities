package com.wwwwz.aigcs.network;

import com.mojang.datafixers.util.Pair;
import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.server.SafePositions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server request to locate the nearest structure of one catalogue
 * entry. The server uses the world seed based structure placement search of the
 * chunk generator, so the coordinates match the real generation.
 */
@SuppressWarnings("null")
public record StructureScanPayload(String structureId, String tag) implements CustomPacketPayload {
    /** Chunks around the player that are searched, matching vanilla locate. */
    private static final int SEARCH_RADIUS_CHUNKS = 100;

    public static final CustomPacketPayload.Type<StructureScanPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AIGCsM4CnE.MODID, "structure_scan"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureScanPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.structureId == null ? "" : payload.structureId, 48);
                buffer.writeUtf(payload.tag == null ? "" : payload.tag, 64);
            },
            buffer -> new StructureScanPayload(buffer.readUtf(48), buffer.readUtf(64)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StructureScanPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            HolderSet<Structure> targets = resolveTargets(level, payload);
            int hint = 0;
            Pair<BlockPos, Holder<Structure>> found = null;
            if (targets == null) {
                hint = StructureScanResultPayload.HINT_UNKNOWN;
            } else {
                try {
                    found = level.getChunkSource().getGenerator().findNearestMapStructure(
                            level, targets, player.blockPosition(), SEARCH_RADIUS_CHUNKS, false);
                } catch (RuntimeException exception) {
                    AIGCsM4CnE.LOGGER.warn("Structure scan for {} failed", payload.structureId, exception);
                    hint = StructureScanResultPayload.HINT_UNKNOWN;
                }
            }
            if (found == null && hint == 0) {
                hint = StructureScanResultPayload.HINT_NOT_FOUND;
            }
            BlockPos pos = found != null ? found.getFirst() : player.blockPosition();
            // Underground structures are lifted to the terrain surface. In the
            // Nether the heightmaps point at the bedrock roof, so the structure
            // Y itself is used and a standable spot near it is searched.
            int y = pos.getY();
            if (found != null) {
                if (level.dimension().equals(Level.NETHER)) {
                    int safe = SafePositions.resolve(level, pos.getX(), pos.getZ(), pos.getY(), false, 48);
                    y = safe != Integer.MIN_VALUE ? safe : pos.getY();
                } else {
                    y = SafePositions.surfaceY(level, pos.getX(), pos.getZ());
                }
            }
            int distance = found != null
                    ? (int) Math.round(Math.sqrt(player.blockPosition().distSqr(pos)))
                    : 0;
            PacketDistributor.sendToPlayer(player, new StructureScanResultPayload(
                    payload.structureId, found != null, level.dimension().location(),
                    pos.getX(), y, pos.getZ(), distance, hint));
        });
    }

    private static HolderSet<Structure> resolveTargets(ServerLevel level, StructureScanPayload payload) {
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            String tag = payload.tag;
            if (tag != null && !tag.isBlank()) {
                ResourceLocation tagId = ResourceLocation.tryParse(tag);
                return tagId != null ? registry.getOrCreateTag(TagKey.create(Registries.STRUCTURE, tagId))
                        : null;
            }
            ResourceLocation id = ResourceLocation.tryParse(
                    payload.structureId == null ? "" : payload.structureId);
            if (id == null) {
                return null;
            }
            Holder<Structure> holder = registry
                    .getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElse(null);
            return holder != null ? HolderSet.direct(holder) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
