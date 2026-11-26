package com.wwwwz.aigcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.wwwwz.aigcs.server.claim.IsolationPaths;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Ground navigation builds its paths on its own instead of calling the base
 * {@code PathNavigation#createPath(BlockPos, int)}, so the isolation check has
 * to be injected here as well. Subclasses such as
 * {@code AmphibiousPathNavigation}
 * inherit this method and are covered automatically.
 * <p>
 * The mob is read through {@link PathNavigationAccessor}; a plain
 * {@code @Shadow} cannot resolve a field declared in a superclass.
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {
    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void m4cne$blockGroundPath(BlockPos pos, int accuracy,
            CallbackInfoReturnable<Path> callback) {
        if (IsolationPaths.blocks(((PathNavigationAccessor) (Object) this).m4cne$mob(),
                pos.getX() + 0.5D, pos.getZ() + 0.5D)) {
            callback.setReturnValue(null);
        }
    }
}
