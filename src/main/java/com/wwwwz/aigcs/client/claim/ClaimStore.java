package com.wwwwz.aigcs.client.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wwwwz.aigcs.core.claim.ClaimChunk;
import com.wwwwz.aigcs.core.claim.ClaimOwner;
import com.wwwwz.aigcs.core.claim.ClaimRuleGroup;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client mirror of the server side chunk claims.
 * <p>
 * The server list is authoritative for every other player. The own claims are
 * edited locally first: a claim created on the map is marked pending and is
 * uploaded with the next sync round, and a locally removed chunk stays removed
 * even when an older server list arrives while the upload is still running.
 */
@SuppressWarnings("null")
public final class ClaimStore {
    /** One claimed chunk inside the client mirror. */
    public static final class Claim {
        private boolean forceLoad;
        private boolean pending;
        private String id;
        private boolean boundary;
        private boolean enterMessage;

        private Claim(boolean forceLoad, String id, boolean boundary, boolean enterMessage,
                boolean pending) {
            this.forceLoad = forceLoad;
            this.id = id;
            this.boundary = boundary;
            this.enterMessage = enterMessage;
            this.pending = pending;
        }

        public boolean forceLoad() {
            return this.forceLoad;
        }

        public boolean pending() {
            return this.pending;
        }

        public String id() {
            return this.id;
        }

        public boolean boundary() {
            return this.boundary;
        }

        public boolean enterMessage() {
            return this.enterMessage;
        }
    }

    /**
     * Result of a chunk lookup, used by the overlay, the HUD and the chat notices.
     */
    public record Ref(Owner owner, boolean forceLoad, boolean pending, String id, boolean boundary,
            boolean enterMessage) {
        /** Id used for display; never blank. */
        public String displayId(int chunkX, int chunkZ) {
            return this.id == null || this.id.isBlank() ? ClaimChunk.defaultId(chunkX, chunkZ) : this.id;
        }
    }

    /**
     * Flattened chunk of one owner, used by the chunk list page. The record keeps
     * the owner reference so the list can show the player and the colour without
     * another lookup.
     */
    public record Entry(Owner owner, String dimension, int x, int z, boolean forceLoad, String id,
            boolean boundary, boolean enterMessage) {
        public String displayId() {
            return this.id == null || this.id.isBlank() ? ClaimChunk.defaultId(this.x, this.z) : this.id;
        }

        public int centerX() {
            return this.x * 16 + 8;
        }

        public int centerZ() {
            return this.z * 16 + 8;
        }

        /** Key used by the selection of the chunk list page. */
        public String key() {
            return selectionKey(this.dimension, ChunkPos.asLong(this.x, this.z));
        }
    }

    /** Key of one chunk inside a selection that may span dimensions. */
    public static String selectionKey(String dimension, long chunkKey) {
        return dimension + "#" + chunkKey;
    }

    /** One player and the chunks the player claimed. */
    public static final class Owner {
        private final String id;
        private String name;
        private int color = 0xFFFFFF;
        private boolean boundary = true;
        /** Entity rules keyed by chunk id (claim group). */
        private final Map<String, EntityRuleSet> groupRules = new LinkedHashMap<>();
        private final Map<String, Long2ObjectMap<Claim>> chunks = new LinkedHashMap<>();

        private Owner(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String id() {
            return this.id;
        }

        public String name() {
            return this.name;
        }

        public int color() {
            return this.color;
        }

        public boolean boundary() {
            return this.boundary;
        }

        /** Rules of one claim group, never {@code null}. */
        public EntityRuleSet rulesFor(String groupId) {
            if (groupId == null) {
                return EntityRuleSet.EMPTY;
            }
            EntityRuleSet rules = this.groupRules.get(groupId);
            return rules == null ? EntityRuleSet.EMPTY : rules;
        }

        /** All rule groups of this owner, keyed by chunk id. */
        public Map<String, EntityRuleSet> groupRules() {
            return new LinkedHashMap<>(this.groupRules);
        }

        /** True when the given chunk id owns at least one chunk. */
        public boolean hasGroup(String groupId) {
            if (groupId == null) {
                return false;
            }
            for (Long2ObjectMap<Claim> map : this.chunks.values()) {
                for (Long2ObjectMap.Entry<Claim> entry : map.long2ObjectEntrySet()) {
                    long key = entry.getLongKey();
                    String id = entry.getValue().id == null || entry.getValue().id.isBlank()
                            ? ClaimChunk.defaultId(ChunkPos.getX(key), ChunkPos.getZ(key))
                            : entry.getValue().id;
                    if (id.equals(groupId)) {
                        return true;
                    }
                }
            }
            return false;
        }

        public int count() {
            int total = 0;
            for (Long2ObjectMap<Claim> map : this.chunks.values()) {
                total += map.size();
            }
            return total;
        }

        public int count(String dimension) {
            Long2ObjectMap<Claim> map = this.chunks.get(dimension);
            return map == null ? 0 : map.size();
        }

        public int forceCount() {
            int total = 0;
            for (Long2ObjectMap<Claim> map : this.chunks.values()) {
                for (Claim claim : map.values()) {
                    if (claim.forceLoad) {
                        total++;
                    }
                }
            }
            return total;
        }

        public Set<String> dimensions() {
            return this.chunks.keySet();
        }

        private boolean has(String dimension, long key) {
            Long2ObjectMap<Claim> map = this.chunks.get(dimension);
            return map != null && map.containsKey(key);
        }

        private Claim get(String dimension, long key) {
            Long2ObjectMap<Claim> map = this.chunks.get(dimension);
            return map == null ? null : map.get(key);
        }

        private void put(String dimension, long key, boolean forceLoad, String id, boolean boundary,
                boolean enterMessage, boolean pending) {
            this.chunks.computeIfAbsent(dimension, ignored -> new Long2ObjectOpenHashMap<>())
                    .put(key, new Claim(forceLoad, id, boundary, enterMessage, pending));
        }

        /** Every chunk of this owner as a flat list, used by the chunk list page. */
        public List<Entry> chunkEntries() {
            List<Entry> entries = new ArrayList<>(this.count());
            for (Map.Entry<String, Long2ObjectMap<Claim>> dimension : this.chunks.entrySet()) {
                for (Long2ObjectMap.Entry<Claim> chunk : dimension.getValue().long2ObjectEntrySet()) {
                    Claim claim = chunk.getValue();
                    long key = chunk.getLongKey();
                    entries.add(new Entry(this, dimension.getKey(), ChunkPos.getX(key), ChunkPos.getZ(key),
                            claim.forceLoad, claim.id, claim.boundary, claim.enterMessage));
                }
            }
            return entries;
        }

        private void remove(String dimension, long key) {
            Long2ObjectMap<Claim> map = this.chunks.get(dimension);
            if (map == null) {
                return;
            }
            map.remove(key);
            if (map.isEmpty()) {
                this.chunks.remove(dimension);
            }
        }
    }

    private static final ClaimStore INSTANCE = new ClaimStore();

    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private final Map<String, Long2ObjectMap<Ref>> index = new LinkedHashMap<>();
    private final Set<String> hiddenOwners = new LinkedHashSet<>();
    /** Keys removed locally before the server confirmed them. */
    private final Set<String> pendingDeletes = new LinkedHashSet<>();

    private String worldKey = "";
    private long version;
    /** Bumped by local claim edits only; drives the upload rounds. */
    private long editVersion;

    private ClaimStore() {
    }

    public static ClaimStore get() {
        return INSTANCE;
    }

    /** Clears the mirror when the player leaves the world. */
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.clear();
    }

    /** Clears the mirror when the player switches to another world / server. */
    public synchronized void ensureWorld(String worldKey) {
        if (worldKey == null) {
            worldKey = "";
        }
        if (worldKey.equals(this.worldKey)) {
            return;
        }
        this.worldKey = worldKey;
        this.owners.clear();
        this.index.clear();
        this.hiddenOwners.clear();
        this.pendingDeletes.clear();
        this.version++;
    }

    public synchronized void clear() {
        this.owners.clear();
        this.index.clear();
        this.hiddenOwners.clear();
        this.pendingDeletes.clear();
        this.version++;
    }

    public long version() {
        return this.version;
    }

    /** Version of the local claim edits, used to detect unsynced changes. */
    public long editVersion() {
        return this.editVersion;
    }

    /** UUID of the local player, or an empty string when no world is loaded. */
    public String selfId() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? "" : player.getUUID().toString();
    }

    public synchronized Owner self() {
        String id = this.selfId();
        return id.isEmpty() ? null : this.owners.get(id);
    }

    public synchronized Owner owner(String id) {
        return id == null ? null : this.owners.get(id);
    }

    public synchronized Collection<Owner> owners() {
        return new ArrayList<>(this.owners.values());
    }

    /** Owners sorted by claim count; the own claims always come first. */
    public synchronized List<Owner> sortedOwners() {
        List<Owner> list = new ArrayList<>(this.owners.values());
        String selfId = this.selfId();
        list.sort((left, right) -> {
            boolean leftSelf = left.id.equals(selfId);
            boolean rightSelf = right.id.equals(selfId);
            if (leftSelf != rightSelf) {
                return leftSelf ? -1 : 1;
            }
            int counts = Integer.compare(right.count(), left.count());
            return counts != 0 ? counts : left.name.compareToIgnoreCase(right.name);
        });
        return list;
    }

    public synchronized boolean isHidden(String ownerId) {
        return this.hiddenOwners.contains(ownerId);
    }

    public synchronized void setHidden(String ownerId, boolean hidden) {
        if (ownerId == null) {
            return;
        }
        if (hidden ? this.hiddenOwners.add(ownerId) : this.hiddenOwners.remove(ownerId)) {
            this.version++;
        }
    }

    public synchronized boolean isVisible(Owner owner) {
        if (owner == null) {
            return false;
        }
        return owner.id.equals(this.selfId()) || !this.hiddenOwners.contains(owner.id);
    }

    /** Claim of a chunk, or {@code null} when the chunk is unclaimed. */
    public synchronized Ref refAt(String dimension, int chunkX, int chunkZ) {
        Long2ObjectMap<Ref> map = this.index.get(dimension);
        return map == null ? null : map.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public synchronized Owner ownerAt(String dimension, int chunkX, int chunkZ) {
        Ref ref = this.refAt(dimension, chunkX, chunkZ);
        return ref == null ? null : ref.owner();
    }

    // ------------------------------------------------------------ local edits

    /** Claims the given chunks for the local player. */
    public synchronized int claim(String dimension, Collection<Long> keys, int color) {
        Owner self = this.ensureSelf();
        if (self == null || dimension == null || keys.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (long key : keys) {
            if (!self.has(dimension, key)) {
                int x = ChunkPos.getX(key);
                int z = ChunkPos.getZ(key);
                self.put(dimension, key, false, ClaimChunk.defaultId(x, z), false, false, true);
                added++;
            }
            this.pendingDeletes.remove(deleteKey(dimension, key));
        }
        if (added > 0) {
            this.editVersion++;
            this.settle();
        }
        return added;
    }

    /** Removes own claims. Claims of other players are never touched. */
    public synchronized int unclaim(String dimension, Collection<Long> keys) {
        Owner self = this.self();
        if (self == null || dimension == null) {
            return 0;
        }
        int removed = 0;
        for (long key : keys) {
            if (self.has(dimension, key)) {
                self.remove(dimension, key);
                this.pendingDeletes.add(deleteKey(dimension, key));
                removed++;
            }
        }
        if (removed > 0) {
            this.editVersion++;
            this.settle();
        }
        return removed;
    }

    /** Toggles the force load flag of own claims; missing claims are created. */
    public synchronized int setForceLoad(String dimension, Collection<Long> keys, boolean value, int color) {
        Owner self = this.ensureSelf();
        if (self == null || dimension == null) {
            return 0;
        }
        int changed = 0;
        for (long key : keys) {
            Claim claim = self.get(dimension, key);
            if (claim == null) {
                if (!value) {
                    continue;
                }
                int x = ChunkPos.getX(key);
                int z = ChunkPos.getZ(key);
                self.put(dimension, key, true, ClaimChunk.defaultId(x, z), false, false, true);
                this.pendingDeletes.remove(deleteKey(dimension, key));
                changed++;
                continue;
            }
            if (claim.forceLoad != value) {
                self.put(dimension, key, value, claim.id, claim.boundary, claim.enterMessage, true);
                changed++;
            }
        }
        if (changed > 0) {
            this.editVersion++;
            this.settle();
        }
        return changed;
    }

    /**
     * Renames own chunks. Chunks of other players are never touched.
     *
     * @return number of renamed chunks
     */
    public synchronized int renameChunks(String dimension, Collection<Long> keys, String id) {
        Owner self = this.self();
        if (self == null || dimension == null || keys == null || keys.isEmpty()) {
            return 0;
        }
        int renamed = 0;
        Set<String> oldIds = new LinkedHashSet<>();
        String newId = null;
        for (long key : keys) {
            Claim claim = self.get(dimension, key);
            if (claim == null) {
                continue;
            }
            String oldId = claim.id == null || claim.id.isBlank()
                    ? ClaimChunk.defaultId(ChunkPos.getX(key), ChunkPos.getZ(key))
                    : claim.id;
            oldIds.add(oldId);
            newId = ClaimChunk.sanitizeId(id, ChunkPos.getX(key), ChunkPos.getZ(key));
            self.put(dimension, key, claim.forceLoad, newId, claim.boundary, claim.enterMessage,
                    true);
            renamed++;
        }
        if (renamed > 0 && newId != null) {
            // The rules of the renamed group follow the new id when the old id
            // no longer owns any chunk.
            EntityRuleSet inherited = self.groupRules.get(newId);
            for (String old : oldIds) {
                EntityRuleSet removed = self.groupRules.remove(old);
                if (inherited == null) {
                    inherited = removed;
                }
            }
            if (inherited != null && !inherited.isEmpty()) {
                self.groupRules.put(newId, inherited);
            }
            pruneGroupRules(self);
            this.editVersion++;
            this.settle();
        }
        return renamed;
    }

    /** Toggles the boundary wall of own chunks. */
    public synchronized int setChunkBoundary(String dimension, Collection<Long> keys, boolean value) {
        Owner self = this.self();
        if (self == null || dimension == null || keys == null || keys.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (long key : keys) {
            Claim claim = self.get(dimension, key);
            if (claim == null || claim.boundary == value) {
                continue;
            }
            self.put(dimension, key, claim.forceLoad, claim.id, value, claim.enterMessage, true);
            changed++;
        }
        if (changed > 0) {
            this.editVersion++;
            this.settle();
        }
        return changed;
    }

    /** Toggles the "entered the chunk" hint of own chunks. */
    public synchronized int setChunkEnterMessage(String dimension, Collection<Long> keys, boolean value) {
        Owner self = this.self();
        if (self == null || dimension == null || keys == null || keys.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (long key : keys) {
            Claim claim = self.get(dimension, key);
            if (claim == null || claim.enterMessage == value) {
                continue;
            }
            self.put(dimension, key, claim.forceLoad, claim.id, claim.boundary, value, true);
            changed++;
        }
        if (changed > 0) {
            this.editVersion++;
            this.settle();
        }
        return changed;
    }

    /**
     * Merges own chunks into one id without touching their coordinates: every
     * listed chunk shares the lexicographically smallest id of the group
     * afterwards. Distant chunks merge as well.
     *
     * @return the merged id, or {@code null} when no own chunk was selected
     */
    public synchronized String mergeChunks(String dimension, Collection<Long> keys) {
        Owner self = this.self();
        if (self == null || dimension == null || keys == null || keys.isEmpty()) {
            return null;
        }
        String merged = null;
        for (long key : keys) {
            Claim claim = self.get(dimension, key);
            if (claim == null) {
                continue;
            }
            String id = claim.id == null || claim.id.isBlank()
                    ? ClaimChunk.defaultId(ChunkPos.getX(key), ChunkPos.getZ(key))
                    : claim.id;
            if (merged == null || id.compareTo(merged) < 0) {
                merged = id;
            }
        }
        if (merged == null) {
            return null;
        }
        int changed = 0;
        Set<String> oldIds = new LinkedHashSet<>();
        for (long key : keys) {
            Claim claim = self.get(dimension, key);
            if (claim == null) {
                continue;
            }
            oldIds.add(claim.id == null || claim.id.isBlank()
                    ? ClaimChunk.defaultId(ChunkPos.getX(key), ChunkPos.getZ(key))
                    : claim.id);
            if (!merged.equals(claim.id)) {
                changed++;
            }
            self.put(dimension, key, claim.forceLoad, merged, claim.boundary, claim.enterMessage, true);
        }
        if (changed > 0) {
            // Only the rules of the lexicographically smallest id survive the
            // merge (要求补充-15 item 7).
            EntityRuleSet inherited = self.groupRules.get(merged);
            for (String old : oldIds) {
                self.groupRules.remove(old);
            }
            if (inherited != null && !inherited.isEmpty()) {
                self.groupRules.put(merged, inherited);
            }
            pruneGroupRules(self);
            this.editVersion++;
            this.settle();
        }
        return merged;
    }

    /**
     * Every claimed chunk of every player as a flat list for the chunk list page.
     */
    public synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (Owner owner : this.owners.values()) {
            entries.addAll(owner.chunkEntries());
        }
        return entries;
    }

    /** Entry of one chunk, or {@code null} when the chunk is unclaimed. */
    public synchronized Entry entry(String dimension, int chunkX, int chunkZ) {
        Ref ref = this.refAt(dimension, chunkX, chunkZ);
        if (ref == null) {
            return null;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Claim claim = ref.owner().get(dimension, key);
        if (claim == null) {
            return null;
        }
        return new Entry(ref.owner(), dimension, chunkX, chunkZ, claim.forceLoad, claim.id,
                claim.boundary, claim.enterMessage);
    }

    /**
     * Local preview of a colour change; the server confirms it with the next list.
     */
    public synchronized void setOwnColor(int color) {
        Owner self = this.ensureSelf();
        if (self == null) {
            return;
        }
        self.color = color & 0xFFFFFF;
        this.settle();
    }

    /**
     * Local preview of the boundary flag; the server confirms it with the next
     * list.
     */
    public synchronized void setOwnBoundary(boolean value) {
        Owner self = this.ensureSelf();
        if (self == null) {
            return;
        }
        self.boundary = value;
        this.version++;
    }

    /**
     * Local preview of the entity rules of one claim group; the server confirms
     * them with the next authoritative list.
     */
    public synchronized void setGroupRules(String ownerId, String groupId, EntityRuleSet rules) {
        if (ownerId == null || groupId == null || groupId.isBlank()) {
            return;
        }
        Owner owner = this.owners.get(ownerId);
        if (owner == null && ownerId.equals(this.selfId())) {
            owner = this.ensureSelf();
        }
        if (owner == null) {
            return;
        }
        setGroupRules(owner, groupId, rules);
        this.version++;
    }

    private static void setGroupRules(Owner owner, String groupId, EntityRuleSet rules) {
        if (rules == null || rules.isEmpty()) {
            owner.groupRules.remove(groupId);
        } else {
            owner.groupRules.put(groupId, rules);
        }
    }

    /**
     * Own owner record, created on demand so rules can be edited before the first
     * claim.
     */
    public synchronized Owner selfOrCreate() {
        return this.ensureSelf();
    }

    /** Chunks the local player claims, used by the upload round. */
    public synchronized List<ClaimChunk> uploadChunks() {
        Owner self = this.self();
        if (self == null) {
            return List.of();
        }
        List<ClaimChunk> list = new ArrayList<>(self.count());
        for (Map.Entry<String, Long2ObjectMap<Claim>> entry : self.chunks.entrySet()) {
            for (Long2ObjectMap.Entry<Claim> chunk : entry.getValue().long2ObjectEntrySet()) {
                Claim claim = chunk.getValue();
                long key = chunk.getLongKey();
                list.add(new ClaimChunk(entry.getKey(), ChunkPos.getX(key), ChunkPos.getZ(key),
                        claim.forceLoad, claim.id, claim.boundary, claim.enterMessage));
            }
        }
        return list;
    }

    /**
     * Marks the local edits as confirmed once an upload round finished.
     *
     * @param expectedVersion edit version of the store when the round started;
     *                        the pending flags are kept when the player edited
     *                        something in the meantime
     */
    public synchronized void completeUpload(long expectedVersion) {
        if (this.editVersion != expectedVersion) {
            return;
        }
        Owner self = this.self();
        if (self != null) {
            for (Long2ObjectMap<Claim> map : self.chunks.values()) {
                for (Claim claim : map.values()) {
                    claim.pending = false;
                }
            }
        }
        this.pendingDeletes.clear();
    }

    // ------------------------------------------------------------ server list

    /** Applies the authoritative list of the server. */
    public synchronized void applyServerList(List<ClaimOwner> list) {
        if (list == null) {
            return;
        }
        Owner previousSelf = this.self();
        Map<String, Owner> next = new LinkedHashMap<>();
        for (ClaimOwner data : list) {
            if (data == null || data.id() == null || data.id().isBlank()) {
                continue;
            }
            Owner owner = new Owner(data.id(), data.name() == null ? "?" : data.name());
            owner.color = data.color() & 0xFFFFFF;
            owner.boundary = data.boundary();
            for (ClaimRuleGroup group : data.ruleGroups()) {
                if (group == null || group.id().isBlank() || group.rules().isEmpty()) {
                    continue;
                }
                owner.groupRules.put(group.id(), group.rules());
            }
            for (ClaimChunk chunk : data.chunks()) {
                String dimension = chunk.dimension();
                if (dimension == null) {
                    continue;
                }
                owner.put(dimension, ChunkPos.asLong(chunk.x(), chunk.z()), chunk.forceLoad(),
                        chunk.displayId(), chunk.boundary(), chunk.enterMessage(), false);
            }
            next.put(owner.id, owner);
        }
        // Keep the local edits of the own player: pending claims survive a list
        // that was created before the upload, and chunks the player already
        // removed are not resurrected.
        if (previousSelf != null) {
            Owner self = next.get(previousSelf.id);
            if (self == null) {
                self = new Owner(previousSelf.id, previousSelf.name);
                self.color = previousSelf.color;
                self.boundary = previousSelf.boundary;
                self.groupRules.putAll(previousSelf.groupRules);
                next.put(self.id, self);
            }
            for (Map.Entry<String, Long2ObjectMap<Claim>> entry : previousSelf.chunks.entrySet()) {
                for (Long2ObjectMap.Entry<Claim> chunk : entry.getValue().long2ObjectEntrySet()) {
                    long key = chunk.getLongKey();
                    if (this.pendingDeletes.contains(deleteKey(entry.getKey(), key))) {
                        self.remove(entry.getKey(), key);
                        continue;
                    }
                    if (chunk.getValue().pending) {
                        self.put(entry.getKey(), key, chunk.getValue().forceLoad, chunk.getValue().id,
                                chunk.getValue().boundary, chunk.getValue().enterMessage, true);
                    }
                }
            }
        }
        this.owners.clear();
        this.owners.putAll(next);
        this.settle();
    }

    /** Chunk keys the local player claimed inside one dimension. */
    public synchronized LongOpenHashSet ownKeys(String dimension) {
        LongOpenHashSet keys = new LongOpenHashSet();
        Owner self = this.self();
        if (self == null || dimension == null) {
            return keys;
        }
        Long2ObjectMap<Claim> map = self.chunks.get(dimension);
        if (map != null) {
            keys.addAll(map.keySet());
        }
        return keys;
    }

    /** Number of claims inside one dimension, used by the page labels. */
    public synchronized int count(String dimension) {
        int total = 0;
        for (Owner owner : this.owners.values()) {
            total += owner.count(dimension);
        }
        return total;
    }

    // ---------------------------------------------------------------- helpers

    private Owner ensureSelf() {
        String id = this.selfId();
        LocalPlayer player = Minecraft.getInstance().player;
        if (id.isEmpty() || player == null) {
            return null;
        }
        Owner owner = this.owners.get(id);
        if (owner == null) {
            owner = new Owner(id, player.getGameProfile().getName());
            this.owners.put(id, owner);
        }
        return owner;
    }

    private void settle() {
        this.rebuildIndex();
        this.version++;
    }

    /** Removes rules of groups that no longer own any chunk. */
    private static void pruneGroupRules(Owner owner) {
        if (owner.groupRules.isEmpty()) {
            return;
        }
        Set<String> used = new LinkedHashSet<>();
        for (Long2ObjectMap<Claim> map : owner.chunks.values()) {
            for (Long2ObjectMap.Entry<Claim> entry : map.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                used.add(entry.getValue().id == null || entry.getValue().id.isBlank()
                        ? ClaimChunk.defaultId(ChunkPos.getX(key), ChunkPos.getZ(key))
                        : entry.getValue().id);
            }
        }
        owner.groupRules.keySet().removeIf(id -> !used.contains(id));
    }

    private void rebuildIndex() {
        this.index.clear();
        for (Owner owner : this.owners.values()) {
            for (Map.Entry<String, Long2ObjectMap<Claim>> entry : owner.chunks.entrySet()) {
                Long2ObjectMap<Ref> map = this.index.computeIfAbsent(entry.getKey(),
                        ignored -> new Long2ObjectOpenHashMap<>());
                for (Long2ObjectMap.Entry<Claim> chunk : entry.getValue().long2ObjectEntrySet()) {
                    Claim claim = chunk.getValue();
                    map.put(chunk.getLongKey(), new Ref(owner, claim.forceLoad, claim.pending, claim.id,
                            claim.boundary, claim.enterMessage));
                }
            }
        }
    }

    private static String deleteKey(String dimension, long chunkKey) {
        return dimension + "#" + chunkKey;
    }
}
