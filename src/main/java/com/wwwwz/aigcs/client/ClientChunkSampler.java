package com.wwwwz.aigcs.client;

import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.util.MapColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

/**
 * Turns one loaded client chunk into a {@link ChunkTile}.
 * <p>
 * Height, colour, relief and light are sampled separately so the renderer can
 * adjust shading and the day/night cycle later without touching a single block
 * again. Neighbouring chunks are only read through the heightmap, which never
 * triggers chunk loading on the client.
 */
@SuppressWarnings("null")
public final class ClientChunkSampler {
    private static final int STRIDE = 18;

    private final int[] heights = new int[STRIDE * STRIDE];
    private final int[] caveHeights = new int[STRIDE * STRIDE];

    /**
     * Samples {@code chunkX/chunkZ} into {@code tile}.
     *
     * @return {@code false} when the chunk is not loaded or completely empty;
     *         the tile is cleared in that case
     */
    public boolean sample(ClientLevel level, int chunkX, int chunkZ, ChunkTile tile) {
        tile.clear();
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return false;
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (chunk.isEmpty()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BlockColors blockColors = minecraft.getBlockColors();
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

                BlockPos pos = new BlockPos(baseX + x, top - 1, baseZ + z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    tile.heights[index] = (short) (top - 1);
                    continue;
                }

                int rgb = baseColor(level, blockColors, pos, state);
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    rgb = MapColors.scaleRgb(rgb, waterDepthFactor(level, pos, minY));
                }
                tile.colors[index] = MapColors.rgbToAbgr(rgb);
                tile.heights[index] = (short) (top - 1);

                // Relief: central difference of the four neighbour heights, light
                // coming from the north-west. Negative means a slope facing west.
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
     * Samples the first solid block below {@code sliceY} for every column of a
     * chunk. The downward search is unlimited: it continues all the way to the
     * bottom of the world, so a column always ends at its real cave floor.
     *
     * @return {@code false} when the chunk is not loaded or completely empty
     */
    public boolean sampleCave(ClientLevel level, int chunkX, int chunkZ, int sliceY, ChunkTile tile) {
        tile.clear();
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return false;
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (chunk.isEmpty()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BlockColors blockColors = minecraft.getBlockColors();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int top = Mth.clamp(sliceY, minY + 1, maxY);

        for (int x = -1; x <= 16; x++) {
            for (int z = -1; z <= 16; z++) {
                this.caveHeights[(x + 1) * STRIDE + (z + 1)] = this.findCaveFloor(
                        level, baseX + x, baseZ + z, top, minY);
            }
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int foundY = this.caveHeights[(x + 1) * STRIDE + (z + 1)];
                if (foundY < minY) {
                    continue;
                }
                int index = ChunkTile.index(x, z);
                BlockPos pos = new BlockPos(baseX + x, foundY, baseZ + z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }

                int rgb = baseColor(level, blockColors, pos, state);
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    rgb = MapColors.scaleRgb(rgb, waterDepthFactor(level, pos, minY));
                }
                tile.colors[index] = MapColors.rgbToAbgr(rgb);
                tile.heights[index] = (short) foundY;

                int west = neighbourHeight(this.caveHeights[x * STRIDE + (z + 1)], foundY);
                int east = neighbourHeight(this.caveHeights[(x + 2) * STRIDE + (z + 1)], foundY);
                int north = neighbourHeight(this.caveHeights[(x + 1) * STRIDE + z], foundY);
                int south = neighbourHeight(this.caveHeights[(x + 1) * STRIDE + (z + 2)], foundY);
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

    /** Returns the first non-air block at or below {@code top}, or -1. */
    private int findCaveFloor(ClientLevel level, int worldX, int worldZ, int top, int minY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(worldX, top, worldZ);
        for (int y = top; y >= minY; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir()) {
                return y;
            }
        }
        return -1;
    }

    private static int neighbourHeight(int height, int fallback) {
        return height < 0 ? fallback : height;
    }

    private static int baseColor(ClientLevel level, BlockColors blockColors, BlockPos pos, BlockState state) {
        int tint = blockColors.getColor(state, level, pos, 0);
        if (tint != -1) {
            return tint & 0xFFFFFF;
        }
        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor == null) {
            return 0x808080;
        }
        return MapColors.abgrToRgb(mapColor.calculateRGBColor(MapColor.Brightness.NORMAL));
    }

    private static double waterDepthFactor(ClientLevel level, BlockPos surface, int minY) {
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
