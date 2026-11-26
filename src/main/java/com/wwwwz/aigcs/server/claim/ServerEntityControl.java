package com.wwwwz.aigcs.server.claim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Enforces the per claim group entity rules on the server.
 * <p>
 * The rules are evaluated per entity type and per chunk group (see
 * {@link EntityRuleSet}):
 * <ul>
 * <li>{@link FinalizeSpawnEvent}: blocks natural spawns when the rule denies
 * {@code allowNaturalSpawn} and spawn eggs / the summon command when it denies
 * {@code allowUnnaturalSpawn}. Every other spawn path counts as natural.</li>
 * <li>{@link EntityJoinLevelEvent}: a safety net for spawn paths that never
 * call
 * {@code finalizeSpawn} (treated as unnatural) and the per chunk population
 * limit ({@code maxCount}).</li>
 * <li>{@link LivingChangeTargetEvent} plus the per tick pass: clears mob
 * aggression when the rule of the mob denies {@code allowAggro}.</li>
 * <li>{@link EntityTickEvent.Pre}: grants the rule buffs while the entity stays
 * inside the group, clears them again afterwards, and clamps isolated mobs into
 * their chunk as a safety net next to {@link IsolationPaths}.</li>
 * <li>{@link ServerTickEvent.Post}: scans a few controlled chunks per pass and
 * removes every entity above the per type population limit.</li>
 * </ul>
 * The claim manager keeps a chunk to rules index, so every check is a single
 * hash lookup on the hot path.
 */
@SuppressWarnings("null")
public final class ServerEntityControl {
    /** Keeps a stopped mob slightly inside its chunk. */
    private static final double CHUNK_MARGIN = 0.05D;
    /** How often the buff tracker drops entries of removed entities, in ticks. */
    private static final int BUFF_SWEEP_TICKS = 200;

    /**
     * Spawn reasons recorded by {@link FinalizeSpawnEvent} until the join event.
     */
    private static final Map<Entity, MobSpawnType> SPAWN_TYPES = Collections
            .synchronizedMap(new WeakHashMap<>());

    private static int cleanupTimer;
    private static int cleanupCursor;
    private static int buffSweepTimer;
    private static List<EntityChunk> lastSnapshot = List.of();

    private ServerEntityControl() {
    }

    // -------------------------------------------------------------- lifecycle

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerClaimManager manager = ServerClaimManager.get();
        if (manager == null || !enabled()) {
            return;
        }
        if (++buffSweepTimer >= BUFF_SWEEP_TICKS) {
            buffSweepTimer = 0;
            RuleBuffTracker.sweep();
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
        buffSweepTimer = 0;
        lastSnapshot = List.of();
        SPAWN_TYPES.clear();
        RuleBuffTracker.reset();
    }

    private static boolean enabled() {
        return Config.CLAIM_ENTITY_CONTROL.getAsBoolean();
    }

    // ----------------------------------------------------------------- spawns

    /**
     * Reason aware spawn gate. {@link MobSpawnType#SPAWN_EGG} and
     * {@link MobSpawnType#COMMAND} are the only "unnatural" spawn paths defined
     * by 要求补充-15; every other reason (chunk generation, the natural spawner,
     * spawner blocks, breeding, structure spawns, ...) counts as natural.
     */
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!enabled()) {
            return;
        }
        Mob mob = event.getEntity();
        MobSpawnType spawnType = event.getSpawnType();
        SPAWN_TYPES.put(mob, spawnType);
        ServerLevel level = event.getLevel().getLevel();
        EntityRule rule = ruleAt(level, Mth.floor(event.getX()) >> 4, Mth.floor(event.getZ()) >> 4,
                typeId(mob));
        if (rule == null || spawnAllowed(rule, spawnType)) {
            return;
        }
        event.setSpawnCancelled(true);
        AIGCsM4CnE.LOGGER.debug("Blocked {} spawn of {} in a controlled chunk", spawnType,
                BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
    }

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
        MobSpawnType recorded = SPAWN_TYPES.remove(mob);
        EntityRule rule = ruleAt(level, mob);
        if (rule == null) {
            return;
        }
        // A spawn path that never called finalizeSpawn is a command style spawn
        // (for example a modded direct add): treat it as unnatural.
        MobSpawnType spawnType = recorded == null ? MobSpawnType.COMMAND : recorded;
        if (!spawnAllowed(rule, spawnType)) {
            event.setCanceled(true);
            AIGCsM4CnE.LOGGER.debug("Blocked join of {} in a controlled chunk",
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
            return;
        }
        if (!withinPopulationLimit(level, mob, rule)) {
            event.setCanceled(true);
            AIGCsM4CnE.LOGGER.debug("Blocked spawn of {} above the population limit",
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
        }
    }

    /** Applies the natural / unnatural spawn flags of one rule. */
    private static boolean spawnAllowed(EntityRule rule, MobSpawnType type) {
        boolean unnatural = type == MobSpawnType.SPAWN_EGG || type == MobSpawnType.COMMAND;
        return unnatural ? rule.allowUnnaturalSpawn() : rule.allowNaturalSpawn();
    }

    /** Enforces the optional per chunk population limit of one rule. */
    private static boolean withinPopulationLimit(ServerLevel level, Mob mob, EntityRule rule) {
        int limit = rule.maxCount();
        if (limit <= 0) {
            return true;
        }
        String type = typeId(mob);
        int sameType = 0;
        for (Mob other : entitiesInChunk(level, mob.chunkPosition().x, mob.chunkPosition().z)) {
            if (typeId(other).equals(type)) {
                sameType++;
            }
        }
        return sameType < limit;
    }

    /** Clears the buffs a rule granted when the entity leaves the level. */
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            RuleBuffTracker.clear(living);
        }
    }

    // --------------------------------------------------------------- aggression

    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (!enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(event.getNewAboutToBeSetTarget() instanceof Player)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        EntityRule rule = ruleAt(level, mob);
        if (rule != null && !rule.allowAggro()) {
            event.setCanceled(true);
        }
    }

    // -------------------------------------------------------------- tick checks

    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!enabled()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        EntityRule rule = ruleAt(level, entity);
        if (rule != null && rule.hasBuffs()) {
            RuleBuffTracker.apply(living, rule.buffs());
        } else {
            RuleBuffTracker.clear(living);
        }
        if (!(entity instanceof Mob mob)) {
            return;
        }
        if (rule == null) {
            return;
        }
        clearAggro(mob, rule);
        if (rule.isolated()) {
            clampToChunk(mob);
        }
    }

    private static void clearAggro(Mob mob, EntityRule rule) {
        if (rule.allowAggro() || !(mob.getTarget() instanceof Player)) {
            return;
        }
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.getNavigation().stop();
    }

    /**
     * Safety net of the isolation rule: a mob can never end up outside its chunk
     * even when knockback, a teleport or a path built before a rule change pushes
     * it out. The next position is predicted from the current motion, so a mob
     * walking towards the border is stopped before it crosses it.
     */
    private static void clampToChunk(Mob mob) {
        int currentX = mob.chunkPosition().x;
        int currentZ = mob.chunkPosition().z;
        double minX = currentX * 16.0D + CHUNK_MARGIN;
        double maxX = currentX * 16.0D + 16.0D - CHUNK_MARGIN;
        double minZ = currentZ * 16.0D + CHUNK_MARGIN;
        double maxZ = currentZ * 16.0D + 16.0D - CHUNK_MARGIN;
        Vec3 motion = mob.getDeltaMovement();
        int nextX = Mth.floor(mob.getX() + motion.x) >> 4;
        int nextZ = Mth.floor(mob.getZ() + motion.z) >> 4;
        boolean leaving = nextX != currentX || nextZ != currentZ;
        boolean outside = mob.getX() < minX || mob.getX() > maxX || mob.getZ() < minZ
                || mob.getZ() > maxZ;
        if (!leaving && !outside) {
            return;
        }
        mob.setPos(Mth.clamp(mob.getX(), minX, maxX), mob.getY(), Mth.clamp(mob.getZ(), minZ, maxZ));
        mob.setDeltaMovement(0.0D, motion.y, 0.0D);
        mob.getNavigation().stop();
    }

    // ---------------------------------------------------------------- clean up

    private static void cleanChunk(ServerLevel level, EntityChunk chunk) {
        EntityRuleSet rules = chunk.rules();
        boolean anyLimit = false;
        for (EntityRule rule : rules.rules()) {
            if (rule.active() && rule.maxCount() > 0) {
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
            if (rule == null || !rule.active() || rule.maxCount() <= 0) {
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

    /**
     * Active rule of the entity type at the current position of the entity, or
     * {@code null} when the chunk has no rule group or the type is not listed.
     */
    private static EntityRule ruleAt(ServerLevel level, Entity entity) {
        return ruleAt(level, entity.chunkPosition().x, entity.chunkPosition().z, typeId(entity));
    }

    private static EntityRule ruleAt(ServerLevel level, int chunkX, int chunkZ, String type) {
        ServerClaimManager manager = ServerClaimManager.get();
        if (manager == null) {
            return null;
        }
        EntityClaim claim = manager.entityClaimAt(level.dimension().location().toString(), chunkX,
                chunkZ);
        if (claim == null) {
            return null;
        }
        EntityRule rule = claim.rules().rule(type);
        return rule != null && rule.active() ? rule : null;
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
