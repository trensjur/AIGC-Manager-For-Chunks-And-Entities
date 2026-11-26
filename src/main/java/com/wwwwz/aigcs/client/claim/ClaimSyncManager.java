package com.wwwwz.aigcs.client.claim;

import java.util.ArrayList;
import java.util.List;

import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientWorldKey;
import com.wwwwz.aigcs.core.claim.ClaimChunk;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;
import com.wwwwz.aigcs.network.ClaimActionPayload;
import com.wwwwz.aigcs.network.ClaimRulePayload;
import com.wwwwz.aigcs.network.ClaimSyncPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Uploads the own chunk claims and forwards the management actions.
 * <p>
 * The upload runs in small batches to keep the packet size bounded. When the
 * last batch of a round was sent the locally pending flags are cleared, so the
 * next authoritative list is applied without preserving stale edits.
 * <p>
 * The class also watches the chunk the player stands in and prints a chat
 * message when the player enters or leaves a claim of another player.
 */
@SuppressWarnings("null")
public final class ClaimSyncManager {
    /** Claims uploaded per batch. */
    private static final int BATCH_SIZE = 512;
    /** Delay between two batches, so the upload trickles instead of bursting. */
    private static final long BATCH_INTERVAL_MS = 100L;
    /** Delay after an edit before the round starts, so a drag only syncs once. */
    private static final long EDIT_DEBOUNCE_MS = 600L;
    /** How often the claim under the player is checked, in client ticks. */
    private static final int NOTIFY_INTERVAL_TICKS = 5;

    private static final ClaimSyncManager INSTANCE = new ClaimSyncManager();

    private boolean supported = true;
    private boolean running;
    /** {@code true} once the server sent its authoritative list for this world. */
    private boolean receivedList;
    private int roundId;
    private List<ClaimChunk> queue = List.of();
    private int queueIndex;
    private long lastBatchAt;
    private long roundVersion;
    private long lastSeenEditVersion = Long.MIN_VALUE;
    private long nextRoundAt;
    private int notifyTimer;

    private boolean admin;
    private int scanIntervalSeconds = 600;
    private int maxClaims = 10000;
    private int maxForced = 256;
    /** {@code true} when the server enabled the claim entity control feature. */
    private boolean entityControl = true;

    private String claimOwnerId = "";
    private String claimOwnerName = "";
    private String claimDimension = "";
    /** Dimension + chunk the "entered a chunk" hint was last evaluated for. */
    private String claimChunkKey = "";

    private ClaimSyncManager() {
    }

    public static ClaimSyncManager get() {
        return INSTANCE;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        INSTANCE.tick();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.reset();
    }

    /** @return {@code true} when the server provides the claim feature */
    public boolean supported() {
        return this.supported;
    }

    public boolean isAdmin() {
        return this.admin;
    }

    public int scanIntervalSeconds() {
        return this.scanIntervalSeconds;
    }

    public int maxClaims() {
        return this.maxClaims;
    }

    public int maxForced() {
        return this.maxForced;
    }

    /** @return {@code true} when the server enabled the claim entity control */
    public boolean entityControlSupported() {
        return this.entityControl;
    }

    public boolean isRunning() {
        return this.running;
    }

    /**
     * @return {@code true} when the server provides the claim feature and the
     *         authoritative list of this world already arrived
     */
    public boolean available() {
        return this.supported && this.receivedList;
    }

    /** Called when the server sent its authoritative list. */
    public void onListReceived(boolean admin, int scanIntervalSeconds, int maxClaims, int maxForced,
            boolean entityControl) {
        this.supported = true;
        this.receivedList = true;
        this.admin = admin;
        this.scanIntervalSeconds = Math.max(30, scanIntervalSeconds);
        this.maxClaims = Math.max(1, maxClaims);
        this.maxForced = Math.max(0, maxForced);
        this.entityControl = entityControl;
    }

    /** Starts a round right away; used by the server scan request. */
    public void requestScanRound(boolean manual) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        LocalPlayer player = minecraft.player;
        // Without the authoritative list an upload would erase the server state.
        if (listener == null || player == null || !this.receivedList) {
            return;
        }
        this.running = false;
        this.start(listener);
        if (manual) {
            player.displayClientMessage(
                    Component.translatable("screen.m4cne.claims.notify.scan"), true);
        }
    }

    /** Uploads the own claims immediately (page button). */
    public boolean syncNow() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null || minecraft.player == null || !this.receivedList) {
            return false;
        }
        this.running = false;
        return this.start(listener);
    }

    // ---------------------------------------------------------------- actions

    public boolean setBoundary(String ownerId, boolean value) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.SET_BOUNDARY, ownerId, value,
                0, "", List.of()));
    }

    public boolean setColor(int color) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.SET_COLOR, "",
                false, color & 0xFFFFFF, "", List.of()));
    }

    public boolean deleteClaims(String ownerId, List<ClaimChunk> chunks) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.DELETE_CLAIMS, ownerId,
                false, 0, "", chunks == null ? List.of() : chunks));
    }

    public boolean clearForce(String ownerId, List<ClaimChunk> chunks) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.CLEAR_FORCE, ownerId,
                false, 0, "", chunks == null ? List.of() : chunks));
    }

    public boolean requestScan() {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.REQUEST_SCAN, "",
                false, 0, "", List.of()));
    }

    public boolean setScanInterval(int seconds) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.SET_INTERVAL, "",
                false, Math.max(30, seconds), "", List.of()));
    }

    /** Renames some chunks of one owner (owner or administrator). */
    public boolean renameClaims(String ownerId, List<ClaimChunk> chunks, String id) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.RENAME_CLAIMS, ownerId,
                false, 0, id == null ? "" : id, chunks == null ? List.of() : chunks));
    }

    /** Toggles the 3D boundary wall of some chunks of one owner. */
    public boolean setChunkBoundary(String ownerId, List<ClaimChunk> chunks, boolean value) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.SET_CHUNK_BOUNDARY, ownerId,
                value, 0, "", chunks == null ? List.of() : chunks));
    }

    /** Toggles the enter message of some chunks of one owner. */
    public boolean setChunkMessage(String ownerId, List<ClaimChunk> chunks, boolean value) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.SET_CHUNK_MESSAGE, ownerId,
                value, 0, "", chunks == null ? List.of() : chunks));
    }

    /** Merges some chunks of one owner into the lexicographically smallest id. */
    public boolean mergeClaims(String ownerId, List<ClaimChunk> chunks) {
        return this.send(new ClaimActionPayload(ClaimActionPayload.Action.MERGE_CLAIMS, ownerId,
                false, 0, "", chunks == null ? List.of() : chunks));
    }

    /** Replaces the entity rules of one claim group (owner or administrator). */
    public boolean setGroupRules(String ownerId, String groupId, EntityRuleSet rules) {
        return this.send(new ClaimRulePayload(ownerId == null ? "" : ownerId,
                groupId == null ? "" : groupId, rules));
    }

    private boolean send(CustomPacketPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null || minecraft.player == null || !this.hasChannel(listener)) {
            this.supported = false;
            return false;
        }
        try {
            PacketDistributor.sendToServer(payload);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // ------------------------------------------------------------------- tick

    private void reset() {
        this.running = false;
        this.supported = true;
        this.entityControl = true;
        this.admin = false;
        this.receivedList = false;
        this.queue = List.of();
        this.queueIndex = 0;
        this.roundVersion = 0L;
        this.lastSeenEditVersion = Long.MIN_VALUE;
        this.nextRoundAt = 0L;
        this.claimOwnerId = "";
        this.claimOwnerName = "";
        this.claimDimension = "";
        this.claimChunkKey = "";
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientPacketListener listener = minecraft.getConnection();
        if (player == null || minecraft.level == null || listener == null) {
            return;
        }
        ClaimStore store = ClaimStore.get();
        store.ensureWorld(ClientWorldKey.current());
        this.notifyClaimChange(player);
        long now = System.currentTimeMillis();
        if (!this.running && this.receivedList && now >= this.nextRoundAt
                && store.editVersion() != this.lastSeenEditVersion) {
            this.start(listener);
        }
        if (this.running) {
            this.push(now);
        }
    }

    private boolean start(ClientPacketListener listener) {
        if (!this.hasChannel(listener)) {
            this.supported = false;
            this.nextRoundAt = System.currentTimeMillis() + 60_000L;
            return false;
        }
        this.supported = true;
        ClaimStore store = ClaimStore.get();
        this.queue = store.uploadChunks();
        this.queueIndex = 0;
        this.roundVersion = store.editVersion();
        this.lastSeenEditVersion = this.roundVersion;
        this.roundId++;
        this.lastBatchAt = 0L;
        this.running = true;
        if (this.queue.isEmpty() && store.self() == null) {
            // Nothing was ever claimed here: an empty round would only replace
            // the (already empty) server state.
            this.running = false;
            return true;
        }
        if (this.queue.isEmpty()) {
            // An empty round is authoritative as well: the player removed every
            // claim. The server replaces the stored set with it.
            this.send(new ClaimSyncPayload(this.roundId, List.of(), true));
            this.running = false;
            store.completeUpload(this.roundVersion);
            this.nextRoundAt = System.currentTimeMillis() + EDIT_DEBOUNCE_MS;
            return true;
        }
        this.push(System.currentTimeMillis());
        return true;
    }

    private void push(long now) {
        if (now - this.lastBatchAt < BATCH_INTERVAL_MS) {
            return;
        }
        this.lastBatchAt = now;
        int end = Math.min(this.queue.size(), this.queueIndex + BATCH_SIZE);
        List<ClaimChunk> batch = new ArrayList<>(this.queue.subList(this.queueIndex, end));
        this.queueIndex = end;
        boolean last = end >= this.queue.size();
        this.send(new ClaimSyncPayload(this.roundId, batch, last));
        if (last) {
            this.running = false;
            ClaimStore.get().completeUpload(this.roundVersion);
            this.nextRoundAt = now + EDIT_DEBOUNCE_MS;
        }
    }

    private void send(ClaimSyncPayload payload) {
        try {
            PacketDistributor.sendToServer(payload);
        } catch (RuntimeException ignored) {
            // The connection may be closing; the next round retries.
        }
    }

    // -------------------------------------------------------- chat reminders

    private void notifyClaimChange(LocalPlayer player) {
        if (++this.notifyTimer < NOTIFY_INTERVAL_TICKS) {
            return;
        }
        this.notifyTimer = 0;
        String dimension = player.level().dimension().location().toString();
        ChunkPos chunk = player.chunkPosition();
        ClaimStore.Ref ref = ClaimStore.get().refAt(dimension, chunk.x, chunk.z);
        String ownerId = ref == null ? "" : ref.owner().id();
        String ownerName = ref == null ? "" : ref.owner().name();
        String chunkKey = dimension + "#" + chunk.x + "," + chunk.z;
        boolean ownerChanged = !ownerId.equals(this.claimOwnerId)
                || !dimension.equals(this.claimDimension);
        boolean chunkChanged = !chunkKey.equals(this.claimChunkKey);
        if (!ownerChanged && !chunkChanged) {
            return;
        }
        String previousId = this.claimOwnerId;
        String previousName = this.claimOwnerName;
        this.claimOwnerId = ownerId;
        this.claimOwnerName = ownerName;
        this.claimDimension = dimension;
        this.claimChunkKey = chunkKey;

        // Per chunk "entered the chunk" hint; it works for claims of every
        // player, including the own chunks.
        if (chunkChanged && ref != null && ref.enterMessage()) {
            this.showEnterMessage(player, ref, chunk);
        }

        if (!ClientConfig.CLAIM_NOTIFY.getAsBoolean()) {
            return;
        }
        String selfId = ClaimStore.get().selfId();
        if (ownerChanged && !previousId.isEmpty() && !previousId.equals(selfId)) {
            player.displayClientMessage(Component.translatable("screen.m4cne.claims.notify.leave",
                    Component.literal(previousName).withStyle(ChatFormatting.YELLOW)), false);
        }
        if (ownerChanged && !ownerId.isEmpty() && !ownerId.equals(selfId)) {
            player.displayClientMessage(Component.translatable("screen.m4cne.claims.notify.enter",
                    Component.literal(ownerName).withStyle(ChatFormatting.AQUA)), false);
        }
    }

    /** Shows the enter hint of one chunk according to the configured mode. */
    private void showEnterMessage(LocalPlayer player, ClaimStore.Ref ref, ChunkPos chunk) {
        ClaimMessageMode mode = ClientConfig.CLAIM_ENTER_MESSAGE_MODE.get();
        if (mode == ClaimMessageMode.OFF) {
            return;
        }
        Component message = Component.translatable("screen.m4cne.claims.enter_message",
                Component.literal(ref.displayId(chunk.x, chunk.z)).withStyle(ChatFormatting.GOLD),
                Component.literal(ref.owner().name()).withStyle(ChatFormatting.AQUA));
        if (mode == ClaimMessageMode.ABOVE_HOTBAR && Minecraft.getInstance().gui != null) {
            Minecraft.getInstance().gui.setOverlayMessage(message, false);
            return;
        }
        player.displayClientMessage(message, false);
    }

    private boolean hasChannel(ClientPacketListener listener) {
        try {
            return NetworkRegistry.hasChannel(listener.getConnection(), ConnectionProtocol.PLAY,
                    ClaimSyncPayload.TYPE.id());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
