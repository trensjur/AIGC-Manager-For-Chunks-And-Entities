package com.wwwwz.aigcs.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.core.claim.ClaimChunk;
import com.wwwwz.aigcs.core.claim.ClaimOwner;
import com.wwwwz.aigcs.core.claim.EntityRule;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;
import com.wwwwz.aigcs.network.ClaimActionPayload;
import com.wwwwz.aigcs.network.ClaimListPayload;
import com.wwwwz.aigcs.network.ClaimScanPayload;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side chunk claim repository.
 * <p>
 * Clients upload their own claims as a complete snapshot in small batches.
 * When the last batch of a round arrives the server replaces the stored claims
 * of that player, makes sure every force load flag has a vanilla forced chunk
 * ticket and pushes the merged list back to everyone.
 * <p>
 * The server also scans periodically (default every ten minutes) and on demand:
 * a scan simply asks every client to upload its claims again, so a claim that
 * was created while an upload failed is never lost.
 */
@SuppressWarnings("null")
public final class ServerClaimManager {
    /**
     * Entity control settings of one claimed chunk, used by the entity controller.
     */
    public record EntityClaim(String ownerId, boolean forceLoad, EntityRuleSet rules) {
    }

    /** One chunk with an active entity rule set, used by the cleanup pass. */
    public record EntityChunk(String dimension, int x, int z, boolean forceLoad, EntityRuleSet rules) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Interval of the dirty flag flush, in ticks. */
    private static final int SAVE_INTERVAL_TICKS = 100;
    /** How often the forced chunk set is re-applied, in ticks. */
    private static final int FORCED_REFRESH_TICKS = 200;
    /** How long an administrative deletion blocks the same chunk again. */
    private static final long BLOCKED_MILLIS = 120_000L;
    /**
     * Safety net for one upload round; the client sends at most this many chunks.
     */
    private static final int MAX_ROUND_CHUNKS = 200_000;
    /** Safety net for one broadcast; keeps the payload below the network limit. */
    private static final int MAX_TOTAL_CLAIMS = 200_000;
    private static final int MAX_COORDINATE = 1_875_000;
    private static final LongOpenHashSet EMPTY_SET = new LongOpenHashSet();

    private static volatile ServerClaimManager active;

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private final Map<UUID, Round> rounds = new HashMap<>();
    /**
     * Chunks an administrator removed recently, so a stale upload cannot revive
     * them.
     */
    private final Map<String, Map<String, Long>> blocked = new HashMap<>();
    /** Forced chunks currently applied per dimension. */
    private final Map<String, LongOpenHashSet> appliedForced = new LinkedHashMap<>();
    /** Fast (dimension, chunk) to owner lookup for the entity controller. */
    private final Map<String, Long2ObjectMap<EntityClaim>> entityIndex = new LinkedHashMap<>();
    /** Immutable snapshot of all chunks with entity control enabled. */
    private volatile List<EntityChunk> entityChunks = List.of();

    private boolean dirty;
    private int saveTimer;
    private int scanTimer;
    private int forcedTimer;

    private ServerClaimManager(MinecraftServer server) {
        this.server = server;
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("m4cne").resolve("claims.json");
    }

    public static ServerClaimManager get() {
        return active;
    }

    // ------------------------------------------------------------- lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        ServerClaimManager manager = new ServerClaimManager(event.getServer());
        manager.load();
        active = manager;
        manager.refreshForcedChunks();
        AIGCsM4CnE.LOGGER.info("Loaded chunk claims of {} players", manager.owners.size());
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        ServerClaimManager manager = active;
        if (manager != null && manager.server == event.getServer()) {
            manager.save();
            active = null;
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerClaimManager manager = active;
        if (manager != null && manager.server == event.getServer()) {
            manager.tick();
        }
    }

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerClaimManager manager = active;
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.sendList(player);
        }
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerClaimManager manager = active;
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.rounds.remove(player.getUUID());
        }
    }

    private void tick() {
        int interval = Math.max(30, Config.CLAIM_SCAN_INTERVAL_SECONDS.getAsInt()) * 20;
        if (++this.scanTimer >= interval) {
            this.scanTimer = 0;
            this.broadcastScan(false);
        }
        if (this.dirty) {
            if (++this.saveTimer >= SAVE_INTERVAL_TICKS) {
                this.save();
            }
        } else {
            this.saveTimer = 0;
        }
        if (++this.forcedTimer >= FORCED_REFRESH_TICKS) {
            this.forcedTimer = 0;
            // New dimensions may have been loaded since the last pass.
            this.refreshForcedChunks();
        }
    }

    // ----------------------------------------------------------------- upload

    /** Accumulates one upload round and applies it once the last batch arrived. */
    public synchronized void handleSync(ServerPlayer player, int roundId, List<ClaimChunk> chunks,
            boolean lastBatch) {
        Round round = this.rounds.get(player.getUUID());
        if (round == null || round.id != roundId) {
            round = new Round(roundId);
            this.rounds.put(player.getUUID(), round);
        }
        int room = MAX_ROUND_CHUNKS - round.chunks.size();
        if (room > 0 && chunks != null && !chunks.isEmpty()) {
            round.chunks.addAll(chunks.subList(0, Math.min(room, chunks.size())));
        }
        if (!lastBatch) {
            return;
        }
        this.rounds.remove(player.getUUID());
        this.applyRound(player, round);
    }

    private void applyRound(ServerPlayer player, Round round) {
        Owner owner = this.owners.computeIfAbsent(player.getUUID().toString(),
                key -> new Owner(key, player.getGameProfile().getName()));
        owner.name = player.getGameProfile().getName();

        int maxClaims = Math.max(1, Config.CLAIM_MAX_PER_PLAYER.getAsInt());
        int maxForced = Math.max(0, Config.CLAIM_MAX_FORCED_PER_PLAYER.getAsInt());
        boolean forceAllowed = Config.CLAIM_FORCE_LOAD.getAsBoolean();
        Map<String, Long2ObjectMap<ChunkData>> claims = new LinkedHashMap<>();
        int total = 0;
        int forcedCount = 0;
        long now = System.currentTimeMillis();
        for (ClaimChunk chunk : round.chunks) {
            if (total >= maxClaims) {
                break;
            }
            String dimension = dimensionName(chunk.dimension());
            if (dimension == null) {
                continue;
            }
            if (Math.abs(chunk.x()) > MAX_COORDINATE || Math.abs(chunk.z()) > MAX_COORDINATE) {
                continue;
            }
            long key = ChunkPos.asLong(chunk.x(), chunk.z());
            if (this.isBlocked(owner.id, dimension, key, now)) {
                continue;
            }
            Long2ObjectMap<ChunkData> map = claims.computeIfAbsent(dimension,
                    ignored -> new Long2ObjectOpenHashMap<>());
            if (map.containsKey(key)) {
                continue;
            }
            boolean forceLoad = chunk.forceLoad() && forceAllowed && forcedCount < maxForced;
            map.put(key, new ChunkData(forceLoad,
                    ClaimChunk.sanitizeId(chunk.id(), chunk.x(), chunk.z()), chunk.boundary(),
                    chunk.enterMessage()));
            total++;
            if (forceLoad) {
                forcedCount++;
            }
        }
        owner.claims = claims;
        this.dirty = true;
        this.rebuildEntityIndex();
        this.refreshForcedChunks();
        this.broadcast();
    }

    // ---------------------------------------------------------------- actions

    public synchronized void handleAction(ServerPlayer player, ClaimActionPayload payload) {
        if (payload == null || payload.action() == null) {
            return;
        }
        boolean admin = this.isAdmin(player);
        switch (payload.action()) {
            case SET_BOUNDARY -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                owner.boundary = payload.value();
                this.changed();
            }
            case SET_COLOR -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                owner.color = payload.number() & 0xFFFFFF;
                this.changed();
            }
            case DELETE_CLAIMS -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                boolean foreign = !owner.id.equals(player.getUUID().toString());
                this.removeClaims(owner, payload.chunks(), foreign);
                this.changed();
            }
            case CLEAR_FORCE -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                this.clearForce(owner, payload.chunks());
                this.changed();
            }
            case RENAME_CLAIMS -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                this.renameClaims(owner, payload.chunks(), payload.text());
                this.changed();
            }
            case SET_CHUNK_BOUNDARY -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                this.setChunkFlag(owner, payload.chunks(), payload.value(), true);
                this.changed();
            }
            case SET_CHUNK_MESSAGE -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                this.setChunkFlag(owner, payload.chunks(), payload.value(), false);
                this.changed();
            }
            case MERGE_CLAIMS -> {
                Owner owner = this.targetOwner(player, payload.ownerId(), admin);
                if (owner == null) {
                    return;
                }
                this.mergeClaims(owner, payload.chunks());
                this.changed();
            }
            case REQUEST_SCAN -> {
                if (!admin) {
                    return;
                }
                this.scanTimer = 0;
                this.broadcastScan(true);
            }
            case SET_INTERVAL -> {
                if (!admin) {
                    return;
                }
                int seconds = Math.max(30, Math.min(86400, payload.number()));
                Config.CLAIM_SCAN_INTERVAL_SECONDS.set(seconds);
                Config.SPEC.save();
                this.scanTimer = 0;
                this.broadcast();
            }
        }
    }

    /**
     * Applies a new entity control rule set of one owner.
     * <p>
     * The server re-checks the permission, drops unknown or non living entity
     * types and stores the sanitised set. The next authoritative list confirms
     * the change on every client.
     */
    public synchronized void handleEntityRules(ServerPlayer player, String ownerId, EntityRuleSet rules) {
        if (rules == null || !Config.CLAIM_ENTITY_CONTROL.getAsBoolean()) {
            return;
        }
        Owner owner = this.targetOwner(player, ownerId, this.isAdmin(player));
        if (owner == null) {
            return;
        }
        int maxRules = Math.max(1, Config.CLAIM_MAX_ENTITY_RULES.getAsInt());
        owner.entityRules = this.validateRules(rules.sanitize(maxRules));
        this.changed();
    }

    /** Drops rules whose entity type is unknown or not a living entity. */
    private EntityRuleSet validateRules(EntityRuleSet rules) {
        List<EntityRule> kept = new ArrayList<>(rules.rules().size());
        for (EntityRule rule : rules.rules()) {
            ResourceLocation id = ResourceLocation.tryParse(rule.type());
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                continue;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            Class<? extends net.minecraft.world.entity.Entity> baseClass = type == null ? null
                    : type.getBaseClass();
            if (type == null || type == EntityType.PLAYER || baseClass == null
                    || !Mob.class.isAssignableFrom(baseClass)) {
                continue;
            }
            kept.add(rule);
        }
        return new EntityRuleSet(rules.enabled(), rules.allowSpawn(), rules.allowAggro(),
                rules.allowEnter(), rules.allowLeave(), rules.forceLoadAllowSpawn(), kept);
    }

    /** Removes either the given chunks or every claim of the owner. */
    private void removeClaims(Owner owner, List<ClaimChunk> chunks, boolean block) {
        if (chunks == null || chunks.isEmpty()) {
            owner.claims.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (ClaimChunk chunk : chunks) {
            String dimension = dimensionName(chunk.dimension());
            if (dimension == null) {
                continue;
            }
            long key = ChunkPos.asLong(chunk.x(), chunk.z());
            Long2ObjectMap<ChunkData> map = owner.claims.get(dimension);
            if (map != null) {
                map.remove(key);
                if (map.isEmpty()) {
                    owner.claims.remove(dimension);
                }
            }
            if (block) {
                this.blocked.computeIfAbsent(owner.id, ignored -> new HashMap<>())
                        .put(dimension + "#" + key, now + BLOCKED_MILLIS);
            }
        }
    }

    private void clearForce(Owner owner, List<ClaimChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            for (Long2ObjectMap<ChunkData> map : owner.claims.values()) {
                for (ChunkData data : map.values()) {
                    data.forceLoad = false;
                }
            }
            return;
        }
        for (ChunkData data : this.chunkData(owner, chunks)) {
            data.forceLoad = false;
        }
    }

    /** Applies a new id to the listed existing chunks of one owner. */
    private void renameClaims(Owner owner, List<ClaimChunk> chunks, String id) {
        String newId = id == null ? "" : id.trim();
        if (chunks == null || chunks.isEmpty() || newId.isEmpty()) {
            return;
        }
        if (newId.length() > ClaimChunk.MAX_ID_LENGTH) {
            newId = newId.substring(0, ClaimChunk.MAX_ID_LENGTH);
        }
        for (ChunkData data : this.chunkData(owner, chunks)) {
            data.id = newId;
        }
    }

    /** Toggles the boundary wall or the enter message flag of some chunks. */
    private void setChunkFlag(Owner owner, List<ClaimChunk> chunks, boolean value, boolean boundary) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (ChunkData data : this.chunkData(owner, chunks)) {
            if (boundary) {
                data.boundary = value;
            } else {
                data.enterMessage = value;
            }
        }
    }

    /**
     * Merges the listed chunks into one id: the lexicographically smallest id of
     * the group wins. The chunks do not have to touch each other.
     */
    private void mergeClaims(Owner owner, List<ClaimChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<ChunkData> data = this.chunkData(owner, chunks);
        String merged = null;
        for (ChunkData entry : data) {
            String id = entry.id == null || entry.id.isBlank() ? null : entry.id;
            if (id == null) {
                continue;
            }
            if (merged == null || id.compareTo(merged) < 0) {
                merged = id;
            }
        }
        if (merged == null) {
            return;
        }
        for (ChunkData entry : data) {
            entry.id = merged;
        }
    }

    /** Existing chunk data records of the listed chunks. */
    private List<ChunkData> chunkData(Owner owner, List<ClaimChunk> chunks) {
        List<ChunkData> data = new ArrayList<>(chunks.size());
        for (ClaimChunk chunk : chunks) {
            String dimension = dimensionName(chunk.dimension());
            if (dimension == null) {
                continue;
            }
            Long2ObjectMap<ChunkData> map = owner.claims.get(dimension);
            ChunkData entry = map == null ? null : map.get(ChunkPos.asLong(chunk.x(), chunk.z()));
            if (entry != null) {
                data.add(entry);
            }
        }
        return data;
    }

    private void changed() {
        this.dirty = true;
        this.rebuildEntityIndex();
        this.refreshForcedChunks();
        this.broadcast();
    }

    private Owner targetOwner(ServerPlayer player, String ownerId, boolean admin) {
        String selfId = player.getUUID().toString();
        String id = ownerId == null || ownerId.isBlank() ? selfId : ownerId;
        if (!id.equals(selfId) && !admin) {
            return null;
        }
        Owner owner = this.owners.get(id);
        if (owner == null && id.equals(selfId)) {
            owner = new Owner(selfId, player.getGameProfile().getName());
            this.owners.put(selfId, owner);
        }
        return owner;
    }

    private boolean isBlocked(String ownerId, String dimension, long key, long now) {
        Map<String, Long> entries = this.blocked.get(ownerId);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        String id = dimension + "#" + key;
        Long until = entries.get(id);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            entries.remove(id);
            return false;
        }
        return true;
    }

    // ----------------------------------------------------------------- access

    public synchronized int claimCount() {
        int total = 0;
        for (Owner owner : this.owners.values()) {
            total += owner.count();
        }
        return total;
    }

    public synchronized int forcedCount() {
        int total = 0;
        for (Owner owner : this.owners.values()) {
            total += owner.forcedCount();
        }
        return total;
    }

    /** The running server, used by the entity controller for level lookups. */
    public MinecraftServer server() {
        return this.server;
    }

    /**
     * Entity control settings of one chunk, or {@code null} when the chunk is
     * unclaimed or the owner did not enable entity control.
     */
    public synchronized EntityClaim entityClaimAt(String dimension, int chunkX, int chunkZ) {
        Long2ObjectMap<EntityClaim> map = this.entityIndex.get(dimension);
        return map == null ? null : map.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Immutable snapshot of every chunk with an active entity rule set. */
    public List<EntityChunk> entityChunks() {
        return this.entityChunks;
    }

    public synchronized List<ClaimOwner> snapshot() {
        List<ClaimOwner> list = new ArrayList<>();
        int total = 0;
        for (Owner owner : this.owners.values()) {
            List<ClaimChunk> chunks = new ArrayList<>();
            for (Map.Entry<String, Long2ObjectMap<ChunkData>> entry : owner.claims.entrySet()) {
                for (Long2ObjectMap.Entry<ChunkData> chunk : entry.getValue().long2ObjectEntrySet()) {
                    if (total >= MAX_TOTAL_CLAIMS) {
                        break;
                    }
                    long key = chunk.getLongKey();
                    ChunkData data = chunk.getValue();
                    chunks.add(new ClaimChunk(entry.getKey(), ChunkPos.getX(key), ChunkPos.getZ(key),
                            data.forceLoad, data.id, data.boundary, data.enterMessage));
                    total++;
                }
            }
            list.add(new ClaimOwner(owner.id, owner.name, owner.color, owner.boundary, chunks,
                    owner.entityRules));
        }
        return list;
    }

    public synchronized boolean isAdmin(ServerPlayer player) {
        if (this.server.isSingleplayerOwner(player.getGameProfile())) {
            return true;
        }
        return player.hasPermissions(Math.max(0, Config.CLAIM_ADMIN_PERMISSION_LEVEL.getAsInt()));
    }

    /** Deletes every claim of one owner by name, used by the management command. */
    public synchronized int clearOwner(String name) {
        for (Owner owner : this.owners.values()) {
            if (owner.name.equalsIgnoreCase(name)) {
                int removed = owner.count();
                owner.claims.clear();
                this.changed();
                return removed;
            }
        }
        return 0;
    }

    /** Triggers a manual scan; returns the number of notified clients. */
    public synchronized int requestScan() {
        this.scanTimer = 0;
        return this.broadcastScan(true);
    }

    private void sendList(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, this.listPayload(player));
    }

    private void broadcast() {
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, this.listPayload(player));
        }
    }

    private int broadcastScan(boolean manual) {
        ClaimScanPayload payload = new ClaimScanPayload(manual);
        int sent = 0;
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
            sent++;
        }
        return sent;
    }

    private ClaimListPayload listPayload(ServerPlayer player) {
        int maxForced = Config.CLAIM_FORCE_LOAD.getAsBoolean()
                ? Math.max(0, Config.CLAIM_MAX_FORCED_PER_PLAYER.getAsInt())
                : 0;
        return new ClaimListPayload(this.snapshot(), this.isAdmin(player),
                Math.max(30, Config.CLAIM_SCAN_INTERVAL_SECONDS.getAsInt()),
                Math.max(1, Config.CLAIM_MAX_PER_PLAYER.getAsInt()), maxForced,
                Config.CLAIM_ENTITY_CONTROL.getAsBoolean());
    }

    // ---------------------------------------------------------- forced chunks

    /**
     * Rebuilds the chunk to owner index used by the entity controller. Chunks of
     * owners that did not enable entity control are left out, so the controller
     * only pays for claims that actually use the feature.
     */
    private void rebuildEntityIndex() {
        this.entityIndex.clear();
        List<EntityChunk> chunks = new ArrayList<>();
        for (Owner owner : this.owners.values()) {
            if (owner.entityRules == null || owner.entityRules.inert()) {
                continue;
            }
            for (Map.Entry<String, Long2ObjectMap<ChunkData>> entry : owner.claims.entrySet()) {
                Long2ObjectMap<EntityClaim> map = this.entityIndex.computeIfAbsent(entry.getKey(),
                        ignored -> new Long2ObjectOpenHashMap<>());
                for (Long2ObjectMap.Entry<ChunkData> chunk : entry.getValue().long2ObjectEntrySet()) {
                    ChunkData data = chunk.getValue();
                    long key = chunk.getLongKey();
                    map.put(key, new EntityClaim(owner.id, data.forceLoad, owner.entityRules));
                    chunks.add(new EntityChunk(entry.getKey(), ChunkPos.getX(key), ChunkPos.getZ(key),
                            data.forceLoad, owner.entityRules));
                }
            }
        }
        this.entityChunks = List.copyOf(chunks);
    }

    /** Applies / releases the vanilla forced chunk tickets of every claim. */
    private void refreshForcedChunks() {
        Map<String, LongOpenHashSet> desired = new LinkedHashMap<>();
        for (Owner owner : this.owners.values()) {
            for (Map.Entry<String, Long2ObjectMap<ChunkData>> entry : owner.claims.entrySet()) {
                for (Long2ObjectMap.Entry<ChunkData> chunk : entry.getValue().long2ObjectEntrySet()) {
                    if (!chunk.getValue().forceLoad) {
                        continue;
                    }
                    desired.computeIfAbsent(entry.getKey(), ignored -> new LongOpenHashSet())
                            .add(chunk.getLongKey());
                }
            }
        }
        Set<String> dimensions = new LinkedHashSet<>(desired.keySet());
        dimensions.addAll(this.appliedForced.keySet());
        for (String dimension : dimensions) {
            ServerLevel level = this.level(dimension);
            if (level == null) {
                // The dimension is not loaded; the next pass retries.
                continue;
            }
            LongOpenHashSet want = desired.getOrDefault(dimension, EMPTY_SET);
            LongOpenHashSet have = this.appliedForced.computeIfAbsent(dimension,
                    ignored -> new LongOpenHashSet());
            LongArrayList release = new LongArrayList();
            for (long key : have) {
                if (!want.contains(key)) {
                    release.add(key);
                }
            }
            for (long key : release) {
                have.remove(key);
                this.setChunkForced(level, key, false);
            }
            for (long key : want) {
                if (have.add(key)) {
                    this.setChunkForced(level, key, true);
                }
            }
        }
    }

    private void setChunkForced(ServerLevel level, long key, boolean forced) {
        try {
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), forced);
        } catch (RuntimeException exception) {
            AIGCsM4CnE.LOGGER.debug("Failed to set the forced chunk state of {}", key, exception);
        }
    }

    /** Resolves a dimension name to the loaded server level, or {@code null}. */
    public ServerLevel level(String dimension) {
        ResourceLocation location = ResourceLocation.tryParse(dimension == null ? "" : dimension);
        if (location == null) {
            return null;
        }
        return this.server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static String dimensionName(String dimension) {
        ResourceLocation location = ResourceLocation.tryParse(dimension == null ? "" : dimension);
        return location == null ? null : location.toString();
    }

    // -------------------------------------------------------------- storage

    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            StoredFile stored = GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8),
                    StoredFile.class);
            if (stored == null || stored.owners == null) {
                return;
            }
            for (StoredOwner entry : stored.owners) {
                if (entry == null || entry.id == null || entry.id.isBlank()) {
                    continue;
                }
                Owner owner = new Owner(entry.id, entry.name == null ? "?" : entry.name);
                owner.color = entry.color & 0xFFFFFF;
                owner.boundary = entry.boundary;
                owner.entityRules = entry.entityRules == null ? EntityRuleSet.DEFAULT : entry.entityRules;
                if (entry.chunks != null) {
                    for (StoredChunk chunk : entry.chunks) {
                        if (chunk == null || chunk.dimension == null) {
                            continue;
                        }
                        long key = ChunkPos.asLong(chunk.x, chunk.z);
                        ChunkData data = new ChunkData(chunk.forceLoad,
                                ClaimChunk.sanitizeId(chunk.id, chunk.x, chunk.z), chunk.boundary,
                                chunk.enterMessage);
                        owner.claims.computeIfAbsent(chunk.dimension,
                                ignored -> new Long2ObjectOpenHashMap<>()).put(key, data);
                    }
                }
                this.owners.put(owner.id, owner);
            }
        } catch (Exception exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to read chunk claims from {}", this.file, exception);
        }
        this.rebuildEntityIndex();
    }

    private void save() {
        this.dirty = false;
        this.saveTimer = 0;
        StoredFile stored = new StoredFile();
        for (Owner owner : this.owners.values()) {
            StoredOwner entry = new StoredOwner();
            entry.id = owner.id;
            entry.name = owner.name;
            entry.color = owner.color;
            entry.boundary = owner.boundary;
            entry.entityRules = owner.entityRules;
            for (Map.Entry<String, Long2ObjectMap<ChunkData>> chunkEntry : owner.claims.entrySet()) {
                for (Long2ObjectMap.Entry<ChunkData> mapEntry : chunkEntry.getValue().long2ObjectEntrySet()) {
                    long key = mapEntry.getLongKey();
                    ChunkData data = mapEntry.getValue();
                    StoredChunk chunk = new StoredChunk();
                    chunk.dimension = chunkEntry.getKey();
                    chunk.x = ChunkPos.getX(key);
                    chunk.z = ChunkPos.getZ(key);
                    chunk.forceLoad = data.forceLoad;
                    chunk.id = data.id;
                    chunk.boundary = data.boundary;
                    chunk.enterMessage = data.enterMessage;
                    entry.chunks.add(chunk);
                }
            }
            stored.owners.add(entry);
        }
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(stored), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to write chunk claims to {}", this.file, exception);
        }
    }

    // ---------------------------------------------------------------- records

    private static final class Owner {
        private final String id;
        private String name;
        private int color = 0xFFFFFF;
        private boolean boundary = true;
        private EntityRuleSet entityRules = EntityRuleSet.DEFAULT;
        private Map<String, Long2ObjectMap<ChunkData>> claims = new LinkedHashMap<>();

        private Owner(String id, String name) {
            this.id = id;
            this.name = name;
        }

        private int count() {
            int total = 0;
            for (Long2ObjectMap<ChunkData> map : this.claims.values()) {
                total += map.size();
            }
            return total;
        }

        private int forcedCount() {
            int total = 0;
            for (Long2ObjectMap<ChunkData> map : this.claims.values()) {
                for (ChunkData data : map.values()) {
                    if (data.forceLoad) {
                        total++;
                    }
                }
            }
            return total;
        }
    }

    /**
     * Stored data of one claimed chunk: the force load flag and the player
     * editable id / boundary wall / enter message switches.
     */
    private static final class ChunkData {
        private boolean forceLoad;
        private String id;
        private boolean boundary;
        private boolean enterMessage;

        private ChunkData(boolean forceLoad, String id, boolean boundary, boolean enterMessage) {
            this.forceLoad = forceLoad;
            this.id = id;
            this.boundary = boundary;
            this.enterMessage = enterMessage;
        }
    }

    private static final class Round {
        private final int id;
        private final List<ClaimChunk> chunks = new ArrayList<>();

        private Round(int id) {
            this.id = id;
        }
    }

    private static final class StoredFile {
        private List<StoredOwner> owners = new ArrayList<>();
    }

    private static final class StoredOwner {
        private String id;
        private String name;
        private int color = 0xFFFFFF;
        private EntityRuleSet entityRules = EntityRuleSet.DEFAULT;
        private boolean boundary = true;
        private List<StoredChunk> chunks = new ArrayList<>();
    }

    private static final class StoredChunk {
        private String dimension;
        private int x;
        private int z;
        private boolean forceLoad;
        private String id;
        private boolean boundary;
        private boolean enterMessage;
    }
}
