package com.wwwwz.aigcs.client.preload;

import com.wwwwz.aigcs.client.ClientChunkRecorder;
import com.wwwwz.aigcs.client.ClientChunkStore;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientWorldKey;
import com.wwwwz.aigcs.compat.ModCompat;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;
import com.wwwwz.aigcs.core.preload.PreloadPriority;
import com.wwwwz.aigcs.core.preload.PreloadRegion;
import com.wwwwz.aigcs.core.preload.PreloadShape;
import com.wwwwz.aigcs.core.preload.PreloadState;
import com.wwwwz.aigcs.network.PreloadChunkPayload;
import com.wwwwz.aigcs.network.PreloadControlPayload;
import com.wwwwz.aigcs.network.PreloadStatusPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Client side driver of the preload feature.
 * <p>
 * It turns the settings page into control payloads, mirrors the task state the
 * server reports, freezes the game ticks while a task runs and feeds imported
 * chunk batches into the client chunk cache so the world map can draw them.
 */
@SuppressWarnings("null")
public final class PreloadController {
    /** Status mirror of the server task. */
    public record Status(PreloadState state, ResourceLocation dimension, int centerChunkX, int centerChunkZ,
            int radius, PreloadShape shape, int completed, int total, int imported, int importTotal,
            int threads, PreloadPriority priority, int memoryMb, boolean c2me, boolean chunky, int skipped,
            boolean permitted, int permissionLevel, int hint) {

        public double progress() {
            return this.total <= 0 ? 0.0D : Math.min(1.0D, this.completed / (double) this.total);
        }

        public double importProgress() {
            return this.importTotal <= 0 ? 0.0D : Math.min(1.0D, this.imported / (double) this.importTotal);
        }

        public boolean hasTask() {
            return this.state.hasTask() || this.state == PreloadState.DONE;
        }

        public PreloadRegion region() {
            return new PreloadRegion(this.centerChunkX, this.centerChunkZ, this.radius, this.shape);
        }
    }

    private static final Status IDLE = new Status(PreloadState.IDLE,
            ResourceLocation.withDefaultNamespace("overworld"), 0, 0, 0, PreloadShape.SQUARE, 0, 0, 0, 0,
            PreloadLimits.defaultThreads(), PreloadPriority.NORMAL, PreloadLimits.defaultMemoryMb(), false, false,
            0, true, 2, PreloadStatusPayload.HINT_NONE);

    private static final PreloadController INSTANCE = new PreloadController();
    private static final long QUERY_INTERVAL_MS = 900L;

    private Status status = IDLE;
    private boolean supported = true;
    private boolean frozen;
    private boolean screenOpen;
    private boolean autoImportTriggered;
    private long lastQueryAt;
    private long importedThisSession;
    private long skippedThisSession;
    private long statusVersion;
    private String worldKey = "";

    private PreloadController() {
    }

    public static PreloadController get() {
        return INSTANCE;
    }

    public Status status() {
        return this.status;
    }

    /** Incremented whenever a new status snapshot arrived, drives live labels. */
    public long statusVersion() {
        return this.statusVersion;
    }

    /** {@code false} once the server side of the mod turned out to be absent. */
    public boolean supported() {
        return this.supported;
    }

    public long importedThisSession() {
        return this.importedThisSession;
    }

    /** Chunks the client ignored because it already had them locally. */
    public long skippedThisSession() {
        return this.skippedThisSession;
    }

    /** {@code true} when the server granted this client access to the feature. */
    public boolean permitted() {
        return this.status.permitted();
    }

    /** Whether the client may drive the preload feature at all. */
    public boolean canControlPreload() {
        return this.supported && this.status.permitted();
    }

    // ------------------------------------------------------------- lifecycle

    public static void onClientTick(ClientTickEvent.Post event) {
        INSTANCE.tick();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.reset();
    }

    private void reset() {
        this.status = IDLE;
        this.frozen = false;
        this.screenOpen = false;
        this.autoImportTriggered = false;
        this.supported = true;
        this.importedThisSession = 0L;
        this.skippedThisSession = 0L;
        this.worldKey = "";
        this.lastQueryAt = 0L;
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || minecraft.getConnection() == null) {
            this.frozen = false;
            return;
        }
        String world = ClientWorldKey.current();
        if (!world.equals(this.worldKey)) {
            this.worldKey = world;
            this.status = IDLE;
            this.autoImportTriggered = false;
            this.importedThisSession = 0L;
            this.skippedThisSession = 0L;
            PreloadMarkerStore.get().ensureWorld(world);
            ModCompat.invalidate();
        }
        if (this.screenOpen || this.status.state() == PreloadState.RUNNING) {
            this.requestStatusIfStale();
        }
        this.updateFreeze();
        this.checkAutoImport();
    }

    /**
     * Tells the controller that the world map (and therefore the panel) is open.
     */
    public void setScreenOpen(boolean open) {
        this.screenOpen = open;
        if (open) {
            this.requestStatusIfStale();
        }
    }

    private void requestStatusIfStale() {
        long now = System.currentTimeMillis();
        if (now - this.lastQueryAt < QUERY_INTERVAL_MS) {
            return;
        }
        this.lastQueryAt = now;
        this.send(PreloadControlPayload.ACTION_QUERY, null, 0, 0);
    }

    private void updateFreeze() {
        boolean shouldFreeze = ClientConfig.PRELOAD_FREEZE_TICKS.getAsBoolean()
                && this.permitted()
                && this.status.state() == PreloadState.RUNNING;
        if (shouldFreeze == this.frozen) {
            return;
        }
        this.frozen = shouldFreeze;
        // Vanilla only, exactly as requested: the game rule based freeze the
        // player can also toggle by hand.
        this.sendCommand(shouldFreeze ? "tick freeze" : "tick unfreeze");
    }

    private void checkAutoImport() {
        PreloadState state = this.status.state();
        if (state == PreloadState.RUNNING || state == PreloadState.PAUSED) {
            this.autoImportTriggered = false;
            return;
        }
        if (this.autoImportTriggered) {
            return;
        }
        // The server caches the preloaded tiles locally and only allows the
        // import once the whole region finished loading.
        if (state != PreloadState.DONE || !this.permitted()) {
            return;
        }
        this.autoImportTriggered = true;
        if (ClientConfig.PRELOAD_IMPORT_MODE.get() != PreloadImportMode.M4CNE
                || !ClientConfig.PRELOAD_IMPORT_AUTO.getAsBoolean()) {
            return;
        }
        this.send(PreloadControlPayload.ACTION_IMPORT, null, 0, 0);
    }

    // ---------------------------------------------------------------- actions

    /**
     * Starts (or resumes) a task for the current dimension.
     *
     * @return a localised error message when the request could not be sent
     */
    public Component startPreload() {
        if (!this.supported) {
            return Component.translatable("screen.m4cne.preload.error.unsupported");
        }
        if (!this.permitted()) {
            return Component.translatable("screen.m4cne.preload.error.no_permission");
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return Component.translatable("screen.m4cne.no_world");
        }
        BlockPos center = this.resolveCenter(level, player);
        if (center == null) {
            return Component.translatable("screen.m4cne.preload.error.no_marker");
        }
        this.send(PreloadControlPayload.ACTION_START, level.dimension().location(),
                center.getX() >> 4, center.getZ() >> 4);
        return this.supported ? null : Component.translatable("screen.m4cne.preload.error.unsupported");
    }

    public void pausePreload() {
        if (!this.canControlPreload()) {
            return;
        }
        this.send(PreloadControlPayload.ACTION_PAUSE, this.dimension(), 0, 0);
    }

    public void resumePreload() {
        if (!this.canControlPreload()) {
            return;
        }
        this.send(PreloadControlPayload.ACTION_RESUME, this.dimension(), 0, 0);
    }

    public void cancelPreload() {
        if (!this.canControlPreload()) {
            return;
        }
        this.send(PreloadControlPayload.ACTION_CANCEL, this.dimension(), 0, 0);
        this.autoImportTriggered = false;
        this.importedThisSession = 0L;
        this.skippedThisSession = 0L;
        // Cancel also drops the preload chunk cache: the sampler forgets every
        // hash, so the next sweep records the loaded chunks again from scratch.
        ClientChunkRecorder.get().clearSampledCache();
    }

    /** Manually starts the import of the last preload region. */
    public Component importPreload() {
        if (!this.supported) {
            return Component.translatable("screen.m4cne.preload.error.unsupported");
        }
        if (!this.permitted()) {
            return Component.translatable("screen.m4cne.preload.error.no_permission");
        }
        PreloadImportMode mode = ClientConfig.PRELOAD_IMPORT_MODE.get();
        if (mode == PreloadImportMode.OFF) {
            return Component.translatable("screen.m4cne.preload.error.import_off");
        }
        if (mode == PreloadImportMode.M4CNE && this.status.state() != PreloadState.DONE) {
            // The tiles are only synced after the server side preload finished.
            return Component.translatable("screen.m4cne.preload.error.not_ready");
        }
        this.send(PreloadControlPayload.ACTION_IMPORT, this.dimension(), 0, 0);
        return this.supported ? null : Component.translatable("screen.m4cne.preload.error.unsupported");
    }

    /** Centre block position of a new task, {@code null} when no marker exists. */
    public BlockPos resolveCenter(ClientLevel level, LocalPlayer player) {
        PreloadCenterMode mode = ClientConfig.PRELOAD_CENTER_MODE.get();
        return switch (mode) {
            case SPAWN -> level.getSharedSpawnPos();
            case PLAYER -> player.blockPosition();
            case MARKER -> {
                PreloadMarkerStore.Marker marker = PreloadMarkerStore.get()
                        .active(level.dimension().location());
                yield marker != null ? new BlockPos(marker.x(), player.getBlockY(), marker.z()) : null;
            }
        };
    }

    private ResourceLocation dimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? level.dimension().location() : this.status.dimension();
    }

    // ---------------------------------------------------------------- network

    private void send(int action, ResourceLocation dimension, int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        ClientLevel level = minecraft.level;
        if (listener == null || level == null) {
            return;
        }
        ResourceLocation target = dimension != null ? dimension : level.dimension().location();
        if (!this.supported) {
            return;
        }
        if (action != PreloadControlPayload.ACTION_QUERY && !this.permitted()) {
            // The server would reject it anyway; do not even try.
            return;
        }
        try {
            if (!NetworkRegistry.hasChannel(listener.getConnection(), ConnectionProtocol.PLAY,
                    PreloadControlPayload.TYPE.id())) {
                this.supported = false;
                return;
            }
        } catch (RuntimeException exception) {
            this.supported = false;
            return;
        }
        int radius = Math.max(1, Math.min(PreloadRegion.MAX_RADIUS, ClientConfig.PRELOAD_RADIUS_CHUNKS.getAsInt()));
        PreloadShape shape = ClientConfig.PRELOAD_SHAPE.get();
        int threads = PreloadLimits.clampThreads(ClientConfig.PRELOAD_THREADS.getAsInt());
        int priority = ClientConfig.PRELOAD_PRIORITY.get().ordinal();
        int memory = PreloadLimits.clampMemory(ClientConfig.PRELOAD_MEMORY_MB.getAsInt());
        int importMode = ClientConfig.PRELOAD_IMPORT_MODE.get().protocolValue();
        int importBudget = ClientConfig.PRELOAD_IMPORT_BUDGET.getAsInt();
        PreloadControlPayload payload = new PreloadControlPayload(action, target, chunkX, chunkZ, radius,
                shape.ordinal(), threads, priority, memory, importMode, importBudget);
        try {
            PacketDistributor.sendToServer(payload);
        } catch (RuntimeException exception) {
            this.supported = false;
        }
    }

    private void sendCommand(String command) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null) {
            return;
        }
        try {
            listener.sendCommand(command);
        } catch (RuntimeException ignored) {
            // Without command permission the freeze simply does not happen.
        }
    }

    // -------------------------------------------------------------- responses

    /** Called by the status payload handler. */
    public void onStatus(PreloadStatusPayload payload) {
        PreloadState state = PreloadState.byOrdinal(payload.state());
        PreloadShape shape = PreloadShape.byOrdinal(payload.shape());
        this.status = new Status(state, payload.dimension(), payload.centerChunkX(), payload.centerChunkZ(),
                payload.radius(), shape, payload.completed(), payload.total(), payload.imported(),
                payload.importTotal(), payload.threads(), PreloadPriority.byOrdinal(payload.priority()),
                payload.memoryMb(), payload.c2me(), payload.chunky(), payload.skipped(), payload.permitted(),
                payload.permissionLevel(), payload.hint());
        this.statusVersion++;
        this.supported = true;
        if (payload.hint() == PreloadStatusPayload.HINT_CHUNKY_ACTIVE) {
            // Chunky is generating; make sure our own freeze is released.
            this.updateFreeze();
        }
    }

    /** Called by the imported chunk payload handler. */
    public void acceptChunks(PreloadChunkPayload payload) {
        ResourceLocation dimension = payload.dimension();
        for (PreloadChunkPayload.Entry entry : payload.chunks()) {
            ClientChunkStore.Entry existing = ClientChunkStore.get().get(dimension, entry.chunkX(),
                    entry.chunkZ());
            if (existing != null && existing.tile() != null && !existing.tile().isEmpty()) {
                // The client already loaded this chunk itself, so the synced
                // (possibly older) server tile is skipped.
                this.skippedThisSession++;
                continue;
            }
            ChunkTile tile = entry.toTile();
            EntityCensus census = entry.toCensus();
            ClientChunkStore.get().put(dimension, entry.chunkX(), entry.chunkZ(), tile, census, null,
                    tile.fingerprint(), census.fingerprint());
            this.importedThisSession++;
        }
    }
}
