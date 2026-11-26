package com.wwwwz.aigcs.server;

import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/** Aggregated chunk/entity statistics of one dimension. */
public record DimensionStats(ResourceLocation dimension, int chunks, long entityTotal,
        Map<ResourceLocation, Integer> entityCounts) {

    public List<Map.Entry<ResourceLocation, Integer>> topEntities(int limit) {
        return this.entityCounts.entrySet().stream()
                .sorted(Map.Entry.<ResourceLocation, Integer>comparingByValue().reversed())
                .limit(Math.max(0, limit))
                .toList();
    }
}
