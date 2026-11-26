package com.wwwwz.aigcs.server.preload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.compat.ModCompat;
import com.wwwwz.aigcs.core.ChunkBitset;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;
import com.wwwwz.aigcs.core.EntityCount;
import com.wwwwz.aigcs.core.preload.PreloadPriority;
import com.wwwwz.aigcs.core.preload.PreloadRegion;
import com.wwwwz.aigcs.core.preload.PreloadShape;
import com.wwwwz.aigcs.core.preload.PreloadState;
import com.wwwwz.aigcs.network.PreloadChunkPayload;
import com.wwwwz.aigcs.network.PreloadControlPayload;
import com.wwwwz.aigcs.network.PreloadStatusPayload;
import com.wwwwz.aigcs.server.ServerChunkStore;
import com.wwwwz.aigcs.server.StoredChunk;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side execution of the chunk preload feature.
 * <p>
 * The task is driven by the vanilla chunk system: every chunk of the region
 * gets
 * a short lived region ticket, which makes the chunk engine generate it to
 * {@code FULL}. Chunks are polled with the non blocking
 * {@link net.minecraft.server.level.ServerChunkCache#getChunkNow(int, int)}, so
 * the server thread never waits for generation - that is what makes the feature
 * work with c2me, whose parallel engine simply receives more requests at once.
 * <p>
 * Sampled tiles are stored in the normal per save chunk repository, so a
 * finished preload is available to the map import without keeping the chunks
 * loaded. Progress is persisted next to the repository and survives restarts.
 * <p>
 * The task can be started either by the world map UI (client packets) or by the
 * {@code /m4cne preload ...} commands. Commands edit a persisted
 * {@link Settings} record (dimension, centre, radius, shape, threads, priority,
 * memory, freeze and import defaults) and then start / pause / resume / cancel
 * the task, so a server can preload a whole region without a client.
 */
@SuppressWarnings("null")
public final class ServerPreloadManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Tickets expire on their own, so a crashed task can never leak chunks. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create("m4cne_preload",
            Comparator.comparingLong(ChunkPos::toLong), 1200);
    private static final TicketType<ChunkPos> IMPORT_TICKET = TicketType.create("m4cne_preload_import",
            Comparator.comparingLong(ChunkPos::toLong), 400);
    private static final int STATUS_INTERVAL_TICKS = 10;
    private static final int TICKET_TIMEOUT_TICKS = 1100;
    /** Rough memory footprint of one chunk that is being generated. */
    private static final long CHUNK_MEMORY_ESTIMATE = 96_000L;
    private static final int MAX_IMPORT_BATCH = 8;
    /** Tries per chunk before the chunky import gives up on it. */
    private static final int MAX_IMPORT_ATTEMPTS = 4;
    /** Memory bound of the per player "client already has this chunk" set. */
    private static final int MAX_TRACKED_CLIENT_CHUNKS = 262_144;
    /** Fallback when the server config is not available (yet). */
    private static final int DEFAULT_PERMISSION_LEVEL = 2;

    private static volatile ServerPreloadManager active;

    private final MinecraftServer server;
    private final Path directory;
    private final ServerChunkSampler sampler = new ServerChunkSampler();
    /**
     * Chunks every player reported through the normal chunk sync, per dimension.
     * The preload import skips those chunks: the client already has them, so
     * sending them again would only waste bandwidth.
     */
    private final Map<UUID, Map<ResourceLocation, LongOpenHashSet>> clientChunks = new HashMap<>();

    private Task task;
    private ImportJob importJob;
    private UUID owner;
    private int statusCounter;
    private int hint = PreloadStatusPayload.HINT_NONE;
    /** Geometry / performance defaults used by the preload commands. */
    private Settings settings;
    /** True while the server tick loop is frozen because of a preload task. */
    private boolean frozenByTask;
    /** Whether the tick loop was already frozen before the task froze it. */
    private boolean freezeWasActive;

    private ServerPreloadManager(MinecraftServer server) {
        this.server = server;
        this.directory = server.getWorldPath(LevelResource.ROOT).resolve("m4cne").resolve("preload");
        this.settings = this.readSettings();
    }

    public static ServerPreloadManager get() {
        return active;
    }

    // -------------------------------------------------------------- lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        active = new ServerPreloadManager(event.getServer());
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        ServerPreloadManager manager = active;
        if (manager != null && manager.server == event.getServer()) {
            manager.shutdown();
            active = null;
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerPreloadManager manager = active;
        if (manager != null) {
            manager.tick();
        }
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerPreloadManager manager = active;
        if (manager != null && event.getEntity() instanceof ServerPlayer player
                && player.getUUID().equals(manager.owner)) {
            // The task keeps running; the next query re-attaches the UI.
            manager.markHint(PreloadStatusPayload.HINT_NO_TASK);
        }
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.clientChunks.remove(player.getUUID());
        }
    }

    /**
     * Pushes the current status (including the permission flag) right after a join.
     */
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPreloadManager manager = active;
        if (manager != null && event.getEntity() instanceof ServerPlayer player
                && manager.server == player.server) {
            manager.sendStatus(player, manager.task);
        }
    }

    /**
     * Remembers a chunk a client reported through the normal chunk sync. The
     * preload import will not send this chunk back to that client.
     */
    public static void onClientChunkSync(ServerPlayer player, ResourceLocation dimension, int chunkX,
            int chunkZ) {
        ServerPreloadManager manager = active;
        if (manager != null && manager.server == player.server) {
            manager.rememberClientChunk(player.getUUID(), dimension, chunkX, chunkZ);
        }
    }

    public static void handleControl(ServerPlayer player, PreloadControlPayload payload) {
        ServerPreloadManager manager = active;
        if (manager != null && manager.server == player.server) {
            manager.handle(player, payload);
        }
    }

    private void shutdown() {
        if (this.task != null) {
            this.releaseAllTickets(this.task);
            this.save(this.task);
            this.task = null;
        }
        this.cancelImport();
        this.applyFreeze(false);
    }

    // ------------------------------------------------------- command interface

    /** Geometry, performance and import defaults used by the preload commands. */
    public record Settings(ResourceLocation dimension, int centerChunkX, int centerChunkZ, int radius,
            PreloadShape shape, int threads, PreloadPriority priority, int memoryMb, boolean freezeTicks,
            int importMode, int importBudget) {
        /** Default radius of a command driven task, in chunks. */
        public static final int DEFAULT_RADIUS = 128;
        /** Default import budget of a command driven task, chunks per tick. */
        public static final int DEFAULT_IMPORT_BUDGET = 8;
        /** Upper bound of the import budget, matches {@code PreloadControlPayload}. */
        public static final int MAX_IMPORT_BUDGET = 64;

        public Settings {
            dimension = dimension == null ? Level.OVERWORLD.location() : dimension;
            radius = Math.max(1, Math.min(PreloadRegion.MAX_RADIUS, radius));
            shape = shape == null ? PreloadShape.SQUARE : shape;
            threads = clampThreads(threads);
            priority = priority == null ? PreloadPriority.NORMAL : priority;
            memoryMb = clampMemory(memoryMb);
            importMode = Math.max(PreloadControlPayload.IMPORT_OFF,
                    Math.min(PreloadControlPayload.IMPORT_M4CNE, importMode));
            importBudget = Math.max(1, Math.min(MAX_IMPORT_BUDGET, importBudget));
        }

        public static Settings defaults(ResourceLocation dimension, int centerChunkX, int centerChunkZ) {
            return new Settings(dimension, centerChunkX, centerChunkZ, DEFAULT_RADIUS, PreloadShape.SQUARE,
                    defaultThreads(), PreloadPriority.NORMAL, clampMemory(2048), true,
                    PreloadControlPayload.IMPORT_M4CNE, DEFAULT_IMPORT_BUDGET);
        }

        public PreloadRegion region() {
            return new PreloadRegion(this.centerChunkX, this.centerChunkZ, this.radius, this.shape);
        }

        public Settings withDimension(ResourceLocation value) {
            return new Settings(value, this.centerChunkX, this.centerChunkZ, this.radius, this.shape,
                    this.threads, this.priority, this.memoryMb, this.freezeTicks, this.importMode,
                    this.importBudget);
        }

        public Settings withCenter(int chunkX, int chunkZ) {
            return new Settings(this.dimension, chunkX, chunkZ, this.radius, this.shape, this.threads,
                    this.priority, this.memoryMb, this.freezeTicks, this.importMode, this.importBudget);
        }

        public Settings withRadius(int value) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, value, this.shape,
                    this.threads, this.priority, this.memoryMb, this.freezeTicks, this.importMode,
                    this.importBudget);
        }

        public Settings withShape(PreloadShape value) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, this.radius, value,
                    this.threads, this.priority, this.memoryMb, this.freezeTicks, this.importMode,
                    this.importBudget);
        }

        public Settings withThreads(int value) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, this.radius, this.shape,
                    value, this.priority, this.memoryMb, this.freezeTicks, this.importMode,
                    this.importBudget);
        }

        public Settings withPriority(PreloadPriority value) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, this.radius, this.shape,
                    this.threads, value, this.memoryMb, this.freezeTicks, this.importMode,
                    this.importBudget);
        }

        public Settings withMemory(int value) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, this.radius, this.shape,
                    this.threads, this.priority, value, this.freezeTicks, this.importMode,
                    this.importBudget);
        }

        public Settings withFreeze(boolean value) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, this.radius, this.shape,
                    this.threads, this.priority, this.memoryMb, value, this.importMode, this.importBudget);
        }

        public Settings withImportMode(int mode) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, this.radius, this.shape,
                    this.threads, this.priority, this.memoryMb, this.freezeTicks, mode, this.importBudget);
        }

        public Settings withImportBudget(int value) {
            return new Settings(this.dimension, this.centerChunkX, this.centerChunkZ, this.radius, this.shape,
                    this.threads, this.priority, this.memoryMb, this.freezeTicks, this.importMode, value);
        }
    }

    /** Result of {@link #start(ServerPlayer, Settings)}. */
    public enum StartOutcome {
        STARTED, RESUMED, CHUNKY_RUNNING, DIMENSION_MISSING
    }

    /** Result of {@link #resume()}. */
    public enum ResumeOutcome {
        RESUMED, NO_TASK, NOT_PAUSED, CHUNKY_RUNNING, DIMENSION_MISSING
    }

    /** Result of {@link #importTask(ServerPlayer, ResourceLocation, int, int)}. */
    public enum ImportOutcome {
        STARTED, NO_TASK, NOT_READY, DISABLED, NO_PLAYER
    }

    /** Read only snapshot reported by {@code /m4cne preload status}. */
    public record Status(boolean hasTask, PreloadState state, ResourceLocation dimension, int centerChunkX,
            int centerChunkZ, int radius, PreloadShape shape, int completed, int total, int threads,
            PreloadPriority priority, int memoryMb, int imported, int importTotal, int skipped,
            boolean importRunning, boolean c2me, boolean chunky, boolean frozen) {
    }

    /** Current command settings; never {@code null}. */
    public Settings settings() {
        return this.settings;
    }

    /**
     * Stores new command settings. The freeze flag is applied to a running task
     * immediately; every other value is used by the next
     * {@code /m4cne preload start}.
     */
    public void setSettings(Settings value) {
        if (value == null) {
            return;
        }
        this.settings = value;
        this.saveSettings();
        Task task = this.task;
        if (task == null) {
            return;
        }
        task.freezeTicks = value.freezeTicks();
        if (task.state == PreloadState.RUNNING) {
            this.applyFreeze(value.freezeTicks());
        }
        this.save(task);
    }

    /** Restores the default command settings centred on the given chunk. */
    public Settings resetSettings(ResourceLocation dimension, int centerChunkX, int centerChunkZ) {
        Settings next = Settings.defaults(dimension, centerChunkX, centerChunkZ);
        this.setSettings(next);
        return next;
    }

    /** True when the command source may drive the preload feature. */
    public boolean permitted(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? this.permitted(player) : source.hasPermission(requiredPermissionLevel());
    }

    public PreloadState state() {
        return this.task != null ? this.task.state : PreloadState.IDLE;
    }

    public boolean hasTask() {
        return this.task != null;
    }

    public boolean hasLevel(ResourceLocation dimension) {
        return this.level(dimension) != null;
    }

    public int heapLimitMb() {
        return (int) Math.max(64L, Runtime.getRuntime().maxMemory() / 1_048_576L);
    }

    /**
     * Starts (or resumes) a task from the given settings. The dimension must be
     * loaded and chunky must not be generating at the same time.
     */
    public StartOutcome start(ServerPlayer player, Settings requested) {
        Settings request = requested != null ? requested : this.settings;
        if (this.level(request.dimension()) == null) {
            this.hint = PreloadStatusPayload.HINT_DIMENSION_UNAVAILABLE;
            return StartOutcome.DIMENSION_MISSING;
        }
        if (ModCompat.chunkyRunning()) {
            this.hint = PreloadStatusPayload.HINT_CHUNKY_ACTIVE;
            return StartOutcome.CHUNKY_RUNNING;
        }
        if (player != null) {
            this.owner = player.getUUID();
        }
        this.hint = PreloadStatusPayload.HINT_NONE;
        boolean resumed = this.startRegion(request);
        return resumed ? StartOutcome.RESUMED : StartOutcome.STARTED;
    }

    /** Pauses the active task; {@code false} when there is no running task. */
    public boolean pause() {
        Task task = this.task;
        if (task == null || task.state != PreloadState.RUNNING) {
            return false;
        }
        this.releaseAllTickets(task);
        task.state = PreloadState.PAUSED;
        this.save(task);
        this.applyFreeze(false);
        AIGCsM4CnE.LOGGER.info("Preload paused in {} ({}/{})", task.dimension, task.bits.count(),
                task.region.total());
        return true;
    }

    /** Continues a paused task with its stored geometry and budget. */
    public ResumeOutcome resume() {
        Task task = this.task;
        if (task == null) {
            // After a server restart the progress record of the configured
            // dimension is attached again, so `resume` works without a client
            // query in between.
            task = this.load(this.settings.dimension());
            if (task == null) {
                return ResumeOutcome.NO_TASK;
            }
            this.task = task;
        }
        if (task.state == PreloadState.RUNNING) {
            return ResumeOutcome.NOT_PAUSED;
        }
        if (task.state != PreloadState.PAUSED) {
            return ResumeOutcome.NOT_PAUSED;
        }
        if (this.level(task.dimension) == null) {
            return ResumeOutcome.DIMENSION_MISSING;
        }
        if (ModCompat.chunkyRunning()) {
            return ResumeOutcome.CHUNKY_RUNNING;
        }
        task.state = PreloadState.RUNNING;
        this.save(task);
        this.applyFreeze(task.freezeTicks);
        AIGCsM4CnE.LOGGER.info("Preload resumed in {}", task.dimension);
        return ResumeOutcome.RESUMED;
    }

    /**
     * Cancels the active task and deletes its progress record. When no task is
     * active the stored progress of the current settings dimension is removed.
     *
     * @return {@code true} when a task or a progress record was removed
     */
    public boolean cancel() {
        boolean changed = false;
        Task task = this.task;
        if (task != null) {
            this.releaseAllTickets(task);
            this.delete(task.dimension);
            this.task = null;
            this.applyFreeze(false);
            changed = true;
        } else if (this.settings != null && Files.isRegularFile(this.fileFor(this.settings.dimension()))) {
            this.delete(this.settings.dimension());
            changed = true;
        }
        this.cancelImport();
        return changed;
    }

    /** Starts streaming the sampled tiles of a finished task to a player. */
    public ImportOutcome importTask(ServerPlayer player, ResourceLocation dimension, int mode, int budget) {
        this.cancelImport();
        if (player == null) {
            return ImportOutcome.NO_PLAYER;
        }
        if (mode != PreloadControlPayload.IMPORT_M4CNE && mode != PreloadControlPayload.IMPORT_CHUNKY) {
            return ImportOutcome.DISABLED;
        }
        ResourceLocation target = dimension != null ? dimension : this.settings.dimension();
        Task source = this.matches(target) ? this.task : this.load(target);
        if (source == null) {
            this.hint = PreloadStatusPayload.HINT_NO_TASK;
            return ImportOutcome.NO_TASK;
        }
        if (mode == PreloadControlPayload.IMPORT_M4CNE && source.state != PreloadState.DONE) {
            // The tiles are cached on the server while the task runs, but the
            // client only receives them once the whole region was loaded.
            this.hint = PreloadStatusPayload.HINT_NOT_READY;
            return ImportOutcome.NOT_READY;
        }
        this.importJob = new ImportJob(target, source.region, mode, Math.max(1, Math.min(64, budget)),
                player.getUUID());
        this.owner = player.getUUID();
        this.hint = PreloadStatusPayload.HINT_NONE;
        return ImportOutcome.STARTED;
    }

    /** Stops a running import job. */
    public void stopImport() {
        this.cancelImport();
    }

    /** Pushes the current status to the player who owns the task. */
    public void announce() {
        ServerPlayer player = this.owner != null ? this.server.getPlayerList().getPlayer(this.owner) : null;
        this.sendStatus(player, this.task);
    }

    /** Read only status snapshot for the status command. */
    public Status status() {
        Task task = this.task;
        int imported = 0;
        int importTotal = 0;
        int skipped = 0;
        if (this.importJob != null) {
            imported = this.importJob.imported;
            importTotal = this.importJob.region.total();
            skipped = this.importJob.skipped;
        } else if (task != null && (task.imported > 0 || task.skipped > 0)) {
            imported = task.imported;
            importTotal = task.region.total();
            skipped = task.skipped;
        }
        if (task == null) {
            Settings current = this.settings;
            return new Status(false, PreloadState.IDLE, current.dimension(), current.centerChunkX(),
                    current.centerChunkZ(), current.radius(), current.shape(), 0, 0, current.threads(),
                    current.priority(), current.memoryMb(), imported, importTotal, skipped,
                    this.importJob != null, ModCompat.c2meLoaded(), ModCompat.chunkyLoaded(),
                    this.frozenByTask);
        }
        return new Status(true, task.state, task.dimension, task.region.centerChunkX(),
                task.region.centerChunkZ(), task.region.radius(), task.region.shape(), task.bits.count(),
                task.region.total(), task.threads, task.priority, task.memoryMb, imported, importTotal,
                skipped, this.importJob != null, ModCompat.c2meLoaded(), ModCompat.chunkyLoaded(),
                this.frozenByTask);
    }

    /**
     * Freezes / unfreezes the server tick loop for command driven tasks and
     * restores the state that was active before the task froze it.
     */
    private void applyFreeze(boolean frozen) {
        if (frozen) {
            if (this.frozenByTask) {
                return;
            }
            this.freezeWasActive = this.server.tickRateManager().isFrozen();
            this.server.tickRateManager().setFrozen(true);
            this.frozenByTask = true;
        } else if (this.frozenByTask) {
            this.server.tickRateManager().setFrozen(this.freezeWasActive);
            this.frozenByTask = false;
        }
    }

    private static int defaultThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    private static boolean sameRegion(PreloadRegion first, PreloadRegion second) {
        return first.centerChunkX() == second.centerChunkX()
                && first.centerChunkZ() == second.centerChunkZ()
                && first.radius() == second.radius()
                && first.shape() == second.shape();
    }

    // -------------------------------------------------------------- permission

    private static int requiredPermissionLevel() {
        try {
            return Config.PRELOAD_REQUIRED_PERMISSION_LEVEL.getAsInt();
        } catch (IllegalStateException exception) {
            // Server config not loaded (should never happen while the server runs).
            return DEFAULT_PERMISSION_LEVEL;
        }
    }

    /**
     * Only the server owner and administrators may drive the preload feature.
     * The single player host is always allowed, even when the world has cheats
     * disabled and the vanilla permission level therefore stays at 0.
     */
    private boolean permitted(ServerPlayer player) {
        int required = requiredPermissionLevel();
        if (required <= 0) {
            return true;
        }
        if (this.server.isSingleplayerOwner(player.getGameProfile())) {
            return true;
        }
        return player.hasPermissions(required);
    }

    // ------------------------------------------------------- client chunk tracking

    private void rememberClientChunk(UUID player, ResourceLocation dimension, int chunkX, int chunkZ) {
        Map<ResourceLocation, LongOpenHashSet> dimensions = this.clientChunks.computeIfAbsent(player,
                id -> new HashMap<>());
        LongOpenHashSet chunks = dimensions.computeIfAbsent(dimension, id -> new LongOpenHashSet());
        if (chunks.size() >= MAX_TRACKED_CLIENT_CHUNKS) {
            return;
        }
        chunks.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    private boolean clientHasChunk(UUID player, ResourceLocation dimension, int chunkX, int chunkZ) {
        if (player == null) {
            return false;
        }
        Map<ResourceLocation, LongOpenHashSet> dimensions = this.clientChunks.get(player);
        if (dimensions == null) {
            return false;
        }
        LongOpenHashSet chunks = dimensions.get(dimension);
        return chunks != null && chunks.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    // ----------------------------------------------------------------- control

    private void handle(ServerPlayer player, PreloadControlPayload payload) {
        if (!this.permitted(player)) {
            // Reading the status stays allowed so the client can render the
            // permission hint, everything else is rejected on the server.
            if (payload.action() != PreloadControlPayload.ACTION_QUERY) {
                AIGCsM4CnE.LOGGER.warn("Rejected preload action {} from {} (permission level {} required)",
                        payload.action(), player.getGameProfile().getName(), requiredPermissionLevel());
                this.hint = PreloadStatusPayload.HINT_NO_PERMISSION;
            }
            this.sendStatus(player, this.task);
            return;
        }
        ResourceLocation dimension = payload.dimension();
        ServerLevel level = this.level(dimension);
        if (level == null) {
            this.hint = PreloadStatusPayload.HINT_DIMENSION_UNAVAILABLE;
            this.sendStatus(player, null);
            return;
        }
        this.owner = player.getUUID();
        this.hint = PreloadStatusPayload.HINT_NONE;

        switch (payload.action()) {
            case PreloadControlPayload.ACTION_QUERY -> {
                if (this.task == null || !this.task.dimension.equals(dimension)) {
                    if (this.task != null) {
                        // Remember the progress of the previous dimension before
                        // switching the active task.
                        this.releaseAllTickets(this.task);
                        this.save(this.task);
                    }
                    this.task = this.load(dimension);
                }
                this.sendStatus(player, this.task);
            }
            case PreloadControlPayload.ACTION_START -> {
                this.start(player, new Settings(dimension, payload.centerChunkX(),
                        payload.centerChunkZ(), payload.radius(), PreloadShape.byOrdinal(payload.shape()),
                        payload.threads(), PreloadPriority.byOrdinal(payload.priority()),
                        payload.memoryMb(), false, payload.importMode(), payload.importBudget()));
                this.sendStatus(player, this.task);
            }
            case PreloadControlPayload.ACTION_PAUSE -> {
                if (this.matches(dimension)) {
                    this.pause();
                }
                this.sendStatus(player, this.task);
            }
            case PreloadControlPayload.ACTION_RESUME -> {
                if (this.matches(dimension)) {
                    this.task.state = PreloadState.RUNNING;
                    this.task.threads = clampThreads(payload.threads());
                    this.task.priority = PreloadPriority.byOrdinal(payload.priority());
                    this.task.memoryMb = clampMemory(payload.memoryMb());
                    this.save(this.task);
                    this.applyFreeze(this.task.freezeTicks);
                }
                this.sendStatus(player, this.task);
            }
            case PreloadControlPayload.ACTION_CANCEL -> {
                if (this.matches(dimension)) {
                    this.cancel();
                } else {
                    this.delete(dimension);
                    this.cancelImport();
                }
                this.sendStatus(player, null);
            }
            case PreloadControlPayload.ACTION_IMPORT -> {
                this.importTask(player, dimension, payload.importMode(), payload.importBudget());
                this.sendStatus(player, this.task);
            }
            default -> this.sendStatus(player, this.task);
        }
    }

    /**
     * Starts a task (or resumes the progress of the same region).
     *
     * @return {@code true} when an existing region was resumed
     */
    private boolean startRegion(Settings request) {
        ResourceLocation dimension = request.dimension();
        PreloadRegion region = request.region();
        Task existing = this.task;
        if (existing != null) {
            if (existing.dimension.equals(dimension) && sameRegion(existing.region, region)) {
                // Starting an existing region resumes the stored progress.
                existing.state = PreloadState.RUNNING;
                existing.threads = request.threads();
                existing.priority = request.priority();
                existing.memoryMb = request.memoryMb();
                existing.freezeTicks = request.freezeTicks();
                this.save(existing);
                this.applyFreeze(existing.freezeTicks);
                AIGCsM4CnE.LOGGER.info("Preload resumed in {} ({}/{})", dimension, existing.bits.count(),
                        existing.region.total());
                return true;
            }
            this.releaseAllTickets(existing);
        }

        this.cancelImport();
        Task task = new Task(dimension, this.owner, region);
        task.threads = request.threads();
        task.priority = request.priority();
        task.memoryMb = request.memoryMb();
        task.freezeTicks = request.freezeTicks();
        task.state = PreloadState.RUNNING;

        Task stored = this.load(dimension);
        if (stored != null && sameRegion(stored.region, task.region)) {
            // Restarting the same geometry after a server restart continues the
            // persisted progress instead of starting from zero.
            task.bits = stored.bits;
        }
        this.task = task;
        this.save(task);
        this.applyFreeze(task.freezeTicks);
        AIGCsM4CnE.LOGGER.info("Preload started in {} ({} chunks, radius {}, {})", dimension,
                task.region.total(), region.radius(), region.shape());
        return false;
    }

    private boolean matches(ResourceLocation dimension) {
        return this.task != null && this.task.dimension.equals(dimension);
    }

    private ServerLevel level(ResourceLocation dimension) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimension);
        return this.server.getLevel(key);
    }

    private static int clampThreads(int threads) {
        int maximum = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(maximum, threads));
    }

    private static int clampMemory(int memoryMb) {
        long maximum = Runtime.getRuntime().maxMemory() / 1_048_576L;
        return (int) Math.max(64L, Math.min(maximum, memoryMb));
    }

    // -------------------------------------------------------------------- tick

    private void tick() {
        ServerPlayer player = this.owner != null ? this.server.getPlayerList().getPlayer(this.owner) : null;
        if (this.task != null) {
            this.tickTask();
        }
        if (this.importJob != null) {
            this.tickImport();
        }
        if (this.task != null && ++this.statusCounter >= STATUS_INTERVAL_TICKS) {
            this.statusCounter = 0;
            if (this.task.state == PreloadState.RUNNING) {
                this.sendStatus(player, this.task);
            }
        }
    }

    private void tickTask() {
        Task task = this.task;
        if (task.state != PreloadState.RUNNING) {
            return;
        }
        ServerLevel level = this.level(task.dimension);
        if (level == null) {
            task.state = PreloadState.FAILED;
            this.applyFreeze(false);
            this.save(task);
            return;
        }
        long now = level.getGameTime();
        int budget = Math.max(1, task.threads);
        int sampled = 0;

        // 1. Sample chunks that finished generating.
        while (sampled < budget && !task.ready.isEmpty()) {
            long key = task.ready.poll();
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            LevelChunk chunk = this.pollChunk(level, chunkX, chunkZ);
            if (chunk == null) {
                task.ready.addLast(key);
                break;
            }
            this.sampleInto(level, task, chunk);
            releaseTicket(level, TICKET, chunkX, chunkZ);
            task.inFlight.remove(key);
            sampled++;
        }

        // 2. Promote freshly loaded chunks of the in-flight window.
        if (!task.inFlight.isEmpty()) {
            Long2LongOpenHashMap done = new Long2LongOpenHashMap();
            for (Long2LongMap.Entry entry : task.inFlight.long2LongEntrySet()) {
                long key = entry.getLongKey();
                long submitted = entry.getLongValue();
                if (now - submitted > TICKET_TIMEOUT_TICKS) {
                    // The engine never finished this chunk; retry it later.
                    int chunkX = ChunkPos.getX(key);
                    int chunkZ = ChunkPos.getZ(key);
                    releaseTicket(level, TICKET, chunkX, chunkZ);
                    task.retry.add(PreloadRegion.packLocal(task.region.localX(chunkX), task.region.localZ(chunkZ)));
                    done.put(key, Long.MIN_VALUE);
                    continue;
                }
                int chunkX = ChunkPos.getX(key);
                int chunkZ = ChunkPos.getZ(key);
                if (this.pollChunk(level, chunkX, chunkZ) != null && !task.ready.contains(key)) {
                    task.ready.addLast(key);
                }
            }
            for (Long2LongMap.Entry entry : done.long2LongEntrySet()) {
                task.inFlight.remove(entry.getLongKey());
            }
        }

        // 3. Keep the generation pipeline full.
        int memoryCap = (int) Math.max(4L, task.memoryMb * 1_048_576L / CHUNK_MEMORY_ESTIMATE);
        int maxInFlight = Math.min(Math.max(8, task.threads * 6), memoryCap);
        int submitted = 0;
        while (submitted < budget && task.inFlight.size() < maxInFlight) {
            int packed = task.nextPending();
            if (packed == Integer.MIN_VALUE) {
                break;
            }
            int chunkX = task.region.chunkX(PreloadRegion.unpackLocalX(packed));
            int chunkZ = task.region.chunkZ(PreloadRegion.unpackLocalZ(packed));
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
            task.inFlight.put(pos.toLong(), now);
            submitted++;
        }

        // 4. Finish once every chunk of the region was sampled.
        if (task.bits.count() >= task.region.total() && task.inFlight.isEmpty() && task.ready.isEmpty()) {
            task.state = PreloadState.DONE;
            this.applyFreeze(false);
            this.save(task);
            // Flush the sampled tiles to the per-save repository before the
            // client is told that the data may be imported.
            ServerChunkStore store = ServerChunkStore.get();
            if (store != null) {
                store.saveNow();
            }
            ServerPlayer player = this.owner != null ? this.server.getPlayerList().getPlayer(this.owner) : null;
            this.sendStatus(player, task);
            this.hint = PreloadStatusPayload.HINT_NONE;
            AIGCsM4CnE.LOGGER.info("Preload finished in {} ({} chunks)", task.dimension, task.region.total());
        }
    }

    private LevelChunk pollChunk(ServerLevel level, int chunkX, int chunkZ) {
        try {
            return level.getChunkSource().getChunkNow(chunkX, chunkZ);
        } catch (RuntimeException exception) {
            // A chunk that is still being promoted can not be sampled yet.
            return null;
        }
    }

    private void sampleInto(ServerLevel level, Task task, LevelChunk chunk) {
        int localX = task.region.localX(chunk.getPos().x);
        int localZ = task.region.localZ(chunk.getPos().z);
        ChunkTile tile = new ChunkTile();
        EntityCensus census = EntityCensus.empty();
        try {
            boolean sampled = this.sampler.sample(level, chunk, tile);
            if (!sampled) {
                tile.clear();
            }
            census = this.sampler.census(level, chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to sample preloaded chunk {} {}", chunk.getPos().x, chunk.getPos().z,
                    exception);
        }
        if (task.bits.set(localX, localZ)) {
            task.completed = task.bits.count();
        }
        ServerChunkStore store = ServerChunkStore.get();
        if (store != null && !tile.isEmpty()) {
            store.storeChunk(task.dimension, chunk.getPos().x, chunk.getPos().z, tile, census);
        }
    }

    private void releaseAllTickets(Task task) {
        ServerLevel level = this.level(task.dimension);
        for (long key : task.inFlight.keySet()) {
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            if (level != null) {
                releaseTicket(level, TICKET, chunkX, chunkZ);
            }
            // The chunk was requested but not sampled yet, so it has to stay
            // pending. Otherwise a paused task could never reach 100%.
            task.retry.add(PreloadRegion.packLocal(task.region.localX(chunkX), task.region.localZ(chunkZ)));
        }
        task.inFlight.clear();
        task.ready.clear();
    }

    private static void releaseTicket(ServerLevel level, TicketType<ChunkPos> type, int chunkX, int chunkZ) {
        try {
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().removeRegionTicket(type, pos, 0, pos);
        } catch (RuntimeException exception) {
            AIGCsM4CnE.LOGGER.debug("Failed to release preload ticket at {},{}", chunkX, chunkZ, exception);
        }
    }

    // ------------------------------------------------------------------ import

    private void tickImport() {
        ImportJob job = this.importJob;
        ServerPlayer player = this.server.getPlayerList().getPlayer(job.owner);
        if (player == null) {
            this.cancelImport();
            return;
        }
        ServerLevel level = this.level(job.dimension);
        if (level == null) {
            this.cancelImport();
            return;
        }
        List<PreloadChunkPayload.Entry> batch = new ArrayList<>(MAX_IMPORT_BATCH);
        IntArrayList waiting = new IntArrayList();
        long now = level.getGameTime();

        for (int index = 0; index < job.budget; index++) {
            Integer packed = job.nextPending();
            if (packed == null) {
                break;
            }
            int chunkX = job.region.chunkX(PreloadRegion.unpackLocalX(packed));
            int chunkZ = job.region.chunkZ(PreloadRegion.unpackLocalZ(packed));

            if (this.clientHasChunk(job.owner, job.dimension, chunkX, chunkZ)) {
                // The client already loaded and recorded this chunk, so the
                // sync skips it completely.
                job.imported++;
                job.skipped++;
                continue;
            }

            if (job.mode == PreloadControlPayload.IMPORT_M4CNE) {
                job.imported++;
                ServerChunkStore store = ServerChunkStore.get();
                StoredChunk stored = store != null ? store.getChunk(job.dimension, chunkX, chunkZ) : null;
                if (stored == null || stored.tile == null) {
                    continue;
                }
                batch.add(entry(chunkX, chunkZ, stored.tile, stored.entities));
            } else {
                // Chunky stored its output in the vanilla save, so sampling the
                // generated chunks gives exactly the data chunky created.
                LevelChunk chunk = this.pollChunk(level, chunkX, chunkZ);
                if (chunk == null) {
                    long key = ChunkPos.asLong(chunkX, chunkZ);
                    if (!job.inFlight.containsKey(key)) {
                        if (job.attempts.addTo(key, 1) > MAX_IMPORT_ATTEMPTS) {
                            // The chunk never loads, for example because it is
                            // outside of the world border. Drop it instead of
                            // retrying it forever.
                            job.imported++;
                            continue;
                        }
                        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                        level.getChunkSource().addRegionTicket(IMPORT_TICKET, pos, 0, pos);
                        job.inFlight.put(key, now);
                    } else if (now - job.inFlight.get(key) > TICKET_TIMEOUT_TICKS) {
                        releaseTicket(level, IMPORT_TICKET, chunkX, chunkZ);
                        job.inFlight.remove(key);
                    }
                    waiting.add(packed);
                    continue;
                }
                ChunkTile tile = new ChunkTile();
                EntityCensus census = EntityCensus.empty();
                try {
                    this.sampler.sample(level, chunk, tile);
                    census = this.sampler.census(level, chunkX, chunkZ);
                } catch (RuntimeException exception) {
                    AIGCsM4CnE.LOGGER.warn("Failed to sample imported chunk {},{}", chunkX, chunkZ, exception);
                }
                releaseTicket(level, IMPORT_TICKET, chunkX, chunkZ);
                job.inFlight.remove(ChunkPos.asLong(chunkX, chunkZ));
                job.imported++;
                if (!tile.isEmpty()) {
                    batch.add(entry(chunkX, chunkZ, tile, census));
                }
            }

            if (batch.size() >= MAX_IMPORT_BATCH) {
                this.sendImport(player, job, batch);
                batch = new ArrayList<>(MAX_IMPORT_BATCH);
            }
        }

        job.restore(waiting);
        if (!batch.isEmpty()) {
            this.sendImport(player, job, batch);
        }
        if (this.matches(job.dimension)) {
            this.task.imported = job.imported;
            this.task.skipped = job.skipped;
        }
        if (job.finished()) {
            if (this.matches(job.dimension)) {
                this.save(this.task);
            }
            this.sendStatus(player, this.task);
            this.cancelImport();
        }
    }

    private void sendImport(ServerPlayer player, ImportJob job, List<PreloadChunkPayload.Entry> batch) {
        PacketDistributor.sendToPlayer(player, new PreloadChunkPayload(job.dimension, List.copyOf(batch)));
    }

    private static PreloadChunkPayload.Entry entry(int chunkX, int chunkZ, ChunkTile tile, EntityCensus census) {
        List<EntityCount> counts = census != null ? census.toList() : List.of();
        return new PreloadChunkPayload.Entry(chunkX, chunkZ,
                tile.colors, tile.heights, tile.shade, tile.skyLight, tile.blockLight, counts);
    }

    private void cancelImport() {
        ImportJob job = this.importJob;
        if (job == null) {
            return;
        }
        this.importJob = null;
        ServerLevel level = this.level(job.dimension);
        if (level != null) {
            for (long key : job.inFlight.keySet()) {
                releaseTicket(level, IMPORT_TICKET, ChunkPos.getX(key), ChunkPos.getZ(key));
            }
        }
    }

    // ------------------------------------------------------------------ status

    private void markHint(int value) {
        this.hint = value;
    }

    private void sendStatus(ServerPlayer player, Task task) {
        if (player == null) {
            return;
        }
        int imported = 0;
        int importTotal = 0;
        int skipped = 0;
        if (this.importJob != null) {
            imported = this.importJob.imported;
            importTotal = this.importJob.region.total();
            skipped = this.importJob.skipped;
        } else if (task != null && (task.imported > 0 || task.skipped > 0)) {
            imported = task.imported;
            importTotal = task.region.total();
            skipped = task.skipped;
        }
        PreloadState state = task != null ? task.state : PreloadState.IDLE;
        ResourceLocation dimension = task != null ? task.dimension : player.level().dimension().location();
        PreloadRegion region = task != null ? task.region : new PreloadRegion(0, 0, 0, PreloadShape.SQUARE);
        int completed = task != null ? task.bits.count() : 0;
        int total = task != null ? region.total() : 0;
        int threads = task != null ? task.threads : clampThreads(1);
        int priority = task != null ? task.priority.ordinal() : PreloadPriority.NORMAL.ordinal();
        int memory = task != null ? task.memoryMb : clampMemory(1024);

        PacketDistributor.sendToPlayer(player, new PreloadStatusPayload(
                state.ordinal(), dimension, region.centerChunkX(), region.centerChunkZ(), region.radius(),
                region.shape().ordinal(), completed, total, imported, importTotal, threads, priority, memory,
                ModCompat.c2meLoaded(), ModCompat.chunkyLoaded(), skipped, this.permitted(player),
                requiredPermissionLevel(), this.hint));
        this.hint = PreloadStatusPayload.HINT_NONE;
    }

    // ------------------------------------------------------------- persistence

    private Path fileFor(ResourceLocation dimension) {
        String path = dimension.getPath().replace('/', '_');
        return this.directory.resolve(dimension.getNamespace()).resolve(path + ".json");
    }

    private Path settingsFile() {
        return this.directory.resolve("settings.json");
    }

    /** Reads the command settings; falls back to the overworld spawn chunk. */
    private Settings readSettings() {
        int centerX = this.server.overworld().getSharedSpawnPos().getX() >> 4;
        int centerZ = this.server.overworld().getSharedSpawnPos().getZ() >> 4;
        Settings fallback = Settings.defaults(Level.OVERWORLD.location(), centerX, centerZ);
        Path filePath = this.settingsFile();
        if (!Files.isRegularFile(filePath)) {
            return fallback;
        }
        try {
            SettingsFile file = GSON.fromJson(Files.readString(filePath, StandardCharsets.UTF_8),
                    SettingsFile.class);
            if (file == null) {
                return fallback;
            }
            return new Settings(file.dimension == null ? null : ResourceLocation.tryParse(file.dimension),
                    file.centerChunkX, file.centerChunkZ, file.radius, parseShape(file.shape),
                    file.threads, parsePriority(file.priority), file.memoryMb, file.freezeTicks,
                    file.importMode, file.importBudget);
        } catch (IOException | RuntimeException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to read preload settings from {}", filePath, exception);
            return fallback;
        }
    }

    private void saveSettings() {
        Settings current = this.settings;
        if (current == null) {
            return;
        }
        SettingsFile file = new SettingsFile();
        file.dimension = current.dimension().toString();
        file.centerChunkX = current.centerChunkX();
        file.centerChunkZ = current.centerChunkZ();
        file.radius = current.radius();
        file.shape = current.shape().name();
        file.threads = current.threads();
        file.priority = current.priority().name();
        file.memoryMb = current.memoryMb();
        file.freezeTicks = current.freezeTicks();
        file.importMode = current.importMode();
        file.importBudget = current.importBudget();
        Path filePath = this.settingsFile();
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, GSON.toJson(file), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to write preload settings to {}", filePath, exception);
        }
    }

    private void save(Task task) {
        TaskFile file = new TaskFile();
        file.state = task.state.name();
        file.centerChunkX = task.region.centerChunkX();
        file.centerChunkZ = task.region.centerChunkZ();
        file.radius = task.region.radius();
        file.shape = task.region.shape().name();
        file.threads = task.threads;
        file.priority = task.priority.name();
        file.memoryMb = task.memoryMb;
        file.freezeTicks = task.freezeTicks;
        file.completed = task.bits.count();
        file.total = task.region.total();
        file.imported = task.imported;
        file.skipped = task.skipped;
        file.bits = task.bits.encode();
        file.updatedAt = System.currentTimeMillis();
        Path filePath = this.fileFor(task.dimension);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, GSON.toJson(file), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to write preload progress to {}", filePath, exception);
        }
    }

    private Task load(ResourceLocation dimension) {
        Path filePath = this.fileFor(dimension);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            TaskFile file = GSON.fromJson(json, TaskFile.class);
            if (file == null || file.radius <= 0) {
                return null;
            }
            PreloadShape shape = parseShape(file.shape);
            PreloadRegion region = new PreloadRegion(file.centerChunkX, file.centerChunkZ,
                    Math.min(PreloadRegion.MAX_RADIUS, file.radius), shape);
            Task task = new Task(dimension, this.owner, region);
            task.bits = ChunkBitset.decode(file.bits, region.width(), region.width());
            task.threads = clampThreads(file.threads);
            task.priority = parsePriority(file.priority);
            task.memoryMb = clampMemory(file.memoryMb);
            task.freezeTicks = file.freezeTicks;
            task.skipped = file.skipped;
            task.imported = file.imported;
            PreloadState state = parseState(file.state);
            // A task that was running when the game closed continues paused.
            task.state = state == PreloadState.RUNNING ? PreloadState.PAUSED : state;
            return task;
        } catch (IOException | RuntimeException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to read preload progress from {}", filePath, exception);
            return null;
        }
    }

    private void delete(ResourceLocation dimension) {
        Path filePath = this.fileFor(dimension);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to delete preload progress {}", filePath, exception);
        }
    }

    private static PreloadShape parseShape(String name) {
        try {
            return PreloadShape.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return PreloadShape.SQUARE;
        }
    }

    private static PreloadPriority parsePriority(String name) {
        try {
            return PreloadPriority.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return PreloadPriority.NORMAL;
        }
    }

    private static PreloadState parseState(String name) {
        try {
            return PreloadState.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return PreloadState.PAUSED;
        }
    }

    // ------------------------------------------------------------------- state

    private static final class Task {
        private final ResourceLocation dimension;
        private final UUID owner;
        private final PreloadRegion region;
        private ChunkBitset bits;
        private final int[] order;
        private int cursor;
        private PreloadState state = PreloadState.PAUSED;
        private int threads = 1;
        private PreloadPriority priority = PreloadPriority.NORMAL;
        private int memoryMb = 1024;
        private boolean freezeTicks;
        private int completed;
        private int imported;
        private int skipped;
        private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
        private final IntArrayList retry = new IntArrayList();
        private final Deque<Long> ready = new ArrayDeque<>();

        private Task(ResourceLocation dimension, UUID owner, PreloadRegion region) {
            this.dimension = dimension;
            this.owner = owner;
            this.region = region;
            this.bits = new ChunkBitset(region.width(), region.width());
            this.order = region.buildOrder();
            this.imported = 0;
        }

        private int nextPending() {
            while (!this.retry.isEmpty()) {
                int packed = this.retry.removeInt(this.retry.size() - 1);
                int localX = PreloadRegion.unpackLocalX(packed);
                int localZ = PreloadRegion.unpackLocalZ(packed);
                if (!this.bits.get(localX, localZ)) {
                    return packed;
                }
            }
            while (this.cursor < this.order.length) {
                int packed = this.order[this.cursor++];
                int localX = PreloadRegion.unpackLocalX(packed);
                int localZ = PreloadRegion.unpackLocalZ(packed);
                if (!this.bits.get(localX, localZ)) {
                    return packed;
                }
            }
            return Integer.MIN_VALUE;
        }
    }

    private static final class ImportJob {
        private final ResourceLocation dimension;
        private final PreloadRegion region;
        private final int mode;
        private final int budget;
        private final UUID owner;
        private final int[] order;
        private int cursor;
        private int imported;
        private int skipped;
        private final Deque<Integer> pushedBack = new ArrayDeque<>();
        private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
        private final Long2IntOpenHashMap attempts = new Long2IntOpenHashMap();

        private ImportJob(ResourceLocation dimension, PreloadRegion region, int mode, int budget, UUID owner) {
            this.dimension = dimension;
            this.region = region;
            this.mode = mode;
            this.budget = budget;
            this.owner = owner;
            this.order = region.buildOrder();
        }

        private Integer nextPending() {
            if (!this.pushedBack.isEmpty()) {
                return this.pushedBack.pollFirst();
            }
            return this.cursor < this.order.length ? this.order[this.cursor++] : null;
        }

        /** Re-queues chunks that are still generating, in front of the rest. */
        private void restore(IntArrayList stillWaiting) {
            for (int index = stillWaiting.size() - 1; index >= 0; index--) {
                if (this.pushedBack.size() >= 8192) {
                    break;
                }
                this.pushedBack.addFirst(stillWaiting.getInt(index));
            }
        }

        private boolean finished() {
            return this.cursor >= this.order.length && this.pushedBack.isEmpty() && this.inFlight.isEmpty();
        }
    }

    private static final class TaskFile {
        private String state = PreloadState.PAUSED.name();
        private int centerChunkX;
        private int centerChunkZ;
        private int radius;
        private String shape = PreloadShape.SQUARE.name();
        private int threads = 1;
        private String priority = PreloadPriority.NORMAL.name();
        private int memoryMb = 1024;
        private boolean freezeTicks;
        private int completed;
        private int total;
        private int skipped;
        private int imported;
        private String bits = "";
        private long updatedAt;
    }

    /** Persisted command settings of the preload feature. */
    private static final class SettingsFile {
        private String dimension = Level.OVERWORLD.location().toString();
        private int centerChunkX;
        private int centerChunkZ;
        private int radius = Settings.DEFAULT_RADIUS;
        private String shape = PreloadShape.SQUARE.name();
        private int threads = 1;
        private String priority = PreloadPriority.NORMAL.name();
        private int memoryMb = 1024;
        private boolean freezeTicks = true;
        private int importMode = PreloadControlPayload.IMPORT_M4CNE;
        private int importBudget = Settings.DEFAULT_IMPORT_BUDGET;
    }
}
