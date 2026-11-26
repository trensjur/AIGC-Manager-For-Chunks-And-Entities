package com.wwwwz.aigcs.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * Per-save chunk repository.
 * <p>
 * All data lives below {@code <world>/m4cne/<namespace>/<path>}, so two save
 * games can never overwrite each other's chunks. Regions are loaded lazily and
 * written back on a single background thread; the server thread only ever
 * serialises regions that were actually modified.
 */
@SuppressWarnings("null")
public final class ChunkRepository implements AutoCloseable {
    private static final String REGION_GLOB = "r.*.m4cr";
    private static final String REGION_SUFFIX = ".m4cr";

    private final Path root;
    private final Map<ResourceLocation, DimensionData> dimensions = new HashMap<>();
    private final ExecutorService ioExecutor;

    private int upsertCounter;

    public ChunkRepository(Path root) {
        this.root = root;
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m4cne-region-io");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    public Path root() {
        return this.root;
    }

    /**
     * Merges one client update. A {@code null} tile or census leaves the stored
     * part untouched.
     */
    public void upsert(ResourceLocation dimension, int chunkX, int chunkZ, ChunkTile tile, EntityCensus census) {
        DimensionData data = this.dimensions.computeIfAbsent(dimension, this::createDimension);
        int regionX = chunkX >> RegionStore.SHIFT;
        int regionZ = chunkZ >> RegionStore.SHIFT;
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        RegionStore region = data.regions.get(regionKey);
        if (region == null) {
            region = RegionStore.load(data.dir, regionX, regionZ);
            data.regions.put(regionKey, region);
        }

        long now = System.currentTimeMillis();
        synchronized (region) {
            StoredChunk chunk = region.getOrCreate(chunkX, chunkZ);
            if (tile != null) {
                chunk.tile = tile;
            }
            if (census != null) {
                chunk.entities = census;
            }
            chunk.updatedAt = now;
            chunk.revision++;
            region.dirty = true;
            region.lastAccess = now;
        }

        if (++this.upsertCounter % 256 == 0) {
            this.evictIfNeeded();
        }
    }

    /** Returns the stored chunk or {@code null} when nothing was recorded yet. */
    public StoredChunk getChunk(ResourceLocation dimension, int chunkX, int chunkZ) {
        DimensionData data = this.dimensions.computeIfAbsent(dimension, this::createDimension);
        int regionX = chunkX >> RegionStore.SHIFT;
        int regionZ = chunkZ >> RegionStore.SHIFT;
        RegionStore region = data.regions.get(ChunkPos.asLong(regionX, regionZ));
        if (region == null) {
            region = RegionStore.load(data.dir, regionX, regionZ);
            data.regions.put(region.regionKey, region);
        }
        long now = System.currentTimeMillis();
        region.lastAccess = now;
        return region.get(chunkX, chunkZ);
    }

    /**
     * Serialises every dirty region on the calling thread and queues the file
     * writes on the background IO thread.
     *
     * @return number of regions that were queued for writing
     */
    public int saveDirtyAsync() {
        int count = 0;
        for (DimensionData data : this.dimensions.values()) {
            for (RegionStore region : data.regions.values()) {
                byte[] payload = null;
                synchronized (region) {
                    if (!region.dirty) {
                        continue;
                    }
                    try {
                        payload = region.serialize();
                        region.dirty = false;
                    } catch (IOException exception) {
                        AIGCsM4CnE.LOGGER.error("Failed to serialise chunk region {}", region.file, exception);
                    }
                }
                if (payload != null) {
                    this.submitWrite(region, payload);
                    count++;
                }
            }
        }
        return count;
    }

    private void submitWrite(RegionStore region, byte[] payload) {
        this.ioExecutor.execute(() -> {
            try {
                Path parent = region.file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path temporary = region.file.resolveSibling(region.file.getFileName() + ".tmp");
                Files.write(temporary, payload);
                try {
                    Files.move(temporary, region.file,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, region.file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                AIGCsM4CnE.LOGGER.error("Failed to write chunk region {}", region.file, exception);
                region.dirty = true;
            }
        });
    }

    public List<DimensionStats> stats() {
        Set<ResourceLocation> ids = new HashSet<>(this.dimensions.keySet());
        ids.addAll(this.scanDimensionIds());
        List<ResourceLocation> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparing(ResourceLocation::toString));

        List<DimensionStats> result = new ArrayList<>(sorted.size());
        for (ResourceLocation id : sorted) {
            result.add(this.statsFor(id));
        }
        return result;
    }

    private DimensionStats statsFor(ResourceLocation id) {
        DimensionData data = this.dimensions.get(id);
        Path directory = data != null ? data.dir : this.dimensionDir(id);

        int chunks = 0;
        long entityTotal = 0;
        Map<ResourceLocation, Integer> entityCounts = new HashMap<>();
        Set<Long> seenRegions = new HashSet<>();
        long[] totals = new long[2];

        if (data != null) {
            for (RegionStore region : data.regions.values()) {
                seenRegions.add(region.regionKey);
                totals[0] = 0L;
                totals[1] = 0L;
                region.aggregate(totals, entityCounts);
                chunks += (int) totals[0];
                entityTotal += totals[1];
            }
        }

        for (Path file : listRegionFiles(directory)) {
            long regionKey = parseRegionKey(file.getFileName().toString());
            if (regionKey == Long.MIN_VALUE || !seenRegions.add(regionKey)) {
                continue;
            }
            RegionStore region = RegionStore.load(directory, ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
            totals[0] = 0L;
            totals[1] = 0L;
            region.aggregate(totals, entityCounts);
            chunks += (int) totals[0];
            entityTotal += totals[1];
        }

        return new DimensionStats(id, chunks, entityTotal, entityCounts);
    }

    private Set<ResourceLocation> scanDimensionIds() {
        Set<ResourceLocation> ids = new HashSet<>();
        if (!Files.isDirectory(this.root)) {
            return ids;
        }
        try (Stream<Path> stream = Files.walk(this.root, 8)) {
            stream.filter(Files::isDirectory)
                    .filter(directory -> !directory.equals(this.root))
                    .filter(ChunkRepository::containsRegionFile)
                    .forEach(directory -> {
                        String relative = this.root.relativize(directory).toString().replace('\\', '/');
                        int separator = relative.indexOf('/');
                        if (separator <= 0 || separator == relative.length() - 1) {
                            return;
                        }
                        ResourceLocation id = ResourceLocation.tryParse(
                                relative.substring(0, separator) + ":" + relative.substring(separator + 1));
                        if (id != null) {
                            ids.add(id);
                        }
                    });
        } catch (IOException | RuntimeException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to scan chunk repository {}", this.root, exception);
        }
        return ids;
    }

    private static boolean containsRegionFile(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, REGION_GLOB)) {
            return stream.iterator().hasNext();
        } catch (IOException exception) {
            return false;
        }
    }

    private static List<Path> listRegionFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, REGION_GLOB)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to list chunk regions in {}", directory, exception);
        }
        return files;
    }

    /**
     * Parses {@code r.<regionX>.<regionZ>.m4cr}; returns {@link Long#MIN_VALUE} on
     * failure.
     */
    private static long parseRegionKey(String fileName) {
        if (!fileName.startsWith("r.") || !fileName.endsWith(REGION_SUFFIX)) {
            return Long.MIN_VALUE;
        }
        String core = fileName.substring(2, fileName.length() - REGION_SUFFIX.length());
        int separator = core.indexOf('.');
        if (separator <= 0 || separator >= core.length() - 1) {
            return Long.MIN_VALUE;
        }
        try {
            int regionX = Integer.parseInt(core.substring(0, separator));
            int regionZ = Integer.parseInt(core.substring(separator + 1));
            return ChunkPos.asLong(regionX, regionZ);
        } catch (NumberFormatException exception) {
            return Long.MIN_VALUE;
        }
    }

    private void evictIfNeeded() {
        int maximum;
        try {
            maximum = Config.MAX_LOADED_CHUNKS.get();
        } catch (IllegalStateException exception) {
            return;
        }

        int total = 0;
        List<RegionStore> candidates = new ArrayList<>();
        for (DimensionData data : this.dimensions.values()) {
            for (RegionStore region : data.regions.values()) {
                total += region.chunkCount();
                if (!region.dirty) {
                    candidates.add(region);
                }
            }
        }
        if (total <= maximum) {
            return;
        }

        candidates.sort(Comparator.comparingLong(region -> region.lastAccess));
        int target = Math.max(1, maximum * 3 / 4);
        for (RegionStore candidate : candidates) {
            if (total <= target) {
                break;
            }
            if (this.removeRegion(candidate)) {
                total -= candidate.chunkCount();
            }
        }
    }

    /** Removes the exact region instance, not just a colliding region key. */
    private boolean removeRegion(RegionStore candidate) {
        for (DimensionData data : this.dimensions.values()) {
            var iterator = data.regions.long2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue() == candidate) {
                    iterator.remove();
                    return true;
                }
            }
        }
        return false;
    }

    private Path dimensionDir(ResourceLocation dimension) {
        return this.root.resolve(dimension.getNamespace()).resolve(dimension.getPath());
    }

    private DimensionData createDimension(ResourceLocation dimension) {
        Path directory = this.dimensionDir(dimension);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to create chunk repository directory {}", directory, exception);
        }
        return new DimensionData(directory);
    }

    @Override
    public void close() {
        int queued = this.saveDirtyAsync();
        this.ioExecutor.shutdown();
        try {
            if (!this.ioExecutor.awaitTermination(30L, TimeUnit.SECONDS)) {
                this.ioExecutor.shutdownNow();
                AIGCsM4CnE.LOGGER.warn("Timed out while writing chunk regions");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.ioExecutor.shutdownNow();
        }
        this.dimensions.clear();
        AIGCsM4CnE.LOGGER.info("Chunk repository {} closed, {} regions flushed", this.root, queued);
    }

    private static final class DimensionData {
        final Path dir;
        final Long2ObjectOpenHashMap<RegionStore> regions = new Long2ObjectOpenHashMap<>();

        DimensionData(Path dir) {
            this.dir = dir;
        }
    }
}
