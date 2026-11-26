package com.wwwwz.aigcs.core;

import net.minecraft.resources.ResourceLocation;

/** One "entity type -> amount" pair of a chunk census. */
public record EntityCount(ResourceLocation type, int count) {
}
