package com.wwwwz.aigcs.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Entity census of one chunk: every entity type that is present and how many
 * entities of that type the client can see.
 * <p>
 * The census is deliberately type-based instead of storing every entity
 * instance: it stays small enough to travel with the chunk tile and it is
 * exactly what the chunk/entity overview needs.
 */
@SuppressWarnings("null")
public final class EntityCensus {
    /** Hard upper bound for type entries, also used as decoder bound. */
    public static final int MAX_TYPES = 256;
    public static final int MAX_COUNT = 1_000_000;

    private static final EntityCensus EMPTY = new EntityCensus(Map.of());

    private final Map<ResourceLocation, Integer> counts;

    public EntityCensus(Map<ResourceLocation, Integer> counts) {
        Map<ResourceLocation, Integer> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Integer> entry : counts.entrySet()) {
            ResourceLocation type = entry.getKey();
            Integer amount = entry.getValue();
            if (type == null || amount == null || amount <= 0) {
                continue;
            }
            copy.put(type, Math.min(amount, MAX_COUNT));
        }
        this.counts = Collections.unmodifiableMap(copy);
    }

    public static EntityCensus empty() {
        return EMPTY;
    }

    public Map<ResourceLocation, Integer> counts() {
        return this.counts;
    }

    public boolean isEmpty() {
        return this.counts.isEmpty();
    }

    public int total() {
        long total = 0;
        for (int amount : this.counts.values()) {
            total += amount;
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * Order independent hash so two censuses with the same content always
     * produce the same value regardless of map iteration order.
     */
    public long fingerprint() {
        long hash = 0x9E3779B97F4A7C15L;
        int total = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : this.counts.entrySet()) {
            total += entry.getValue();
            hash += (long) entry.getKey().hashCode() * 0x100000001B3L + entry.getValue() * 31L;
        }
        return hash ^ (total * 0x9E3779B1L);
    }

    public List<EntityCount> toList() {
        List<EntityCount> list = new ArrayList<>(this.counts.size());
        for (Map.Entry<ResourceLocation, Integer> entry : this.counts.entrySet()) {
            list.add(new EntityCount(entry.getKey(), entry.getValue()));
        }
        return list;
    }

    // ------------------------------------------------------------------ codecs

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(this.counts.size());
        for (Map.Entry<ResourceLocation, Integer> entry : this.counts.entrySet()) {
            buffer.writeUtf(entry.getKey().toString(), 256);
            buffer.writeVarInt(entry.getValue());
        }
    }

    public static EntityCensus read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_TYPES) {
            throw new DecoderException("Invalid entity census size " + size);
        }
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String rawType = buffer.readUtf(256);
            int amount = buffer.readVarInt();
            ResourceLocation type = ResourceLocation.tryParse(rawType);
            if (type != null && amount > 0) {
                counts.merge(type, Math.min(amount, MAX_COUNT), Integer::sum);
            }
        }
        return new EntityCensus(counts);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(this.counts.size());
        for (Map.Entry<ResourceLocation, Integer> entry : this.counts.entrySet()) {
            out.writeUTF(entry.getKey().toString());
            out.writeInt(entry.getValue());
        }
    }

    public static EntityCensus readFrom(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_TYPES) {
            throw new IOException("Invalid entity census size " + size);
        }
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String rawType = in.readUTF();
            int amount = in.readInt();
            ResourceLocation type = ResourceLocation.tryParse(rawType);
            if (type != null && amount > 0) {
                counts.merge(type, Math.min(amount, MAX_COUNT), Integer::sum);
            }
        }
        return new EntityCensus(counts);
    }

    @Override
    public String toString() {
        return "EntityCensus" + this.counts;
    }
}
