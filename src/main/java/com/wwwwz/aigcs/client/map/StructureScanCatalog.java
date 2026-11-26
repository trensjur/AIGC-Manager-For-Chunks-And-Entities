package com.wwwwz.aigcs.client.map;

import java.util.ArrayList;
import java.util.List;

import com.wwwwz.aigcs.core.WaypointCategory;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Structures that the world map can locate through the seed based structure
 * placement search.
 * <p>
 * An entry either searches a vanilla structure tag (villages, ocean ruins,
 * mineshafts, ruined portals and shipwrecks all have several biome variants) or
 * a single structure id. The category is the waypoint category assigned to the
 * generated waypoint.
 */
public final class StructureScanCatalog {
    public enum DimensionKind {
        OVERWORLD,
        NETHER,
        END,
        ANY
    }

    public record Entry(String id, String tag, String translationKey, WaypointCategory category,
            DimensionKind dimension) {

        public boolean availableIn(ResourceLocation dimension) {
            return switch (this.dimension) {
                case OVERWORLD -> Level.OVERWORLD.location().equals(dimension);
                case NETHER -> Level.NETHER.location().equals(dimension);
                case END -> Level.END.location().equals(dimension);
                case ANY -> true;
            };
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            entry("village", "minecraft:village", WaypointCategory.VILLAGE, DimensionKind.OVERWORLD),
            entry("bastion_remnant", "", WaypointCategory.RUINS, DimensionKind.NETHER),
            entry("ocean_ruin", "minecraft:ocean_ruin", WaypointCategory.RUINS, DimensionKind.OVERWORLD),
            entry("ancient_city", "", WaypointCategory.RUINS, DimensionKind.OVERWORLD),
            entry("mineshaft", "minecraft:mineshaft", WaypointCategory.RUINS, DimensionKind.OVERWORLD),
            entry("igloo", "", WaypointCategory.RUINS, DimensionKind.OVERWORLD),
            entry("stronghold", "", WaypointCategory.DUNGEON, DimensionKind.OVERWORLD),
            entry("ocean_monument", "", WaypointCategory.TEMPLE, DimensionKind.OVERWORLD),
            entry("desert_pyramid", "", WaypointCategory.TEMPLE, DimensionKind.OVERWORLD),
            entry("jungle_temple", "", WaypointCategory.TEMPLE, DimensionKind.OVERWORLD),
            entry("ruined_portal", "minecraft:ruined_portal", WaypointCategory.PORTAL, DimensionKind.ANY),
            entry("swamp_hut", "", WaypointCategory.OUTPOST, DimensionKind.OVERWORLD),
            entry("end_city", "", WaypointCategory.OUTPOST, DimensionKind.END),
            entry("fortress", "", WaypointCategory.OUTPOST, DimensionKind.NETHER),
            entry("woodland_mansion", "", WaypointCategory.OUTPOST, DimensionKind.OVERWORLD),
            entry("pillager_outpost", "", WaypointCategory.OUTPOST, DimensionKind.OVERWORLD),
            entry("buried_treasure", "", WaypointCategory.TREASURE, DimensionKind.OVERWORLD),
            entry("shipwreck", "minecraft:shipwreck", WaypointCategory.TREASURE, DimensionKind.OVERWORLD),
            entry("trial_chambers", "", WaypointCategory.TRIAL_CHAMBER, DimensionKind.OVERWORLD));

    private StructureScanCatalog() {
    }

    private static Entry entry(String id, String tag, WaypointCategory category, DimensionKind dimension) {
        return new Entry(id, tag, "screen.m4cne.structure." + id, category, dimension);
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    /** Entries that can appear in the given dimension. */
    public static List<Entry> forDimension(ResourceLocation dimension) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (dimension == null || entry.availableIn(dimension)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** @return the catalogue entry with the given id or {@code null} */
    public static Entry byId(String id) {
        if (id != null) {
            for (Entry entry : ENTRIES) {
                if (entry.id().equals(id)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Finds the first entry whose translated name or id contains the query.
     * Used by the free text search field of the scan page.
     */
    public static Entry search(String query, ResourceLocation dimension) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        for (Entry entry : forDimension(dimension)) {
            String name = Component.translatable(entry.translationKey()).getString()
                    .toLowerCase(java.util.Locale.ROOT);
            if (entry.id().toLowerCase(java.util.Locale.ROOT).contains(needle) || name.contains(needle)) {
                return entry;
            }
        }
        return null;
    }
}
