package com.wwwwz.aigcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * Exposes the protected {@code mob} field of {@link PathNavigation}.
 * <p>
 * The isolation mixins need the mob that owns the navigation; a {@code @Shadow}
 * only resolves fields declared in the mixin target itself, so a tiny accessor
 * mixin on {@code PathNavigation} is used instead. Subclasses such as
 * {@code GroundPathNavigation} inherit the generated accessor.
 */
@Mixin(PathNavigation.class)
public interface PathNavigationAccessor {
    @Accessor("mob")
    Mob m4cne$mob();
}
