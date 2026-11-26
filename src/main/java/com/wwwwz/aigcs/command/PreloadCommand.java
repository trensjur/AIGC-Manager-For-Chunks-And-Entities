package com.wwwwz.aigcs.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wwwwz.aigcs.core.preload.PreloadPriority;
import com.wwwwz.aigcs.core.preload.PreloadRegion;
import com.wwwwz.aigcs.core.preload.PreloadShape;
import com.wwwwz.aigcs.network.PreloadControlPayload;
import com.wwwwz.aigcs.server.preload.ServerPreloadManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /m4cne preload ...} - full command control of the chunk preload task.
 * <p>
 * The commands edit a persisted {@link ServerPreloadManager.Settings} record
 * (dimension, centre chunk, radius, shape, threads, priority, memory, freeze
 * and
 * import defaults) and then drive the task:
 *
 * <pre>
 * /m4cne preload status
 * /m4cne preload settings
 * /m4cne preload start [here]
 * /m4cne preload pause | resume | cancel
 * /m4cne preload dimension &lt;id&gt;
 * /m4cne preload center &lt;chunkX&gt; &lt;chunkZ&gt; | center here
 * /m4cne preload radius &lt;chunks&gt;
 * /m4cne preload shape &lt;square|circle&gt;
 * /m4cne preload threads &lt;count&gt;
 * /m4cne preload priority &lt;lowest|low|normal|high|highest&gt;
 * /m4cne preload memory &lt;megabytes&gt;
 * /m4cne preload freeze &lt;on|off&gt;
 * /m4cne preload import [off|chunky|m4cne] [budget]
 * /m4cne preload reset
 * </pre>
 *
 * Every command checks the server side preload permission (server config
 * {@code preload.requiredPermissionLevel}, the single player host is always
 * allowed).
 */
@SuppressWarnings("null")
public final class PreloadCommand {
    /** Vanilla world border limit, in chunks. */
    private static final int MAX_CHUNK_COORDINATE = 1_875_000;
    private static final SuggestionProvider<CommandSourceStack> SHAPES = (context, builder) -> SharedSuggestionProvider
            .suggest(new String[] { "square", "circle" }, builder);
    private static final SuggestionProvider<CommandSourceStack> PRIORITIES = (context,
            builder) -> SharedSuggestionProvider
                    .suggest(new String[] { "lowest", "low", "normal", "high", "highest" }, builder);
    private static final SuggestionProvider<CommandSourceStack> DIMENSIONS = (context,
            builder) -> SharedSuggestionProvider
                    .suggest(context.getSource().getServer().levelKeys().stream()
                            .map(key -> key.location().toString()).toList(), builder);
    private static final SimpleCommandExceptionType ERROR_SHAPE = new SimpleCommandExceptionType(
            Component.translatable("command.m4cne.preload.error.shape"));
    private static final SimpleCommandExceptionType ERROR_PRIORITY = new SimpleCommandExceptionType(
            Component.translatable("command.m4cne.preload.error.priority"));

    private PreloadCommand() {
    }

    /** Command tree appended to {@code /m4cne}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("preload")
                .requires(PreloadCommand::allowed)
                .then(Commands.literal("status")
                        .executes(PreloadCommand::status))
                .then(Commands.literal("settings")
                        .executes(PreloadCommand::settings))
                .then(Commands.literal("start")
                        .executes(context -> start(context, false))
                        .then(Commands.literal("here")
                                .executes(context -> start(context, true))))
                .then(Commands.literal("pause")
                        .executes(PreloadCommand::pause))
                .then(Commands.literal("resume")
                        .executes(PreloadCommand::resume))
                .then(Commands.literal("cancel")
                        .executes(PreloadCommand::cancel))
                .then(Commands.literal("dimension")
                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                .suggests(DIMENSIONS)
                                .executes(PreloadCommand::dimension)))
                .then(Commands.literal("center")
                        .then(Commands.literal("here")
                                .executes(PreloadCommand::centerHere))
                        .then(Commands.argument("chunkX",
                                IntegerArgumentType.integer(-MAX_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE))
                                .then(Commands.argument("chunkZ",
                                        IntegerArgumentType.integer(-MAX_CHUNK_COORDINATE,
                                                MAX_CHUNK_COORDINATE))
                                        .executes(PreloadCommand::center))))
                .then(Commands.literal("radius")
                        .then(Commands.argument("chunks",
                                IntegerArgumentType.integer(1, PreloadRegion.MAX_RADIUS))
                                .executes(PreloadCommand::radius)))
                .then(Commands.literal("shape")
                        .then(Commands.argument("shape", StringArgumentType.word())
                                .suggests(SHAPES)
                                .executes(PreloadCommand::shape)))
                .then(Commands.literal("threads")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1024))
                                .executes(PreloadCommand::threads)))
                .then(Commands.literal("priority")
                        .then(Commands.argument("priority", StringArgumentType.word())
                                .suggests(PRIORITIES)
                                .executes(PreloadCommand::priority)))
                .then(Commands.literal("memory")
                        .then(Commands.argument("megabytes", IntegerArgumentType.integer(64, 65536))
                                .executes(PreloadCommand::memory)))
                .then(Commands.literal("freeze")
                        .then(Commands.literal("on")
                                .executes(context -> freeze(context, true)))
                        .then(Commands.literal("off")
                                .executes(context -> freeze(context, false))))
                .then(Commands.literal("import")
                        .executes(PreloadCommand::importCurrent)
                        .then(Commands.literal("off")
                                .executes(context -> importMode(context, PreloadControlPayload.IMPORT_OFF, 0)))
                        .then(Commands.literal("chunky")
                                .executes(context -> importMode(context, PreloadControlPayload.IMPORT_CHUNKY, -1))
                                .then(Commands.argument("budget", IntegerArgumentType
                                        .integer(1, ServerPreloadManager.Settings.MAX_IMPORT_BUDGET))
                                        .executes(context -> importMode(context,
                                                PreloadControlPayload.IMPORT_CHUNKY,
                                                IntegerArgumentType.getInteger(context, "budget")))))
                        .then(Commands.literal("m4cne")
                                .executes(context -> importMode(context, PreloadControlPayload.IMPORT_M4CNE, -1))
                                .then(Commands.argument("budget", IntegerArgumentType
                                        .integer(1, ServerPreloadManager.Settings.MAX_IMPORT_BUDGET))
                                        .executes(context -> importMode(context,
                                                PreloadControlPayload.IMPORT_M4CNE,
                                                IntegerArgumentType.getInteger(context, "budget"))))))
                .then(Commands.literal("reset")
                        .executes(PreloadCommand::reset));
    }

    // --------------------------------------------------------------- plumbing

    private static boolean allowed(CommandSourceStack source) {
        ServerPreloadManager manager = ServerPreloadManager.get();
        // Without a manager the command handlers report the missing store, so
        // the node stays visible instead of silently disappearing.
        return manager == null || manager.permitted(source);
    }

    private static ServerPreloadManager manager(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = ServerPreloadManager.get();
        if (manager == null) {
            context.getSource().sendFailure(Component.translatable("command.m4cne.preload.no_store"));
        }
        return manager;
    }

    private static void success(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendSuccess(() -> Component.translatable(key, args), false);
    }

    private static void failure(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendFailure(Component.translatable(key, args));
    }

    private static Component literal(ResourceLocation dimension) {
        return Component.literal(dimension.toString());
    }

    private static Component state(boolean value) {
        return Component.translatable(value ? "screen.m4cne.state.enabled" : "screen.m4cne.state.disabled");
    }

    private static Component importModeName(int mode) {
        String key = mode == PreloadControlPayload.IMPORT_CHUNKY ? "screen.m4cne.preload.import.chunky"
                : mode == PreloadControlPayload.IMPORT_M4CNE ? "screen.m4cne.preload.import.m4cne"
                        : "screen.m4cne.preload.import.off";
        return Component.translatable(key);
    }

    private static PreloadShape parseShape(String name) {
        for (PreloadShape shape : PreloadShape.values()) {
            if (shape.name().equalsIgnoreCase(name)) {
                return shape;
            }
        }
        return null;
    }

    private static PreloadPriority parsePriority(String name) {
        for (PreloadPriority priority : PreloadPriority.values()) {
            if (priority.name().equalsIgnoreCase(name)) {
                return priority;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- status

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerPreloadManager.Status status = manager.status();
        if (status.hasTask()) {
            success(context, "command.m4cne.preload.status.header",
                    Component.translatable(status.state().translationKey()), literal(status.dimension()));
            success(context, "command.m4cne.preload.status.region", status.centerChunkX(),
                    status.centerChunkZ(), status.radius(),
                    Component.translatable(status.shape().translationKey()), status.total());
            int percent = status.total() > 0
                    ? (int) Math.round(status.completed() * 100.0D / status.total())
                    : 0;
            success(context, "command.m4cne.preload.status.progress", status.completed(), status.total(),
                    percent);
            success(context, "command.m4cne.preload.status.perf", status.threads(),
                    Component.translatable(status.priority().translationKey()), status.memoryMb(),
                    state(status.frozen()));
            if (status.importRunning() || status.imported() > 0 || status.skipped() > 0) {
                success(context, "command.m4cne.preload.status.import", status.imported(),
                        status.importTotal(), status.skipped());
            }
        } else {
            success(context, "command.m4cne.preload.status.idle");
        }
        if (status.c2me()) {
            success(context, "command.m4cne.preload.status.c2me");
        }
        if (status.chunky()) {
            success(context, "command.m4cne.preload.status.chunky");
        }
        return 1;
    }

    private static int settings(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerPreloadManager.Settings settings = manager.settings();
        PreloadRegion region = settings.region();
        success(context, "command.m4cne.preload.settings.header");
        success(context, "command.m4cne.preload.settings.line", literal(settings.dimension()),
                settings.centerChunkX(), settings.centerChunkZ(), settings.radius(),
                Component.translatable(settings.shape().translationKey()), region.total());
        success(context, "command.m4cne.preload.settings.perf", settings.threads(),
                Component.translatable(settings.priority().translationKey()), settings.memoryMb(),
                state(settings.freezeTicks()));
        success(context, "command.m4cne.preload.settings.import", importModeName(settings.importMode()),
                settings.importBudget());
        return 1;
    }

    // ------------------------------------------------------------------- task

    private static int start(CommandContext<CommandSourceStack> context, boolean here) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerPreloadManager.Settings settings = manager.settings();
        if (here) {
            ServerLevel level = context.getSource().getLevel();
            Vec3 position = context.getSource().getPosition();
            settings = settings.withDimension(level.dimension().location())
                    .withCenter(Mth.floor(position.x) >> 4, Mth.floor(position.z) >> 4);
            manager.setSettings(settings);
        }
        ServerPlayer player = context.getSource().getPlayer();
        ServerPreloadManager.StartOutcome outcome = manager.start(player, settings);
        switch (outcome) {
            case CHUNKY_RUNNING -> {
                failure(context, "command.m4cne.preload.chunky");
                return 0;
            }
            case DIMENSION_MISSING -> {
                failure(context, "command.m4cne.preload.dimension_missing", literal(settings.dimension()));
                return 0;
            }
            default -> {
            }
        }
        manager.announce();
        ServerPreloadManager.Status status = manager.status();
        if (outcome == ServerPreloadManager.StartOutcome.RESUMED) {
            success(context, "command.m4cne.preload.resumed", literal(settings.dimension()),
                    status.completed(), status.total());
        } else {
            success(context, "command.m4cne.preload.started", literal(settings.dimension()),
                    settings.centerChunkX(), settings.centerChunkZ(), settings.radius(),
                    Component.translatable(settings.shape().translationKey()), status.total());
        }
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        if (!manager.pause()) {
            failure(context, "command.m4cne.preload.not_running");
            return 0;
        }
        manager.announce();
        ServerPreloadManager.Status status = manager.status();
        success(context, "command.m4cne.preload.paused", status.completed(), status.total());
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerPreloadManager.ResumeOutcome outcome = manager.resume();
        switch (outcome) {
            case RESUMED -> {
                manager.announce();
                ServerPreloadManager.Status status = manager.status();
                success(context, "command.m4cne.preload.resumed", literal(status.dimension()),
                        status.completed(), status.total());
                return 1;
            }
            case NO_TASK -> failure(context, "command.m4cne.preload.no_task");
            case NOT_PAUSED -> failure(context, "command.m4cne.preload.not_paused");
            case CHUNKY_RUNNING -> failure(context, "command.m4cne.preload.chunky");
            case DIMENSION_MISSING -> failure(context, "command.m4cne.preload.dimension_missing",
                    literal(manager.status().dimension()));
        }
        return 0;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        if (!manager.cancel()) {
            failure(context, "command.m4cne.preload.nothing_to_cancel");
            return 0;
        }
        manager.announce();
        success(context, "command.m4cne.preload.cancelled");
        return 1;
    }

    // ------------------------------------------------------------- settings

    private static int dimension(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ResourceLocation dimension = ResourceLocationArgument.getId(context, "dimension");
        if (!manager.hasLevel(dimension)) {
            failure(context, "command.m4cne.preload.dimension_missing", literal(dimension));
            return 0;
        }
        manager.setSettings(manager.settings().withDimension(dimension));
        success(context, "command.m4cne.preload.dimension_set", literal(dimension));
        return 1;
    }

    private static int center(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        int chunkX = IntegerArgumentType.getInteger(context, "chunkX");
        int chunkZ = IntegerArgumentType.getInteger(context, "chunkZ");
        manager.setSettings(manager.settings().withCenter(chunkX, chunkZ));
        success(context, "command.m4cne.preload.center_set", chunkX, chunkZ);
        return 1;
    }

    private static int centerHere(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerLevel level = context.getSource().getLevel();
        Vec3 position = context.getSource().getPosition();
        int chunkX = Mth.floor(position.x) >> 4;
        int chunkZ = Mth.floor(position.z) >> 4;
        manager.setSettings(manager.settings()
                .withDimension(level.dimension().location())
                .withCenter(chunkX, chunkZ));
        success(context, "command.m4cne.preload.center_here", literal(level.dimension().location()),
                chunkX, chunkZ);
        return 1;
    }

    private static int radius(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        int chunks = IntegerArgumentType.getInteger(context, "chunks");
        ServerPreloadManager.Settings next = manager.settings().withRadius(chunks);
        manager.setSettings(next);
        success(context, "command.m4cne.preload.radius_set", next.radius(), next.region().total());
        return 1;
    }

    private static int shape(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        PreloadShape shape = parseShape(StringArgumentType.getString(context, "shape"));
        if (shape == null) {
            throw ERROR_SHAPE.create();
        }
        ServerPreloadManager.Settings next = manager.settings().withShape(shape);
        manager.setSettings(next);
        success(context, "command.m4cne.preload.shape_set",
                Component.translatable(shape.translationKey()), next.region().total());
        return 1;
    }

    private static int threads(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        int count = IntegerArgumentType.getInteger(context, "count");
        ServerPreloadManager.Settings next = manager.settings().withThreads(count);
        manager.setSettings(next);
        success(context, "command.m4cne.preload.threads_set", next.threads(),
                Runtime.getRuntime().availableProcessors());
        return 1;
    }

    private static int priority(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        PreloadPriority priority = parsePriority(StringArgumentType.getString(context, "priority"));
        if (priority == null) {
            throw ERROR_PRIORITY.create();
        }
        ServerPreloadManager.Settings next = manager.settings().withPriority(priority);
        manager.setSettings(next);
        success(context, "command.m4cne.preload.priority_set",
                Component.translatable(priority.translationKey()));
        return 1;
    }

    private static int memory(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        int megabytes = IntegerArgumentType.getInteger(context, "megabytes");
        ServerPreloadManager.Settings next = manager.settings().withMemory(megabytes);
        manager.setSettings(next);
        success(context, "command.m4cne.preload.memory_set", next.memoryMb(), manager.heapLimitMb());
        return 1;
    }

    private static int freeze(CommandContext<CommandSourceStack> context, boolean value) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        manager.setSettings(manager.settings().withFreeze(value));
        success(context, "command.m4cne.preload.freeze_set", state(value));
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerLevel level = context.getSource().getLevel();
        Vec3 position = context.getSource().getPosition();
        ServerPreloadManager.Settings next = manager.resetSettings(level.dimension().location(),
                Mth.floor(position.x) >> 4, Mth.floor(position.z) >> 4);
        success(context, "command.m4cne.preload.reset", literal(next.dimension()), next.centerChunkX(),
                next.centerChunkZ(), next.radius());
        return 1;
    }

    // ----------------------------------------------------------------- import

    private static int importCurrent(CommandContext<CommandSourceStack> context) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerPreloadManager.Settings settings = manager.settings();
        return startImport(context, manager, settings.importMode(), settings.importBudget());
    }

    private static int importMode(CommandContext<CommandSourceStack> context, int mode, int budget) {
        ServerPreloadManager manager = manager(context);
        if (manager == null) {
            return 0;
        }
        ServerPreloadManager.Settings settings = manager.settings();
        if (mode == PreloadControlPayload.IMPORT_OFF) {
            manager.stopImport();
            manager.setSettings(settings.withImportMode(PreloadControlPayload.IMPORT_OFF));
            success(context, "command.m4cne.preload.import_off_set");
            return 1;
        }
        int effectiveBudget = budget > 0 ? budget : settings.importBudget();
        manager.setSettings(settings.withImportMode(mode).withImportBudget(effectiveBudget));
        return startImport(context, manager, mode, effectiveBudget);
    }

    private static int startImport(CommandContext<CommandSourceStack> context, ServerPreloadManager manager,
            int mode, int budget) {
        if (mode == PreloadControlPayload.IMPORT_OFF) {
            failure(context, "command.m4cne.preload.import_off");
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        ServerPreloadManager.Status status = manager.status();
        ResourceLocation dimension = status.hasTask() ? status.dimension() : manager.settings().dimension();
        ServerPreloadManager.ImportOutcome outcome = manager.importTask(player, dimension, mode, budget);
        switch (outcome) {
            case STARTED -> {
                manager.announce();
                success(context, "command.m4cne.preload.import_started", importModeName(mode), budget);
                return 1;
            }
            case NO_PLAYER -> failure(context, "command.m4cne.preload.no_player");
            case NO_TASK -> failure(context, "command.m4cne.preload.import_no_task");
            case NOT_READY -> failure(context, "command.m4cne.preload.import_not_ready");
            case DISABLED -> failure(context, "command.m4cne.preload.import_off");
        }
        return 0;
    }
}
