package com.wwwwz.aigcs.client.preload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wwwwz.aigcs.AIGCsM4CnE;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Bright centre markers placed on the world map.
 * <p>
 * A marker is only a client side helper for the "mark the centre on the map"
 * preload mode, so it is stored in the client config directory and isolated per
 * world (and per dimension) just like the waypoints.
 */
@SuppressWarnings("null")
public final class PreloadMarkerStore {
    public record Marker(long id, String dimension, int x, int z) {
        public ResourceLocation dimensionId() {
            ResourceLocation id = ResourceLocation.tryParse(this.dimension);
            return id != null ? id : ResourceLocation.withDefaultNamespace("overworld");
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final PreloadMarkerStore INSTANCE = new PreloadMarkerStore();

    private final List<Marker> markers = new ArrayList<>();
    private final Map<String, Long> active = new LinkedHashMap<>();
    private String worldKey = "";
    private Path file;
    private long nextId = 1L;

    private PreloadMarkerStore() {
    }

    public static PreloadMarkerStore get() {
        return INSTANCE;
    }

    public void ensureWorld(String world) {
        if (world.equals(this.worldKey)) {
            return;
        }
        this.worldKey = world;
        this.markers.clear();
        this.active.clear();
        this.nextId = 1L;
        this.file = resolveFile(world);
        this.load();
    }

    public List<Marker> forDimension(ResourceLocation dimension) {
        String id = dimension.toString();
        List<Marker> result = new ArrayList<>();
        for (Marker marker : this.markers) {
            if (marker.dimension().equals(id)) {
                result.add(marker);
            }
        }
        return result;
    }

    /** Adds a marker and makes it the active preload centre. */
    public Marker add(ResourceLocation dimension, int x, int z) {
        Marker marker = new Marker(this.nextId++, dimension.toString(), x, z);
        this.markers.add(marker);
        this.active.put(marker.dimension(), marker.id());
        this.save();
        return marker;
    }

    public boolean remove(Marker marker) {
        boolean removed = this.markers.removeIf(candidate -> candidate.id() == marker.id());
        if (removed) {
            Long activeId = this.active.get(marker.dimension());
            if (activeId != null && activeId == marker.id()) {
                this.active.remove(marker.dimension());
                // Fall back to the newest remaining marker of that dimension.
                for (int index = this.markers.size() - 1; index >= 0; index--) {
                    Marker candidate = this.markers.get(index);
                    if (candidate.dimension().equals(marker.dimension())) {
                        this.active.put(candidate.dimension(), candidate.id());
                        break;
                    }
                }
            }
            this.save();
        }
        return removed;
    }

    public void setActive(Marker marker) {
        this.active.put(marker.dimension(), marker.id());
        this.save();
    }

    /** @return the active marker of the dimension, {@code null} when none exists */
    public Marker active(ResourceLocation dimension) {
        Long id = this.active.get(dimension.toString());
        if (id != null) {
            for (Marker marker : this.markers) {
                if (marker.id() == id) {
                    return marker;
                }
            }
        }
        List<Marker> candidates = this.forDimension(dimension);
        return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
    }

    /** Nearest marker to a block position, used by the right click menu. */
    public Marker nearest(ResourceLocation dimension, double blockX, double blockZ, double maxDistance) {
        Marker best = null;
        double bestDistance = maxDistance * maxDistance;
        for (Marker marker : this.forDimension(dimension)) {
            double deltaX = marker.x() + 0.5D - blockX;
            double deltaZ = marker.z() + 0.5D - blockZ;
            double distance = deltaX * deltaX + deltaZ * deltaZ;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        return best;
    }

    public int count() {
        return this.markers.size();
    }

    /** Removes every marker of a dimension and returns how many were dropped. */
    public int clearDimension(ResourceLocation dimension) {
        String id = dimension.toString();
        int before = this.markers.size();
        this.markers.removeIf(marker -> marker.dimension().equals(id));
        int removed = before - this.markers.size();
        if (removed > 0) {
            this.active.remove(id);
            this.save();
        }
        return removed;
    }

    // ------------------------------------------------------------- persistence

    private void load() {
        if (this.file == null || !Files.isRegularFile(this.file)) {
            return;
        }
        try {
            String json = Files.readString(this.file, StandardCharsets.UTF_8);
            MarkerFile decoded = GSON.fromJson(json, MarkerFile.class);
            if (decoded == null) {
                return;
            }
            if (decoded.markers != null) {
                for (MarkerData data : decoded.markers) {
                    if (data == null || data.dimension == null) {
                        continue;
                    }
                    this.markers.add(new Marker(data.id, data.dimension, data.x, data.z));
                    this.nextId = Math.max(this.nextId, data.id + 1L);
                }
            }
            if (decoded.active != null) {
                for (Map.Entry<String, Long> entry : decoded.active.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        this.active.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to read preload markers from {}", this.file, exception);
        }
    }

    private void save() {
        if (this.file == null) {
            return;
        }
        MarkerFile data = new MarkerFile();
        for (Marker marker : this.markers) {
            MarkerData entry = new MarkerData();
            entry.id = marker.id();
            entry.dimension = marker.dimension();
            entry.x = marker.x();
            entry.z = marker.z();
            data.markers.add(entry);
        }
        data.active.putAll(this.active);
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to write preload markers to {}", this.file, exception);
        }
    }

    private static Path resolveFile(String world) {
        String safe = world.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        int hash = world.hashCode();
        return FMLPaths.CONFIGDIR.get().resolve("m4cne").resolve("preload_markers")
                .resolve(safe + "_" + Integer.toHexString(hash) + ".json");
    }

    private static final class MarkerFile {
        private List<MarkerData> markers = new ArrayList<>();
        private Map<String, Long> active = new HashMap<>();
    }

    private static final class MarkerData {
        private long id;
        private String dimension;
        private int x;
        private int z;
    }
}
