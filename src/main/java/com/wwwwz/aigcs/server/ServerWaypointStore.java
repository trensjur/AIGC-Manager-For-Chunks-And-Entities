package com.wwwwz.aigcs.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.core.WaypointCategory;
import com.wwwwz.aigcs.core.WaypointData;
import com.wwwwz.aigcs.network.WaypointForceSyncPayload;
import com.wwwwz.aigcs.network.WaypointListPayload;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side waypoint repository.
 * <p>
 * Clients upload their new and deleted waypoints in slow batches. The server
 * adds unknown waypoints, drops a waypoint only after every known client voted
 * for its deletion, persists the merged list and finally pushes the whole list
 * back to all players.
 */
@SuppressWarnings("null")
public final class ServerWaypointStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_WAYPOINTS = 1024;

    private static ServerWaypointStore instance;

    private final MinecraftServer server;
    private final Map<String, WaypointData> waypoints = new LinkedHashMap<>();
    private final Set<String> knownClients = new LinkedHashSet<>();
    private final Map<String, Set<String>> deleteVotes = new LinkedHashMap<>();
    private Path file;

    private ServerWaypointStore(MinecraftServer server) {
        this.server = server;
    }

    public static ServerWaypointStore get() {
        return instance;
    }

    public static void onServerStarted(ServerStartedEvent event) {
        ServerWaypointStore store = new ServerWaypointStore(event.getServer());
        store.file = event.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("m4cne").resolve("waypoints.json");
        store.load();
        instance = store;
        AIGCsM4CnE.LOGGER.debug("Loaded {} synchronised waypoints", store.waypoints.size());
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    /** Sends the current list to a player that just joined. */
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            instance.sendAll(player);
        }
    }

    /** Merges one client batch and broadcasts the list on the last batch. */
    public synchronized void handle(ServerPlayer player, List<WaypointData> upserts, List<String> deletes,
            boolean lastBatch) {
        String client = player.getUUID().toString();
        boolean changed = this.knownClients.add(client);
        if (upserts != null) {
            for (WaypointData raw : upserts) {
                WaypointData data = sanitize(raw);
                if (data == null) {
                    continue;
                }
                if (!this.waypoints.containsKey(data.id()) && this.waypoints.size() >= MAX_WAYPOINTS) {
                    continue;
                }
                this.waypoints.put(data.id(), data);
                Set<String> votes = this.deleteVotes.get(data.id());
                if (votes != null) {
                    votes.remove(client);
                    if (votes.isEmpty()) {
                        this.deleteVotes.remove(data.id());
                    }
                }
                changed = true;
            }
        }
        if (deletes != null) {
            for (String rawId : deletes) {
                if (rawId == null || rawId.isBlank()) {
                    continue;
                }
                String id = rawId.length() > 64 ? rawId.substring(0, 64) : rawId;
                if (!this.waypoints.containsKey(id)) {
                    this.deleteVotes.remove(id);
                    continue;
                }
                Set<String> votes = this.deleteVotes.computeIfAbsent(id,
                        ignored -> new LinkedHashSet<>());
                if (votes.add(client)) {
                    changed = true;
                }
                if (votes.containsAll(this.knownClients)) {
                    this.waypoints.remove(id);
                    this.deleteVotes.remove(id);
                    changed = true;
                }
            }
        }
        if (changed) {
            this.save();
        }
        if (lastBatch) {
            this.broadcast();
        }
    }

    /** Signals every online client to upload all of its waypoints again. */
    public synchronized int requestForceSync() {
        int sent = 0;
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, new WaypointForceSyncPayload());
            sent++;
        }
        return sent;
    }

    public synchronized int waypointCount() {
        return this.waypoints.size();
    }

    private void sendAll(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, this.listPayload());
    }

    private void broadcast() {
        WaypointListPayload payload = this.listPayload();
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private WaypointListPayload listPayload() {
        return new WaypointListPayload(List.copyOf(this.waypoints.values()));
    }

    private static WaypointData sanitize(WaypointData data) {
        if (data == null || data.id() == null || data.id().isBlank()) {
            return null;
        }
        String id = limit(data.id().trim(), 64);
        if (id.isEmpty()) {
            return null;
        }
        String name = limit(data.name() == null ? "" : data.name().trim(), 48);
        if (name.isEmpty()) {
            name = "Waypoint";
        }
        ResourceLocation dimension = ResourceLocation.tryParse(
                data.dimension() == null ? "" : data.dimension());
        if (dimension == null) {
            return null;
        }
        return new WaypointData(id, name,
                Mth.clamp(data.x(), -30_000_000, 30_000_000),
                Mth.clamp(data.y(), -2048, 2048),
                Mth.clamp(data.z(), -30_000_000, 30_000_000),
                dimension.toString(), data.color() & 0xFFFFFF,
                WaypointCategory.byName(data.category()).name());
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private void load() {
        if (this.file == null || !Files.isRegularFile(this.file)) {
            return;
        }
        try {
            StoredFile decoded = GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8),
                    StoredFile.class);
            if (decoded == null) {
                return;
            }
            if (decoded.waypoints != null) {
                for (StoredWaypoint stored : decoded.waypoints) {
                    WaypointData data = stored != null ? stored.toData() : null;
                    if (data != null) {
                        this.waypoints.put(data.id(), data);
                    }
                }
            }
            if (decoded.knownClients != null) {
                this.knownClients.addAll(decoded.knownClients);
            }
            if (decoded.deleteVotes != null) {
                for (Map.Entry<String, List<String>> entry : decoded.deleteVotes.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        this.deleteVotes.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
                    }
                }
            }
        } catch (Exception exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to read synchronised waypoints from {}", this.file, exception);
        }
    }

    private void save() {
        if (this.file == null) {
            return;
        }
        StoredFile data = new StoredFile();
        for (WaypointData waypoint : this.waypoints.values()) {
            data.waypoints.add(StoredWaypoint.of(waypoint));
        }
        data.knownClients.addAll(this.knownClients);
        for (Map.Entry<String, Set<String>> entry : this.deleteVotes.entrySet()) {
            data.deleteVotes.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to write synchronised waypoints to {}", this.file, exception);
        }
    }

    private static final class StoredFile {
        private List<StoredWaypoint> waypoints = new ArrayList<>();
        private List<String> knownClients = new ArrayList<>();
        private Map<String, List<String>> deleteVotes = new LinkedHashMap<>();
    }

    private static final class StoredWaypoint {
        private String id;
        private String name;
        private int x;
        private int y;
        private int z;
        private String dimension;
        private int color;
        private String category;

        private static StoredWaypoint of(WaypointData data) {
            StoredWaypoint stored = new StoredWaypoint();
            stored.id = data.id();
            stored.name = data.name();
            stored.x = data.x();
            stored.y = data.y();
            stored.z = data.z();
            stored.dimension = data.dimension();
            stored.color = data.color();
            stored.category = data.category();
            return stored;
        }

        private WaypointData toData() {
            if (this.id == null || this.name == null || this.dimension == null) {
                return null;
            }
            return new WaypointData(this.id, this.name, this.x, this.y, this.z, this.dimension,
                    this.color, WaypointCategory.byName(this.category).name());
        }
    }
}
