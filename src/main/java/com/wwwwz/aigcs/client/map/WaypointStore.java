package com.wwwwz.aigcs.client.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.core.WaypointCategory;
import com.wwwwz.aigcs.core.WaypointData;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Per world waypoint storage.
 * <p>
 * Waypoints are a client convenience feature, so they live in the client config
 * directory. The file name is derived from
 * {@link com.wwwwz.aigcs.client.ClientWorldKey}
 * so two saves (or two servers) can never share waypoints.
 */
@SuppressWarnings("null")
public final class WaypointStore {
    /**
     * One stored waypoint.
     * <p>
     * {@code id} is stable across clients so the server can merge the lists;
     * {@code sync} and {@code showDistance} are local-only display flags and
     * are never sent to the server.
     */
    public record Waypoint(String id, String name, int x, int y, int z, String dimension, int color,
            WaypointCategory category, boolean sync, boolean showDistance) {

        public Waypoint {
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            if (name == null || name.isBlank()) {
                name = "Waypoint";
            }
            if (dimension == null || dimension.isBlank()) {
                dimension = "minecraft:overworld";
            }
            if (category == null) {
                category = WaypointCategory.VILLAGE;
            }
            color &= 0xFFFFFF;
        }

        public ResourceLocation dimensionId() {
            ResourceLocation id = ResourceLocation.tryParse(this.dimension);
            return id != null ? id : ResourceLocation.withDefaultNamespace("overworld");
        }

        public WaypointData data() {
            return new WaypointData(this.id, this.name, this.x, this.y, this.z, this.dimension, this.color,
                    this.category.name());
        }

        public static Waypoint fromData(WaypointData data, boolean showDistance) {
            return new Waypoint(data.id(), data.name(), data.x(), data.y(), data.z(), data.dimension(),
                    data.color(), data.categoryValue(), true, showDistance);
        }

        public Waypoint withSync(boolean value) {
            return new Waypoint(this.id, this.name, this.x, this.y, this.z, this.dimension, this.color,
                    this.category, value, this.showDistance);
        }

        public Waypoint withShowDistance(boolean value) {
            return new Waypoint(this.id, this.name, this.x, this.y, this.z, this.dimension, this.color,
                    this.category, this.sync, value);
        }
    }

    /** A deleted waypoint waiting for the auto purge timer. */
    public record TrashEntry(Waypoint waypoint, long deletedAt) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final WaypointStore INSTANCE = new WaypointStore();

    private final List<Waypoint> waypoints = new ArrayList<>();
    private final List<TrashEntry> trash = new ArrayList<>();
    /** Waypoints changed locally since the last server round trip. */
    private final Set<String> pendingUpserts = new LinkedHashSet<>();
    /** Waypoints deleted locally and not yet confirmed by the server. */
    private final Set<String> pendingDeletes = new LinkedHashSet<>();
    private String worldKey = "";
    private Path file;
    /** Incremented on every persisted change, drives the live sidebar lists. */
    private long version;

    private WaypointStore() {
    }

    public static WaypointStore get() {
        return INSTANCE;
    }

    /** Changes whenever the waypoint or recycle bin content changed. */
    public long version() {
        return this.version;
    }

    public void ensureWorld(String world) {
        if (world.equals(this.worldKey)) {
            return;
        }
        this.worldKey = world;
        this.waypoints.clear();
        this.trash.clear();
        this.pendingUpserts.clear();
        this.pendingDeletes.clear();
        this.file = resolveFile(world);
        this.load();
    }

    public List<Waypoint> all() {
        return List.copyOf(this.waypoints);
    }

    public List<Waypoint> forDimension(ResourceLocation dimension) {
        List<Waypoint> result = new ArrayList<>();
        String id = dimension.toString();
        for (Waypoint waypoint : this.waypoints) {
            if (waypoint.dimension().equals(id)) {
                result.add(waypoint);
            }
        }
        return result;
    }

    public int count() {
        return this.waypoints.size();
    }

    public int trashCount() {
        return this.trash.size();
    }

    public List<TrashEntry> trash() {
        return List.copyOf(this.trash);
    }

    /** @return the waypoint with the given id or {@code null} */
    public Waypoint get(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Waypoint waypoint : this.waypoints) {
            if (waypoint.id().equals(id)) {
                return waypoint;
            }
        }
        return null;
    }

    public Waypoint add(String name, int x, int y, int z, ResourceLocation dimension) {
        return this.add(name, x, y, z, dimension, WaypointColors.nextDefault(this.waypoints.size()),
                WaypointCategory.VILLAGE);
    }

    public Waypoint add(String name, int x, int y, int z, ResourceLocation dimension, int color,
            WaypointCategory category) {
        Waypoint waypoint = new Waypoint(UUID.randomUUID().toString(), name, x, y, z, dimension.toString(),
                color, category, true, false);
        this.waypoints.add(waypoint);
        this.pendingUpserts.add(waypoint.id());
        this.save();
        return waypoint;
    }

    /** Replaces a stored waypoint by its id. */
    public boolean update(Waypoint waypoint) {
        for (int index = 0; index < this.waypoints.size(); index++) {
            if (this.waypoints.get(index).id().equals(waypoint.id())) {
                this.waypoints.set(index, waypoint);
                if (waypoint.sync()) {
                    this.pendingUpserts.add(waypoint.id());
                }
                this.save();
                return true;
            }
        }
        return false;
    }

    /** Moves a waypoint to the recycle bin and queues the server deletion. */
    public boolean moveToTrash(String id) {
        Waypoint waypoint = this.get(id);
        if (waypoint == null) {
            return false;
        }
        this.waypoints.remove(waypoint);
        this.trash.add(new TrashEntry(waypoint, System.currentTimeMillis()));
        this.pendingUpserts.remove(id);
        if (waypoint.sync()) {
            this.pendingDeletes.add(id);
        }
        this.save();
        return true;
    }

    /** Moves a trashed waypoint back into the list. */
    public boolean restore(String id) {
        for (int index = 0; index < this.trash.size(); index++) {
            TrashEntry entry = this.trash.get(index);
            if (!entry.waypoint().id().equals(id)) {
                continue;
            }
            this.trash.remove(index);
            this.waypoints.add(entry.waypoint());
            this.pendingDeletes.remove(id);
            if (entry.waypoint().sync()) {
                this.pendingUpserts.add(id);
            }
            this.save();
            return true;
        }
        return false;
    }

    /** Permanently removes one recycle bin entry. */
    public boolean purge(String id) {
        boolean removed = this.trash.removeIf(entry -> entry.waypoint().id().equals(id));
        if (removed) {
            this.save();
        }
        return removed;
    }

    /** Permanently empties the recycle bin. */
    public int clearTrash() {
        int removed = this.trash.size();
        if (removed > 0) {
            this.trash.clear();
            this.save();
        }
        return removed;
    }

    /**
     * Moves every waypoint of the given dimension to the recycle bin and
     * returns the amount.
     */
    public int removeDimension(ResourceLocation dimension) {
        String id = dimension.toString();
        int removed = 0;
        for (Waypoint waypoint : List.copyOf(this.waypoints)) {
            if (waypoint.dimension().equals(id) && this.moveToTrash(waypoint.id())) {
                removed++;
            }
        }
        return removed;
    }

    /** Toggles the distance label of one waypoint. */
    public boolean setShowDistance(String id, boolean showDistance) {
        Waypoint waypoint = this.get(id);
        if (waypoint == null) {
            return false;
        }
        return this.update(waypoint.withShowDistance(showDistance));
    }

    /** Enables or disables the server synchronisation of one waypoint. */
    public boolean setSync(String id, boolean sync) {
        Waypoint waypoint = this.get(id);
        if (waypoint == null) {
            return false;
        }
        if (sync) {
            this.pendingUpserts.add(id);
        } else {
            this.pendingUpserts.remove(id);
            this.pendingDeletes.remove(id);
        }
        return this.update(waypoint.withSync(sync));
    }

    /** Waypoints that still have to be uploaded to the server. */
    public List<WaypointData> pendingUpsertData() {
        List<WaypointData> result = new ArrayList<>();
        for (Waypoint waypoint : this.waypoints) {
            if (waypoint.sync() && this.pendingUpserts.contains(waypoint.id())) {
                result.add(waypoint.data());
            }
        }
        return result;
    }

    public List<String> pendingDeletes() {
        return List.copyOf(this.pendingDeletes);
    }

    /**
     * Applies the authoritative server list.
     * <p>
     * Local-only waypoints (sync disabled) and changes that are still pending
     * are kept. Everything else is replaced by the server copy, so deletions
     * made by other clients show up here as well.
     */
    public void applyServerList(List<WaypointData> serverWaypoints) {
        List<Waypoint> merged = new ArrayList<>();
        Set<String> serverIds = new LinkedHashSet<>();
        for (WaypointData data : serverWaypoints) {
            if (data == null || data.id() == null || data.id().isBlank()) {
                continue;
            }
            serverIds.add(data.id());
        }
        // Ids that are already represented by a local copy, so the server copy
        // is not appended a second time.
        Set<String> keptIds = new LinkedHashSet<>();
        for (Waypoint local : this.waypoints) {
            if (!local.sync()) {
                merged.add(local);
                keptIds.add(local.id());
                continue;
            }
            if (!serverIds.contains(local.id()) && this.pendingUpserts.contains(local.id())) {
                // Locally created / changed after the upload started: keep it
                // and upload it in the next round.
                merged.add(local);
                keptIds.add(local.id());
            }
        }
        for (WaypointData data : serverWaypoints) {
            if (data == null || data.id() == null || data.id().isBlank()
                    || keptIds.contains(data.id()) || this.pendingDeletes.contains(data.id())) {
                continue;
            }
            Waypoint local = this.get(data.id());
            boolean showDistance = local != null && local.showDistance();
            merged.add(Waypoint.fromData(data, showDistance));
            this.pendingUpserts.remove(data.id());
        }
        this.waypoints.clear();
        this.waypoints.addAll(merged);
        // A delete only counts as finished once the server stopped listing the
        // waypoint; until then the local vote is kept and the server copy hidden.
        Set<String> stillPending = new LinkedHashSet<>();
        for (String id : this.pendingDeletes) {
            if (serverIds.contains(id)) {
                stillPending.add(id);
            }
        }
        this.pendingDeletes.clear();
        this.pendingDeletes.addAll(stillPending);
        Set<String> presentIds = new LinkedHashSet<>();
        for (Waypoint waypoint : this.waypoints) {
            presentIds.add(waypoint.id());
        }
        this.pendingUpserts.retainAll(presentIds);
        this.save();
    }

    /** Marks every synced waypoint for the next upload (force sync). */
    public void markAllForSync() {
        for (Waypoint waypoint : this.waypoints) {
            if (waypoint.sync()) {
                this.pendingUpserts.add(waypoint.id());
            }
        }
    }

    public long trashAutoClearSeconds() {
        return Math.max(1, ClientConfig.WAYPOINT_TRASH_AUTO_CLEAR_SECONDS.getAsInt());
    }

    /** Seconds until the entry is purged, 0 when the timer already elapsed. */
    public long trashRemainingSeconds(TrashEntry entry) {
        long limit = this.trashAutoClearSeconds() * 1000L;
        long remaining = limit - (System.currentTimeMillis() - entry.deletedAt());
        return Math.max(0L, (remaining + 999L) / 1000L);
    }

    /**
     * Runs the recycle bin auto purge timer. Nothing is done while the bin is
     * empty, which automatically disables the feature until a waypoint is
     * deleted again.
     */
    public int tickTrashAutoClear() {
        if (this.trash.isEmpty()) {
            return 0;
        }
        long limit = this.trashAutoClearSeconds() * 1000L;
        long now = System.currentTimeMillis();
        int before = this.trash.size();
        this.trash.removeIf(entry -> now - entry.deletedAt() >= limit);
        int removed = before - this.trash.size();
        if (removed > 0) {
            this.save();
        }
        return removed;
    }

    private void load() {
        if (this.file == null || !Files.isRegularFile(this.file)) {
            return;
        }
        try {
            String json = Files.readString(this.file, StandardCharsets.UTF_8);
            StoredFile decoded = GSON.fromJson(json, StoredFile.class);
            if (decoded == null) {
                return;
            }
            if (decoded.waypoints != null) {
                for (StoredWaypoint stored : decoded.waypoints) {
                    Waypoint waypoint = stored != null ? stored.toWaypoint() : null;
                    if (waypoint != null) {
                        this.waypoints.add(waypoint);
                        // Files written before the sync feature existed have no
                        // id, so they are treated as new and uploaded once.
                        if (stored.id == null || stored.id.isBlank()) {
                            this.pendingUpserts.add(waypoint.id());
                        }
                    }
                }
            }
            if (decoded.trash != null) {
                for (StoredTrash stored : decoded.trash) {
                    Waypoint waypoint = stored != null && stored.waypoint != null
                            ? stored.waypoint.toWaypoint()
                            : null;
                    if (waypoint != null) {
                        this.trash.add(new TrashEntry(waypoint, stored.deletedAt));
                    }
                }
            }
            if (decoded.pendingUpserts != null) {
                this.pendingUpserts.addAll(decoded.pendingUpserts);
            }
            if (decoded.pendingDeletes != null) {
                this.pendingDeletes.addAll(decoded.pendingDeletes);
            }
        } catch (Exception exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to read waypoints from {}", this.file, exception);
        }
    }

    private void save() {
        if (this.file == null) {
            return;
        }
        StoredFile data = new StoredFile();
        for (Waypoint waypoint : this.waypoints) {
            data.waypoints.add(StoredWaypoint.of(waypoint));
        }
        for (TrashEntry entry : this.trash) {
            StoredTrash stored = new StoredTrash();
            stored.waypoint = StoredWaypoint.of(entry.waypoint());
            stored.deletedAt = entry.deletedAt();
            data.trash.add(stored);
        }
        data.pendingUpserts.addAll(this.pendingUpserts);
        data.pendingDeletes.addAll(this.pendingDeletes);
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(data), StandardCharsets.UTF_8);
            this.version++;
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to write waypoints to {}", this.file, exception);
        }
    }

    private static Path resolveFile(String world) {
        String safe = world.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        int hash = world.hashCode();
        return FMLPaths.CONFIGDIR.get().resolve("m4cne").resolve("waypoints")
                .resolve(safe + "_" + Integer.toHexString(hash) + ".json");
    }

    private static final class StoredFile {
        private List<StoredWaypoint> waypoints = new ArrayList<>();
        private List<StoredTrash> trash = new ArrayList<>();
        private List<String> pendingUpserts = new ArrayList<>();
        private List<String> pendingDeletes = new ArrayList<>();
    }

    private static final class StoredTrash {
        private StoredWaypoint waypoint;
        private long deletedAt;
    }

    /**
     * JSON shape of one waypoint. The {@link Boolean} boxes keep files written
     * by older versions readable: missing flags fall back to sync on / distance
     * off.
     */
    private static final class StoredWaypoint {
        private String id;
        private String name;
        private int x;
        private int y;
        private int z;
        private String dimension;
        private int color;
        private String category;
        private Boolean sync;
        private Boolean showDistance;

        private static StoredWaypoint of(Waypoint waypoint) {
            StoredWaypoint stored = new StoredWaypoint();
            stored.id = waypoint.id();
            stored.name = waypoint.name();
            stored.x = waypoint.x();
            stored.y = waypoint.y();
            stored.z = waypoint.z();
            stored.dimension = waypoint.dimension();
            stored.color = waypoint.color();
            stored.category = waypoint.category().name();
            stored.sync = waypoint.sync();
            stored.showDistance = waypoint.showDistance();
            return stored;
        }

        private Waypoint toWaypoint() {
            if (this.name == null || this.dimension == null) {
                return null;
            }
            return new Waypoint(this.id, this.name, this.x, this.y, this.z, this.dimension, this.color,
                    WaypointCategory.byName(this.category), this.sync == null || this.sync,
                    this.showDistance != null && this.showDistance);
        }
    }
}
