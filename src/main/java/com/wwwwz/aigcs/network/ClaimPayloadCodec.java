package com.wwwwz.aigcs.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wwwwz.aigcs.core.claim.ClaimChunk;
import com.wwwwz.aigcs.core.claim.ClaimOwner;
import com.wwwwz.aigcs.core.claim.ClaimRuleGroup;
import com.wwwwz.aigcs.core.claim.EntityRule;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Shared byte layout of the chunk claim payloads.
 * <p>
 * Claim lists carry a dimension table instead of repeating the dimension name
 * for every chunk: a player with thousands of claimed chunks would otherwise
 * waste most of the packet on the same strings.
 */
@SuppressWarnings("null")
public final class ClaimPayloadCodec {
    public static final int MAX_DIMENSION_LENGTH = 96;
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_DIMENSIONS = 64;
    /** Upper bound of an entity type id written inside a rule. */
    public static final int MAX_ENTITY_TYPE_LENGTH = 128;
    /** Upper bound of a mob effect id written inside a rule. */
    public static final int MAX_BUFF_ID_LENGTH = 96;
    /** Upper bound of rules transferred in one rule set. */
    public static final int MAX_RULES_PER_SET = 256;
    /** Upper bound of rule groups transferred for one claim owner. */
    public static final int MAX_RULE_GROUPS = 256;
    /** Upper bound of chunks inside one C2S batch. */
    public static final int MAX_CHUNKS_PER_PAYLOAD = 4096;
    /** Upper bound of chunks of one owner inside an S2C list. */
    public static final int MAX_CHUNKS_PER_OWNER = 65536;
    /** Upper bound of owners inside one S2C list. */
    public static final int MAX_OWNERS = 1024;

    private ClaimPayloadCodec() {
    }

    /** Collects the distinct dimensions of a list of claims. */
    private static Map<String, Integer> index(List<ClaimChunk> chunks, List<String> dimensions) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (ClaimChunk chunk : chunks) {
            String dimension = chunk.dimension() == null ? "" : chunk.dimension();
            Integer existing = index.get(dimension);
            if (existing == null) {
                index.put(dimension, dimensions.size());
                dimensions.add(dimension);
            }
        }
        return index;
    }

    public static void writeChunks(RegistryFriendlyByteBuf buffer, List<ClaimChunk> chunks) {
        int count = Math.min(chunks.size(), MAX_CHUNKS_PER_PAYLOAD);
        List<String> dimensions = new ArrayList<>();
        Map<String, Integer> index = index(chunks.subList(0, count), dimensions);
        buffer.writeVarInt(dimensions.size());
        for (String dimension : dimensions) {
            buffer.writeUtf(dimension, MAX_DIMENSION_LENGTH);
        }
        buffer.writeVarInt(count);
        for (int position = 0; position < count; position++) {
            ClaimChunk chunk = chunks.get(position);
            buffer.writeVarInt(index.get(chunk.dimension() == null ? "" : chunk.dimension()));
            buffer.writeInt(chunk.x());
            buffer.writeInt(chunk.z());
            buffer.writeBoolean(chunk.forceLoad());
            buffer.writeUtf(chunk.displayId(), ClaimChunk.MAX_ID_LENGTH);
            buffer.writeBoolean(chunk.boundary());
            buffer.writeBoolean(chunk.enterMessage());
        }
    }

    public static List<ClaimChunk> readChunks(RegistryFriendlyByteBuf buffer) {
        int dimensionCount = Math.min(buffer.readVarInt(), MAX_DIMENSIONS);
        List<String> dimensions = new ArrayList<>(dimensionCount);
        for (int position = 0; position < dimensionCount; position++) {
            dimensions.add(buffer.readUtf(MAX_DIMENSION_LENGTH));
        }
        int count = Math.min(buffer.readVarInt(), MAX_CHUNKS_PER_PAYLOAD);
        List<ClaimChunk> chunks = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            int dimension = buffer.readVarInt();
            int x = buffer.readInt();
            int z = buffer.readInt();
            boolean forceLoad = buffer.readBoolean();
            String id = buffer.readUtf(ClaimChunk.MAX_ID_LENGTH);
            boolean boundary = buffer.readBoolean();
            boolean enterMessage = buffer.readBoolean();
            if (dimension >= 0 && dimension < dimensions.size()) {
                chunks.add(new ClaimChunk(dimensions.get(dimension), x, z, forceLoad, id, boundary,
                        enterMessage));
            }
        }
        return chunks;
    }

    /** Writes the entity rules of one chunk group. */
    public static void writeRuleSet(RegistryFriendlyByteBuf buffer, EntityRuleSet ruleSet) {
        EntityRuleSet rules = ruleSet == null ? EntityRuleSet.EMPTY : ruleSet;
        List<EntityRule> list = rules.rules();
        int count = Math.min(list.size(), Math.min(MAX_RULES_PER_SET, EntityRuleSet.MAX_RULES));
        buffer.writeVarInt(count);
        for (int position = 0; position < count; position++) {
            EntityRule rule = list.get(position);
            buffer.writeUtf(rule.type(), MAX_ENTITY_TYPE_LENGTH);
            int flags = 0;
            if (rule.enabled()) {
                flags |= 1;
            }
            if (rule.allowNaturalSpawn()) {
                flags |= 2;
            }
            if (rule.allowUnnaturalSpawn()) {
                flags |= 4;
            }
            if (rule.allowAggro()) {
                flags |= 8;
            }
            if (rule.isolated()) {
                flags |= 16;
            }
            buffer.writeByte(flags);
            buffer.writeVarInt(rule.maxCount());
            List<String> buffs = rule.buffs();
            int buffCount = Math.min(buffs.size(), EntityRule.MAX_BUFFS);
            buffer.writeVarInt(buffCount);
            for (int index = 0; index < buffCount; index++) {
                buffer.writeUtf(buffs.get(index), MAX_BUFF_ID_LENGTH);
            }
        }
    }

    public static EntityRuleSet readRuleSet(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_RULES_PER_SET);
        List<EntityRule> rules = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            String type = buffer.readUtf(MAX_ENTITY_TYPE_LENGTH);
            int flags = buffer.readByte() & 0xFF;
            int maxCount = buffer.readVarInt();
            int buffCount = Math.min(buffer.readVarInt(), EntityRule.MAX_BUFFS);
            List<String> buffs = new ArrayList<>(buffCount);
            for (int index = 0; index < buffCount; index++) {
                buffs.add(buffer.readUtf(MAX_BUFF_ID_LENGTH));
            }
            rules.add(new EntityRule(type, (flags & 1) != 0, maxCount, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0, buffs));
        }
        return new EntityRuleSet(rules);
    }

    /** Writes the rule groups (chunk id + rules) of one claim owner. */
    public static void writeRuleGroups(RegistryFriendlyByteBuf buffer, List<ClaimRuleGroup> groups) {
        List<ClaimRuleGroup> list = groups == null ? List.of() : groups;
        int count = Math.min(list.size(), MAX_RULE_GROUPS);
        buffer.writeVarInt(count);
        for (int position = 0; position < count; position++) {
            ClaimRuleGroup group = list.get(position);
            buffer.writeUtf(group.id(), ClaimChunk.MAX_ID_LENGTH);
            writeRuleSet(buffer, group.rules());
        }
    }

    public static List<ClaimRuleGroup> readRuleGroups(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_RULE_GROUPS);
        List<ClaimRuleGroup> groups = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            String id = buffer.readUtf(ClaimChunk.MAX_ID_LENGTH);
            groups.add(new ClaimRuleGroup(id, readRuleSet(buffer)));
        }
        return groups;
    }

    public static void writeOwners(RegistryFriendlyByteBuf buffer, List<ClaimOwner> owners) {
        int ownerCount = Math.min(owners.size(), MAX_OWNERS);
        List<String> dimensions = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        List<List<ClaimChunk>> chunks = new ArrayList<>(ownerCount);
        for (int owner = 0; owner < ownerCount; owner++) {
            List<ClaimChunk> list = owners.get(owner).chunks();
            int limit = Math.min(list.size(), MAX_CHUNKS_PER_OWNER);
            List<ClaimChunk> limited = list.subList(0, limit);
            chunks.add(limited);
            for (ClaimChunk chunk : limited) {
                String dimension = chunk.dimension() == null ? "" : chunk.dimension();
                if (!index.containsKey(dimension)) {
                    index.put(dimension, dimensions.size());
                    dimensions.add(dimension);
                }
            }
        }
        buffer.writeVarInt(dimensions.size());
        for (String dimension : dimensions) {
            buffer.writeUtf(dimension, MAX_DIMENSION_LENGTH);
        }
        buffer.writeVarInt(ownerCount);
        for (int owner = 0; owner < ownerCount; owner++) {
            ClaimOwner data = owners.get(owner);
            buffer.writeUtf(data.id() == null ? "" : data.id(), MAX_ID_LENGTH);
            buffer.writeUtf(data.name() == null ? "" : data.name(), MAX_NAME_LENGTH);
            buffer.writeInt(data.color());
            buffer.writeBoolean(data.boundary());
            writeRuleGroups(buffer, data.ruleGroups());
            List<ClaimChunk> list = chunks.get(owner);
            buffer.writeVarInt(list.size());
            for (ClaimChunk chunk : list) {
                buffer.writeVarInt(index.get(chunk.dimension() == null ? "" : chunk.dimension()));
                buffer.writeInt(chunk.x());
                buffer.writeInt(chunk.z());
                buffer.writeBoolean(chunk.forceLoad());
                buffer.writeUtf(chunk.displayId(), ClaimChunk.MAX_ID_LENGTH);
                buffer.writeBoolean(chunk.boundary());
                buffer.writeBoolean(chunk.enterMessage());
            }
        }
    }

    public static List<ClaimOwner> readOwners(RegistryFriendlyByteBuf buffer) {
        int dimensionCount = Math.min(buffer.readVarInt(), MAX_DIMENSIONS);
        List<String> dimensions = new ArrayList<>(dimensionCount);
        for (int position = 0; position < dimensionCount; position++) {
            dimensions.add(buffer.readUtf(MAX_DIMENSION_LENGTH));
        }
        int ownerCount = Math.min(buffer.readVarInt(), MAX_OWNERS);
        List<ClaimOwner> owners = new ArrayList<>(ownerCount);
        for (int owner = 0; owner < ownerCount; owner++) {
            String id = buffer.readUtf(MAX_ID_LENGTH);
            String name = buffer.readUtf(MAX_NAME_LENGTH);
            int color = buffer.readInt();
            boolean boundary = buffer.readBoolean();
            List<ClaimRuleGroup> ruleGroups = readRuleGroups(buffer);
            int count = Math.min(buffer.readVarInt(), MAX_CHUNKS_PER_OWNER);
            List<ClaimChunk> chunks = new ArrayList<>(count);
            for (int position = 0; position < count; position++) {
                int dimension = buffer.readVarInt();
                int x = buffer.readInt();
                int z = buffer.readInt();
                boolean forceLoad = buffer.readBoolean();
                String chunkId = buffer.readUtf(ClaimChunk.MAX_ID_LENGTH);
                boolean chunkBoundary = buffer.readBoolean();
                boolean enterMessage = buffer.readBoolean();
                if (dimension >= 0 && dimension < dimensions.size()) {
                    chunks.add(new ClaimChunk(dimensions.get(dimension), x, z, forceLoad, chunkId,
                            chunkBoundary, enterMessage));
                }
            }
            owners.add(new ClaimOwner(id, name, color, boundary, chunks, ruleGroups));
        }
        return owners;
    }
}
