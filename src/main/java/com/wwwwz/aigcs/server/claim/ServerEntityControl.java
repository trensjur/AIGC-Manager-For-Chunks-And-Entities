package com.wwwwz.aigcs.server.claim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.Config;
import com.wwwwz.aigcs.core.claim.EntityRule;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;
import com.wwwwz.aigcs.server.ServerClaimManager;
import com.wwwwz.aigcs.server.ServerClaimManager.EntityChunk;
import com.wwwwz.aigcs.server.ServerClaimManager.EntityClaim;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Enforces the per claim entity rules on the server.
 * <p>
 * Four pieces make up the feature:
 * <ul>
 * <li>{@link EntityJoinLevelEvent}: blocks spawns of a type inside a controlled
 * chunk when the owner disabled it, when the chunk reached the configured
 * population limit of that type or when a force loaded chunk refuses spawns.
 * Because every spawn path (natural spawns, spawn eggs, spawners, commands,
 * breeding) ends in this event, one check covers all of them.</li>
 * <li>{@link LivingChangeTargetEvent} plus the per tick pass: clears mob
 * aggression against a player who stands inside their own claim while the
 * owner disabled mob aggression.</li>
 * <li>{@link EntityTickEvent.Pre}: stops mobs from crossing a claim boundary
 * when entering or leaving is disabled.</li>
 * <li>{@link ServerTickEvent.Post}: scans a few controlled chunks per pass and
 * removes every entity above the per type population limit, so the chunk
 * falls back to the limit and its spawn mechanism reopens.</li>
 * </ul>
 * The claim manager keeps a chunk index of owners with an active rule set, so
 * every check is a single hash lookup on the hot path.
 */
@SuppressWarnings("null")
public final class ServerEntityControl {
    /** Mobs moving faster than this per tick are treated as teleports. */
    private static final double MAX_WALK_STEP = 3.0D;
    /** Keeps a stopped mob slightly inside its chunk. */
    private static final double CHUNK_MARGIN = 0.05D;

    private static int cleanupTimer;
    private static int cleanupCursor;
    private static List<EntityChunk> lastSnapshot = List.of();

    private ServerEntityControl() {
    }

    // -------------------------------------------------------------- lifecycle

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerClaimManager manager = ServerClaimManager.get();
        if (manager == null || !enabled()) {
            return;
        }
        int interval = Math.max(5, Config.CLAIM_ENTITY_CLEANUP_INTERVAL_TICKS.getAsInt());
        if (++cleanupTimer < interval) {
            return;
        }
        cleanupTimer = 0;
        List<EntityChunk> chunks = manager.entityChunks();
        if (chunks.isEmpty()) {
            cleanupCursor = 0;
            lastSnapshot = chunks;
            return;
        }
        if (chunks != lastSnapshot) {
            lastSnapshot = chunks;
            cleanupCursor = 0;
        }
        int budget = Math.min(Math.max(1, Config.CLAIM_ENTITY_CLEANUP_CHUNKS.getAsInt()), chunks.size());
        for (int pass = 0; pass < budget; pass++) {
            if (cleanupCursor >= chunks.size()) {
                cleanupCursor = 0;
            }
            EntityChunk chunk = chunks.get(cleanupCursor++);
            ServerLevel level = manager.level(chunk.dimension());
            if (level == null) {
                continue;
            }
            cleanChunk(level, chunk);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        cleanupTimer = 0;
        cleanupCursor = 0;
        lastSnapshot = List.of();
    }

    private static boolean enabled() {
        return Config.CLAIM_ENTITY_CONTROL.getAsBoolean();
    }

    // ----------------------------------------------------------------- spawns

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !enabled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        ServerClaimManager manager = ServerClaimManager.get();
        if (manager == null) {
            return;
        }
        EntityClaim claim = claimAt(manager, level, mob);
        if (claim == null || !claim.rules().enabled()) {
            return;
        }
        if (canSpawn(level, claim, mob)) {
            return;
        }
        event.setCanceled(true);
        AIGCsM4CnE.LOGGER.debug("Blocked spawn of {} in claimed chunk {}",
                BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), mob.chunkPosition());
    }

    /** @return {@code true} when the owner allows the mob to be added */
    private static boolean canSpawn(ServerLevel level, EntityClaim claim, Mob mob) {
        EntityRuleSet rules = claim.rules();
        if (!rules.allowSpawn()) {
            return false;
        }
        if (claim.forceLoad() && !rules.forceLoadAllowSpawn()) {
            return false;
        }
        String type = typeId(mob);
        EntityRule rule = rules.rule(type);
        if (rule != null && !rule.allowSpawn()) {
            return false;
        }
        int limit = rule == null ? 0 : rule.maxCount();
        if (limit <= 0) {
            return true;
        }
        // A reached population limit closes the spawn mechanism of the chunk:
        // commands, spawn eggs and spawners are refused just like natural
        // spawns, and the limit reopens as soon as the population drops again.
        int sameType = 0;
        for (Mob other : entitiesInChunk(level, mob.chunkPosition().x, mob.chunkPosition().z)) {
            if (typeId(other).equals(type)) {
                sameType++;
            }
        }
        return sameType < limit;
    }

    // --------------------------------------------------------------- aggression

    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (!enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(event.getNewAboutToBeSetTarget() instanceof Player player)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (denyAggro(level, mob, player)) {
            event.setCanceled(true);
        }
    }

    /**
     * @return {@code true} when the mob may no longer target the player: either
     *         the mob type is not allowed to attack at all, or the player stands
     *         inside their own claim whose owner disabled aggression
     */
    private static boolean denyAggro(ServerLevel level, Mob mob, Player player) {
        ServerClaimManager manager = ServerClaimManager.get();
        if (manager == null) {
            return false;
        }
        EntityClaim mobClaim = claimAt(manager, level, mob);
        if (mobClaim != null && mobClaim.rules().enabled()) {
            EntityRule rule = mobClaim.rules().rule(typeId(mob));
            if (rule != null && !rule.allowAggro()) {
                return true;
            }
        }
        EntityClaim playerClaim = manager.entityClaimAt(level.dimension().location().toString(),
                player.chunkPosition().x, player.chunkPosition().z);
        if (playerClaim == null || !playerClaim.rules().enabled()) {
            return false;
        }
        return playerClaim.ownerId().equals(player.getUUID().toString())
                && !playerClaim.rules().allowAggro();
    }

    // -------------------------------------------------------------- tick checks

    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level) || mob.isRemoved()) {
            return;
        }
        clearAggro(level, mob);
        limitBoundary(level, mob);
    }

    private static void clearAggro(ServerLevel level, Mob mob) {
        if (!(mob.getTarget() instanceof Player player)) {
            return;
        }
        if (denyAggro(level, mob, player)) {
            mob.setTarget(null);
            mob.setLastHurtByMob(null);
            mob.getNavigation().stop();
        }
    }

    /** Pushes a mob back when it tries to cross a locked claim boundary. */
    private static void limitBoundary(ServerLevel level, Mob mob) {
        Vec3 motion = mob.getDeltaMovement();
        if (Math.abs(motion.x) < 1.0E-4D && Math.abs(motion.z) < 1.0E-4D) {
            return;
        }
        if (motion.x * motion.x + motion.z * motion.z > MAX_WALK_STEP * MAX_WALK_STEP) {
            // Teleport or extreme knockback: do not fight the game logic.
            return;
        }
        int currentX = mob.chunkPosition().x;
        int currentZ = mob.chunkPosition().z;
        int nextX = Mth.floor(mob.getX() + motion.x) >> 4;
        int nextZ = Mth.floor(mob.getZ() + motion.z) >> 4;
        if (nextX == currentX && nextZ == currentZ) {
            return;
        }
        ServerClaimManager manager = ServerClaimManager.get();
        if (manager == null) {
            return;
        }
        String dimension = level.dimension().location().toString();
        EntityClaim current = manager.entityClaimAt(dimension, currentX, currentZ);
        EntityClaim next = manager.entityClaimAt(dimension, nextX, nextZ);
        if (current == null && next == null) {
            return;
        }
        boolean leaveBlocked = current != null && current.rules().enabled()
                && !current.rules().allowLeave()
                && (next == null || !next.ownerId().equals(current.ownerId()));
        boolean enterBlocked = next != null && next.rules().enabled()
                && !next.rules().allowEnter()
                && (current == null || !current.ownerId().equals(next.ownerId()));
        if (!leaveBlocked && !enterBlocked) {
            return;
        }
        double minX = currentX * 16.0D + CHUNK_MARGIN;
        double maxX = currentX * 16.0D + 16.0D - CHUNK_MARGIN;
        double minZ = currentZ * 16.0D + CHUNK_MARGIN;
        double maxZ = currentZ * 16.0D + 16.0D - CHUNK_MARGIN;
        mob.setPos(Mth.clamp(mob.getX(), minX, maxX), mob.getY(), Mth.clamp(mob.getZ(), minZ, maxZ));
        mob.setDeltaMovement(0.0D, motion.y, 0.0D);
        mob.getNavigation().stop();
    }

    // ---------------------------------------------------------------- clean up

    private static void cleanChunk(ServerLevel level, EntityChunk chunk) {
        EntityRuleSet rules = chunk.rules();
        if (!rules.enabled()) {
            return;
        }
        boolean anyLimit = false;
        for (EntityRule rule : rules.rules()) {
            if (rule.maxCount() > 0) {
                anyLimit = true;
                break;
            }
        }
        if (!anyLimit) {
            return;
        }
        List<Mob> mobs = entitiesInChunk(level, chunk.x(), chunk.z());
        if (mobs.isEmpty()) {
            return;
        }
        Set<Mob> excess = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, List<Mob>> byType = new HashMap<>();
        for (Mob mob : mobs) {
            byType.computeIfAbsent(typeId(mob), ignored -> new ArrayList<>()).add(mob);
        }
        for (Map.Entry<String, List<Mob>> entry : byType.entrySet()) {
            EntityRule rule = rules.rule(entry.getKey());
            if (rule == null || rule.maxCount() <= 0) {
                continue;
            }
            List<Mob> list = entry.getValue();
            for (int index = rule.maxCount(); index < list.size(); index++) {
                excess.add(list.get(index));
            }
        }
        if (excess.isEmpty()) {
            return;
        }
        for (Mob mob : excess) {
            mob.discard();
        }
        AIGCsM4CnE.LOGGER.debug("Removed {} excess entities from controlled chunk {}",
                excess.size(), chunk.dimension() + " " + chunk.x() + "," + chunk.z());
    }

    // ----------------------------------------------------------------- helpers

    private static EntityClaim claimAt(ServerClaimManager manager, ServerLevel level, Entity entity) {
        return manager.entityClaimAt(level.dimension().location().toString(),
                entity.chunkPosition().x, entity.chunkPosition().z);
    }

    private static List<Mob> entitiesInChunk(ServerLevel level, int chunkX, int chunkZ) {
        AABB box = new AABB(chunkX * 16.0D, level.getMinBuildHeight(), chunkZ * 16.0D,
                chunkX * 16.0D + 16.0D, level.getMaxBuildHeight(), chunkZ * 16.0D + 16.0D);
        return level.getEntitiesOfClass(Mob.class, box,
                mob -> mob.isAlive() && mob.chunkPosition().x == chunkX && mob.chunkPosition().z == chunkZ);
    }

    private static String typeId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }
}
