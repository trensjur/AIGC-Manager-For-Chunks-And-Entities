package com.wwwwz.aigcs.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Finds standable feet positions for teleports and structure waypoints.
 * <p>
 * A position is standable when the feet and head blocks are free and the block
 * below can carry the player (solid or fluid). The search prefers the requested
 * Y, then walks up and down a few blocks, and finally falls back to the surface
 * height map so a player can never be teleported inside a wall.
 */
public final class SafePositions {
    /** How far the search walks away from the requested Y. */
    private static final int VERTICAL_SEARCH = 16;

    private SafePositions() {
    }

    /**
     * Resolves the surface (first air above the terrain) at the given column and
     * makes sure it is standable. Used for structure waypoints.
     */
    public static int surfaceY(ServerLevel level, int blockX, int blockZ) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        int resolved = resolve(level, blockX, blockZ, surface, true);
        return resolved != Integer.MIN_VALUE ? resolved : surface;
    }

    /**
     * Resolves a safe feet Y.
     *
     * @param preferredY  requested feet Y
     * @param surfaceOnly when {@code true} the search starts at the surface height
     *                    instead of {@code preferredY}
     * @return the safe Y or {@link Integer#MIN_VALUE} when no spot was found
     */
    public static int resolve(ServerLevel level, int blockX, int blockZ, int preferredY,
            boolean surfaceOnly) {
        return resolve(level, blockX, blockZ, preferredY, surfaceOnly, VERTICAL_SEARCH);
    }

    /**
     * Resolves a safe feet Y with an explicit search distance.
     *
     * @param preferredY     requested feet Y
     * @param surfaceOnly    start at the terrain surface instead of the requested Y
     * @param verticalSearch how far up/down the search walks
     */
    public static int resolve(ServerLevel level, int blockX, int blockZ, int preferredY,
            boolean surfaceOnly, int verticalSearch) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        if (maxY < minY) {
            return Integer.MIN_VALUE;
        }
        boolean nether = level.dimension().equals(Level.NETHER);
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        int start = Mth.clamp(surfaceOnly && !nether ? surface : preferredY, minY, maxY);
        int range = Math.max(0, verticalSearch);
        for (int offset = 0; offset <= range; offset++) {
            int up = start + offset;
            if (up <= maxY && standable(level, blockX, up, blockZ)) {
                return up;
            }
            int down = start - offset;
            if (offset > 0 && down >= minY && standable(level, blockX, down, blockZ)) {
                return down;
            }
        }
        if (nether) {
            // The heightmaps of the Nether point at the bedrock roof, so there
            // is no usable surface fallback there.
            return Integer.MIN_VALUE;
        }
        // Last resort: walk up from the terrain surface so the player always
        // lands on top of the world instead of inside it.
        int surfaceStart = Mth.clamp(surface, minY, maxY);
        for (int offset = 0; offset <= 24; offset++) {
            int up = surfaceStart + offset;
            if (up <= maxY && standable(level, blockX, up, blockZ)) {
                return up;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean standable(ServerLevel level, int blockX, int feetY, int blockZ) {
        BlockPos feet = new BlockPos(blockX, feetY, blockZ);
        BlockPos head = feet.above();
        if (!level.isInWorldBounds(feet) || !level.isInWorldBounds(head)) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        if (!feetState.getCollisionShape(level, feet).isEmpty()) {
            return false;
        }
        BlockState headState = level.getBlockState(head);
        if (!headState.getCollisionShape(level, head).isEmpty()) {
            return false;
        }
        BlockPos floor = feet.below();
        BlockState floorState = level.getBlockState(floor);
        return !floorState.getCollisionShape(level, floor).isEmpty()
                || !floorState.getFluidState().isEmpty();
    }
}
