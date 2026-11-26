package com.wwwwz.aigcs.command;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.core.claim.ClaimOwner;
import com.wwwwz.aigcs.server.DimensionStats;
import com.wwwwz.aigcs.server.ServerChunkStore;
import com.wwwwz.aigcs.server.ServerClaimManager;
import com.wwwwz.aigcs.server.ServerWaypointStore;
import com.wwwwz.aigcs.server.StoredChunk;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Small management/diagnostics commands for the chunk repository.
 * <p>
 * {@code /m4cne here} works for every player and shows the record of the chunk
 * the player stands in. The aggregate statistics and the manual save are
 * operator only.
 */
@SuppressWarnings("null")
public final class M4CnECommand {
        private M4CnECommand() {
        }

        public static void register(RegisterCommandsEvent event) {
                CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
                dispatcher.register(Commands.literal("m4cne")
                                .then(Commands.literal("here")
                                                .executes(M4CnECommand::here))
                                .then(Commands.literal("stats")
                                                .requires(source -> source.hasPermission(2))
                                                .executes(M4CnECommand::stats))
                                .then(Commands.literal("save")
                                                .requires(source -> source.hasPermission(2))
                                                .executes(M4CnECommand::save))
                                .then(Commands.literal("waypoint")
                                                .then(Commands.literal("forceSync")
                                                                .requires(source -> source.hasPermission(2))
                                                                .executes(M4CnECommand::forceWaypointSync)))
                                .then(Commands.literal("claim")
                                                .then(Commands.literal("scan")
                                                                .requires(source -> source.hasPermission(
                                                                                Math.max(0, Config.CLAIM_ADMIN_PERMISSION_LEVEL
                                                                                                .getAsInt())))
                                                                .executes(M4CnECommand::claimScan))
                                                .then(Commands.literal("list")
                                                                .requires(source -> source.hasPermission(2))
                                                                .executes(M4CnECommand::claimList))
                                                .then(Commands.literal("interval")
                                                                .requires(source -> source.hasPermission(
                                                                                Math.max(0, Config.CLAIM_ADMIN_PERMISSION_LEVEL
                                                                                                .getAsInt())))
                                                                .then(Commands.argument("seconds",
                                                                                IntegerArgumentType.integer(30, 86400))
                                                                                .executes(M4CnECommand::claimInterval)))
                                                .then(Commands.literal("clear")
                                                                .requires(source -> source.hasPermission(2))
                                                                .then(Commands.argument("player",
                                                                                StringArgumentType.word())
                                                                                .executes(M4CnECommand::claimClear))))
                                .then(PreloadCommand.node()));
        }

        private static int claimScan(CommandContext<CommandSourceStack> context) {
                ServerClaimManager manager = ServerClaimManager.get();
                if (manager == null) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.noStore"));
                        return 0;
                }
                int sent = manager.requestScan();
                if (sent <= 0) {
                        context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.claim.scan.none"),
                                        false);
                        return 0;
                }
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.claim.scan.sent", sent),
                                true);
                return sent;
        }

        private static int claimList(CommandContext<CommandSourceStack> context) {
                ServerClaimManager manager = ServerClaimManager.get();
                if (manager == null) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.noStore"));
                        return 0;
                }
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.claim.list.header",
                                manager.claimCount(), manager.forcedCount()), false);
                for (ClaimOwner owner : manager.snapshot()) {
                        context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.claim.list.line",
                                        owner.name(), owner.chunks().size(), owner.forceLoadCount()), false);
                }
                return 1;
        }

        private static int claimInterval(CommandContext<CommandSourceStack> context) {
                int seconds = IntegerArgumentType.getInteger(context, "seconds");
                Config.CLAIM_SCAN_INTERVAL_SECONDS.set(seconds);
                Config.SPEC.save();
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.claim.interval", seconds),
                                true);
                return seconds;
        }

        private static int claimClear(CommandContext<CommandSourceStack> context) {
                ServerClaimManager manager = ServerClaimManager.get();
                if (manager == null) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.noStore"));
                        return 0;
                }
                String name = StringArgumentType.getString(context, "player");
                int removed = manager.clearOwner(name);
                if (removed <= 0) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.claim.clear.none", name));
                        return 0;
                }
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.claim.clear.done",
                                removed, name), true);
                return removed;
        }

        private static int forceWaypointSync(CommandContext<CommandSourceStack> context) {
                ServerWaypointStore store = ServerWaypointStore.get();
                if (store == null) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.noStore"));
                        return 0;
                }
                int sent = store.requestForceSync();
                if (sent <= 0) {
                        context.getSource().sendSuccess(
                                        () -> Component.translatable("command.m4cne.waypoint.forceSync.none"), false);
                        return 0;
                }
                context.getSource().sendSuccess(
                                () -> Component.translatable("command.m4cne.waypoint.forceSync.sent", sent), false);
                return sent;
        }

        private static int stats(CommandContext<CommandSourceStack> context) {
                ServerChunkStore store = ServerChunkStore.get();
                if (store == null) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.noStore"));
                        return 0;
                }

                List<DimensionStats> dimensions = store.stats();
                if (dimensions.isEmpty()) {
                        context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.stats.empty"),
                                        false);
                        return 0;
                }

                for (DimensionStats dimension : dimensions) {
                        context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.stats.line",
                                        dimension.dimension().toString(), dimension.chunks(), dimension.entityTotal()),
                                        false);
                        for (Map.Entry<ResourceLocation, Integer> entity : dimension.topEntities(8)) {
                                context.getSource()
                                                .sendSuccess(() -> Component.translatable("command.m4cne.stats.entity",
                                                                entity.getKey().toString(), entity.getValue()), false);
                        }
                }
                return dimensions.size();
        }

        private static int here(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
                ServerChunkStore store = ServerChunkStore.get();
                if (store == null) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.noStore"));
                        return 0;
                }

                ServerPlayer player = context.getSource().getPlayerOrException();
                ResourceLocation dimension = player.level().dimension().location();
                ChunkPos chunkPos = player.chunkPosition();
                StoredChunk chunk = store.getChunk(dimension, chunkPos.x, chunkPos.z);
                if (chunk == null) {
                        context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.here.none",
                                        chunkPos.x, chunkPos.z), false);
                        return 0;
                }

                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.here.header",
                                dimension.toString(), chunkPos.x, chunkPos.z), false);
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.here.updated",
                                Instant.ofEpochMilli(chunk.updatedAt).toString(), chunk.revision), false);
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.here.tile",
                                chunk.tile != null ? "yes" : "no"), false);
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.here.entities",
                                chunk.entities.total()), false);

                List<Map.Entry<ResourceLocation, Integer>> entities = chunk.entities.counts().entrySet().stream()
                                .sorted(Map.Entry.<ResourceLocation, Integer>comparingByValue().reversed())
                                .limit(12)
                                .toList();
                for (Map.Entry<ResourceLocation, Integer> entity : entities) {
                        context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.here.entityLine",
                                        entity.getKey().toString(), entity.getValue()), false);
                }
                return 1;
        }

        private static int save(CommandContext<CommandSourceStack> context) {
                ServerChunkStore store = ServerChunkStore.get();
                if (store == null) {
                        context.getSource().sendFailure(Component.translatable("command.m4cne.noStore"));
                        return 0;
                }
                int regions = store.saveNow();
                context.getSource().sendSuccess(() -> Component.translatable("command.m4cne.save.done", regions), true);
                return regions;
        }
}
