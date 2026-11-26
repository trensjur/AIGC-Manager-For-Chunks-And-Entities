package com.wwwwz.aigcs.server.claim;

import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.core.claim.EntityRule;
import com.wwwwz.aigcs.server.ServerClaimManager;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * Pathfinding level implementation of the "isolated" rule flag (要求补充-15
 * item 4).
 * <p>
 * While a mob stands inside a chunk whose rule marks its type as isolated, the
 * pathfinder treats every target outside that chunk as unreachable: the mixin
 * on
 * {@link PathNavigation} asks this class before it builds a path and returns
 * {@code null} / {@code false} instead, so the mob never even starts walking
 * towards the border. The tick check in {@link ServerEntityControl}
 * additionally
 * clamps the position as a hard safety net for knockback, teleports and paths
 * that were built before the rule changed.
 */
@SuppressWarnings("null")
public final class IsolationPaths {
    private IsolationPaths() {
    }

    /**
     * @return {@code true} when the mob must not path towards the given block
     *         coordinates because it is isolated inside its current chunk
     */
    public static boolean blocks(Mob mob, double targetX, double targetZ) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!Config.CLAIM_ENTITY_CONTROL.getAsBoolean()) {
            return false;
        }
        ServerClaimManager manager = ServerClaimManager.get();
        if (manager == null) {
            return false;
        }
        int currentX = mob.chunkPosition().x;
        int currentZ = mob.chunkPosition().z;
        ServerClaimManager.EntityClaim claim = manager.entityClaimAt(
                level.dimension().location().toString(), currentX, currentZ);
        if (claim == null) {
            return false;
        }
        EntityRule rule = claim.rules().rule(typeId(mob));
        if (rule == null || !rule.active() || !rule.isolated()) {
            return false;
        }
        int targetChunkX = Mth.floor(targetX) >> 4;
        int targetChunkZ = Mth.floor(targetZ) >> 4;
        return targetChunkX != currentX || targetChunkZ != currentZ;
    }

    static String typeId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }
}
