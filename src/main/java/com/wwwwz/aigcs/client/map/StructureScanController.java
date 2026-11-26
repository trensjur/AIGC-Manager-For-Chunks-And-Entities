package com.wwwwz.aigcs.client.map;

import java.util.concurrent.ThreadLocalRandom;

import com.wwwwz.aigcs.network.StructureScanPayload;
import com.wwwwz.aigcs.network.StructureScanResultPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Client driver of the structure scan.
 * <p>
 * It sends the catalogue entry to the server, creates the waypoint when the
 * result arrives (with the mapped category and a random palette colour) and
 * exposes the state for the settings page.
 */
@SuppressWarnings("null")
public final class StructureScanController {
    private static final StructureScanController INSTANCE = new StructureScanController();
    private static final long TIMEOUT_MS = 30_000L;

    private String pendingId = "";
    private long requestedAt;
    private StructureScanResultPayload result;
    private long version;
    private boolean supported = true;

    private StructureScanController() {
    }

    public static StructureScanController get() {
        return INSTANCE;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        INSTANCE.tick();
    }

    /** Starts a scan; ignored while another scan is still running. */
    public void scan(StructureScanCatalog.Entry entry) {
        if (entry == null || this.isPending()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null || minecraft.player == null) {
            return;
        }
        boolean channel;
        try {
            channel = NetworkRegistry.hasChannel(listener.getConnection(), ConnectionProtocol.PLAY,
                    StructureScanPayload.TYPE.id());
        } catch (RuntimeException exception) {
            channel = false;
        }
        if (!channel) {
            this.supported = false;
            this.version++;
            return;
        }
        this.supported = true;
        this.pendingId = entry.id();
        this.requestedAt = System.currentTimeMillis();
        this.result = null;
        this.version++;
        try {
            PacketDistributor.sendToServer(new StructureScanPayload(entry.id(), entry.tag()));
        } catch (RuntimeException ignored) {
            // The connection may be closing; the scan simply times out.
        }
    }

    /** Called by the result payload. */
    public void onResult(StructureScanResultPayload payload) {
        if (payload == null || !payload.structureId().equals(this.pendingId)) {
            return;
        }
        this.pendingId = "";
        this.result = payload;
        if (payload.found()) {
            this.createWaypoint(payload);
        }
        this.version++;
    }

    private void createWaypoint(StructureScanResultPayload payload) {
        StructureScanCatalog.Entry entry = StructureScanCatalog.byId(payload.structureId());
        if (entry == null) {
            return;
        }
        String name = Component.translatable("screen.m4cne.structure.waypoint_name",
                Component.translatable(entry.translationKey()), payload.x(), payload.z()).getString();
        int color = WaypointColors.at(ThreadLocalRandom.current().nextInt(WaypointColors.PALETTE.length));
        WaypointStore.get().add(name, payload.x(), payload.y(), payload.z(), payload.dimension(), color,
                entry.category());
    }

    private void tick() {
        if (this.pendingId.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() - this.requestedAt < TIMEOUT_MS) {
            return;
        }
        this.pendingId = "";
        this.result = null;
        this.version++;
    }

    public boolean isPending() {
        return !this.pendingId.isEmpty();
    }

    public String pendingId() {
        return this.pendingId;
    }

    public StructureScanResultPayload result() {
        return this.result;
    }

    public long version() {
        return this.version;
    }

    public boolean isSupported() {
        return this.supported;
    }
}
