package com.wwwwz.aigcs.client.map;

import java.util.ArrayList;
import java.util.List;

import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientWorldKey;
import com.wwwwz.aigcs.core.WaypointData;
import com.wwwwz.aigcs.network.WaypointSyncPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Drives the automatic waypoint synchronisation on the client.
 * <p>
 * Every {@code waypoints.syncIntervalSeconds} the queued additions and
 * deletions are uploaded in small batches (a few waypoints per tick) instead of
 * one huge packet, which keeps the feature cheap even with many waypoints. The
 * server merges the batch and answers with the authoritative list.
 */
@SuppressWarnings("null")
public final class WaypointSyncManager {
    /** Waypoints uploaded per batch. */
    private static final int BATCH_SIZE = 8;
    /** Delay between two batches, so the upload trickles instead of bursting. */
    private static final long BATCH_INTERVAL_MS = 120L;

    private static final WaypointSyncManager INSTANCE = new WaypointSyncManager();

    private boolean supported = true;
    private boolean running;
    private long nextSyncAt;
    private long lastBatchAt;
    private int uploadIndex;
    private boolean deletesSent;
    private List<WaypointData> uploadQueue = new ArrayList<>();
    private List<String> deleteQueue = new ArrayList<>();
    private String worldKey = "";

    private WaypointSyncManager() {
    }

    public static WaypointSyncManager get() {
        return INSTANCE;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        INSTANCE.tick();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.reset();
    }

    private void reset() {
        this.running = false;
        this.supported = true;
        this.worldKey = "";
        this.uploadQueue = new ArrayList<>();
        this.deleteQueue = new ArrayList<>();
        this.uploadIndex = 0;
        this.deletesSent = false;
        this.nextSyncAt = 0L;
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        ClientPacketListener listener = minecraft.getConnection();
        if (player == null || level == null || listener == null) {
            return;
        }
        String world = ClientWorldKey.current();
        WaypointStore store = WaypointStore.get();
        store.ensureWorld(world);
        store.tickTrashAutoClear();
        long now = System.currentTimeMillis();
        if (!world.equals(this.worldKey)) {
            this.worldKey = world;
            this.running = false;
            // Sync once right after entering a world so the server list is
            // present immediately, then fall back to the configured interval.
            this.nextSyncAt = 0L;
        }
        if (!this.running && now >= this.nextSyncAt) {
            this.begin(listener);
        }
        if (this.running) {
            this.push(now);
        }
    }

    private void begin(ClientPacketListener listener) {
        this.nextSyncAt = System.currentTimeMillis() + this.intervalMs();
        if (!this.supported || !this.hasChannel(listener)) {
            this.supported = false;
            // Retry occasionally: the player may reconnect to a server that
            // actually ships the mod.
            this.nextSyncAt = System.currentTimeMillis() + 60_000L;
            return;
        }
        WaypointStore store = WaypointStore.get();
        this.uploadQueue = new ArrayList<>(store.pendingUpsertData());
        this.deleteQueue = new ArrayList<>(store.pendingDeletes());
        this.uploadIndex = 0;
        this.deletesSent = false;
        this.running = true;
        this.lastBatchAt = 0L;
        if (this.uploadQueue.isEmpty() && this.deleteQueue.isEmpty()) {
            // Nothing changed: the empty round still refreshes this client's
            // list with waypoints added by other players.
            this.send(new WaypointSyncPayload(List.of(), List.of(), true));
            this.running = false;
        }
    }

    private void push(long now) {
        if (now - this.lastBatchAt < BATCH_INTERVAL_MS) {
            return;
        }
        this.lastBatchAt = now;
        if (this.uploadIndex < this.uploadQueue.size()) {
            int end = Math.min(this.uploadQueue.size(), this.uploadIndex + BATCH_SIZE);
            List<WaypointData> batch = new ArrayList<>(this.uploadQueue.subList(this.uploadIndex, end));
            this.uploadIndex = end;
            boolean last = end >= this.uploadQueue.size() && this.deleteQueue.isEmpty();
            this.send(new WaypointSyncPayload(batch, List.of(), last));
            if (last) {
                this.running = false;
            }
            return;
        }
        if (!this.deletesSent) {
            this.deletesSent = true;
            this.send(new WaypointSyncPayload(List.of(), List.copyOf(this.deleteQueue), true));
            this.running = false;
        }
    }

    /** Called when the server sent the merged waypoint list. */
    public void onListReceived() {
        this.running = false;
        this.uploadIndex = 0;
        this.uploadQueue = new ArrayList<>();
        this.deleteQueue = new ArrayList<>();
        this.deletesSent = false;
        this.nextSyncAt = System.currentTimeMillis() + this.intervalMs();
    }

    /** Runs an immediate round; triggered by the server force sync signal. */
    public void requestForceSync() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null || minecraft.player == null) {
            return;
        }
        // The forced round refreshes *all* waypoints, not only the queued ones.
        WaypointStore.get().markAllForSync();
        this.running = false;
        this.begin(listener);
    }

    /** Uploads a single waypoint right away. */
    public boolean syncWaypoint(WaypointStore.Waypoint waypoint) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (waypoint == null || !waypoint.sync() || listener == null) {
            return false;
        }
        if (!this.hasChannel(listener)) {
            this.supported = false;
            return false;
        }
        this.send(new WaypointSyncPayload(List.of(waypoint.data()), List.of(), true));
        return true;
    }

    /** Marks every waypoint for the next upload (used by the force sync). */
    public void markAllForSync() {
        WaypointStore.get().markAllForSync();
    }

    public boolean isRunning() {
        return this.running;
    }

    public boolean isSupported() {
        return this.supported;
    }

    public long secondsUntilNextSync() {
        long remaining = this.nextSyncAt - System.currentTimeMillis();
        return Math.max(0L, (remaining + 999L) / 1000L);
    }

    private boolean hasChannel(ClientPacketListener listener) {
        try {
            return NetworkRegistry.hasChannel(listener.getConnection(), ConnectionProtocol.PLAY,
                    WaypointSyncPayload.TYPE.id());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private long intervalMs() {
        return Math.max(10, ClientConfig.WAYPOINT_SYNC_INTERVAL_SECONDS.getAsInt()) * 1000L;
    }

    private void send(WaypointSyncPayload payload) {
        try {
            PacketDistributor.sendToServer(payload);
        } catch (RuntimeException ignored) {
            // The connection may be closing; the next interval retries.
        }
    }
}
