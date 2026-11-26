package com.wwwwz.aigcs.server.preload;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;
import com.wwwwz.aigcs.util.MapColors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * Server side counterpart of the client chunk sampler.
 * <p>
 * The client can only sample the chunks it has actually received, which stops
 * at
 * the render distance. A preload task runs on the server, so the sampler here
 * turns freshly generated server chunks into the same {@link ChunkTile} layout.
 * The only difference is the colour source: the server has no
 * {@code BlockColors}, so the vanilla map colour plus the biome tints for
 * grass,
 * foliage and water are used instead.
 */
@SuppressWarnings("null")
public final class ServerChunkSampler {
    private static final int STRIDE = 18;

    private final int[] heights = new int[STRIDE * STRIDE];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /**
     * Samples the surface of a loaded chunk.
     *
     * @return {@code false} when the chunk is empty or not fully loaded
     */
    public boolean sample(ServerLevel level, LevelChunk chunk, ChunkTile tile) {
        tile.clear();
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int minY = level.getMinBuildHeight();

        for (int x = -1; x <= 16; x++) {
            for (int z = -1; z <= 16; z++) {
                this.heights[(x + 1) * STRIDE + (z + 1)] = level.getHeight(
                        Heightmap.Types.WORLD_SURFACE, baseX + x, baseZ + z);
            }
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int index = ChunkTile.index(x, z);
                int top = this.heights[(x + 1) * STRIDE + (z + 1)];
                if (top <= minY + 1) {
                    continue;
                }
                BlockPos pos = this.cursor.set(baseX + x, top - 1, baseZ + z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    tile.heights[index] = (short) (top - 1);
                    continue;
                }

                int rgb = this.baseColor(level, pos, state);
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    rgb = MapColors.scaleRgb(rgb, waterDepthFactor(level, pos, minY));
                }
                tile.colors[index] = MapColors.rgbToAbgr(rgb);
                tile.heights[index] = (short) (top - 1);

                int west = this.heights[x * STRIDE + (z + 1)];
                int east = this.heights[(x + 2) * STRIDE + (z + 1)];
                int north = this.heights[(x + 1) * STRIDE + z];
                int south = this.heights[(x + 1) * STRIDE + (z + 2)];
                double slopeX = (west - east) * 0.5D;
                double slopeZ = (north - south) * 0.5D;
                double factor = Math.max(0.60D, Math.min(1.40D, 1.0D + (slopeX + slopeZ) * 0.075D));
                tile.shade[index] = (byte) Math.round((factor - 1.0D) * 160.0D);

                BlockPos above = pos.above();
                tile.skyLight[index] = (byte) level.getBrightness(LightLayer.SKY, above);
                tile.blockLight[index] = (byte) level.getBrightness(LightLayer.BLOCK, above);
            }
        }
        return true;
    }

    /**
     * Counts the entities inside one chunk. Only loaded entities are visible to
     * the server, which is exactly what the preload task just generated.
     */
    public EntityCensus census(ServerLevel level, int chunkX, int chunkZ) {
        if (!Config.RECORD_ENTITIES.getAsBoolean()) {
            return EntityCensus.empty();
        }
        double minX = chunkX << 4;
        double minZ = chunkZ << 4;
        AABB box = new AABB(minX, level.getMinBuildHeight(), minZ,
                minX + 16.0D, level.getMaxBuildHeight(), minZ + 16.0D);
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        level.getEntities().get(box, entity -> {
            if (entity == null || entity.isRemoved()) {
                return;
            }
            EntityType<?> type = entity.getType();
            if (type == null) {
                return;
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) {
                return;
            }
            if (counts.size() >= EntityCensus.MAX_TYPES && !counts.containsKey(id)) {
                return;
            }
            counts.merge(id, 1, Integer::sum);
        });
        if (counts.isEmpty()) {
            return EntityCensus.empty();
        }
        return new EntityCensus(new LinkedHashMap<>(counts));
    }

    private int baseColor(ServerLevel level, BlockPos pos, BlockState state) {
        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor == null) {
            return 0x808080;
        }
        int rgb = MapColors.abgrToRgb(mapColor.calculateRGBColor(MapColor.Brightness.NORMAL));
        try {
            if (mapColor == MapColor.GRASS || mapColor == MapColor.PLANT) {
                Biome biome = level.getBiome(pos).value();
                rgb = biome.getGrassColor(pos.getX(), pos.getZ()) & 0xFFFFFF;
            } else if (mapColor == MapColor.WATER) {
                Biome biome = level.getBiome(pos).value();
                rgb = biome.getWaterColor() & 0xFFFFFF;
            }
        } catch (RuntimeException ignored) {
            // A biome may be unavailable while a chunk is being replaced.
        }
        return rgb;
    }

    private static double waterDepthFactor(ServerLevel level, BlockPos surface, int minY) {
        int depth = 0;
        BlockPos.MutableBlockPos cursor = surface.mutable();
        for (int i = 0; i < 12; i++) {
            cursor.move(0, -1, 0);
            if (cursor.getY() <= minY) {
                break;
            }
            if (level.getBlockState(cursor).getFluidState().isEmpty()) {
                break;
            }
            depth++;
        }
        return 1.0D - Math.min(0.45D, depth * 0.035D);
    }
}
