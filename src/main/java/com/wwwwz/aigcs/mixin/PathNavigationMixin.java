package com.wwwwz.aigcs.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.wwwwz.aigcs.server.claim.IsolationPaths;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Makes isolated mobs believe that everything outside their claimed chunk is
 * unreachable (要求补充-15 item 4).
 * <p>
 * Every goal that asks the navigation for a path ends up in one of the
 * {@code createPath} overloads or in {@code moveTo}; the injections simply
 * return {@code null} / {@code false} when the target lies outside the chunk
 * that isolates the mob, so no path towards the border is ever built. This is
 * cheaper and much smoother than pushing the mob back every tick, which is only
 * kept as a safety net for knockback and teleports.
 * <p>
 * The mixin is a common one (listed in the {@code mixins} array of
 * {@code m4cne.mixins.json}); {@link IsolationPaths} returns {@code false} on
 * the client, so nothing changes there.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin implements PathNavigationAccessor {
    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void m4cne$blockPosPath(BlockPos pos, int accuracy,
            CallbackInfoReturnable<Path> callback) {
        if (IsolationPaths.blocks(this.m4cne$mob(), pos.getX() + 0.5D, pos.getZ() + 0.5D)) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void m4cne$blockEntityPath(Entity target, int accuracy,
            CallbackInfoReturnable<Path> callback) {
        if (IsolationPaths.blocks(this.m4cne$mob(), target.getX(), target.getZ())) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "createPath(Ljava/util/Set;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void m4cne$blockMultiPath(Set<BlockPos> targets, int accuracy,
            CallbackInfoReturnable<Path> callback) {
        Mob mob = this.m4cne$mob();
        for (BlockPos pos : targets) {
            if (IsolationPaths.blocks(mob, pos.getX() + 0.5D, pos.getZ() + 0.5D)) {
                callback.setReturnValue(null);
                return;
            }
        }
    }

    @Inject(method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"), cancellable = true)
    private void m4cne$blockMoveToEntity(Entity target, double speed,
            CallbackInfoReturnable<Boolean> callback) {
        if (IsolationPaths.blocks(this.m4cne$mob(), target.getX(), target.getZ())) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "moveTo(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void m4cne$blockMoveTo(double x, double y, double z, double speed,
            CallbackInfoReturnable<Boolean> callback) {
        if (IsolationPaths.blocks(this.m4cne$mob(), x, z)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "moveTo(DDDID)Z", at = @At("HEAD"), cancellable = true)
    private void m4cne$blockMoveToAccuracy(double x, double y, double z, int accuracy, double speed,
            CallbackInfoReturnable<Boolean> callback) {
        if (IsolationPaths.blocks(this.m4cne$mob(), x, z)) {
            callback.setReturnValue(false);
        }
    }
}
