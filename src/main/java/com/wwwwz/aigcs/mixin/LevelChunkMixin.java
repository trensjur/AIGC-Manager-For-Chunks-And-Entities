package com.wwwwz.aigcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.wwwwz.aigcs.client.ClientChunkRecorder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reports every block state change that happens on the client to the chunk
 * recorder.
 * <p>
 * {@link LevelChunk#setBlockState} is the single funnel for server block
 * updates, local predictions and chunk section replacements. Injecting at
 * {@code RETURN} keeps the hook cheap and only forwards actual changes
 * (the method returns {@code null} when the state did not change).
 * <p>
 * The mixin is listed in the {@code client} section of
 * {@code m4cne.mixins.json}
 * so it is never applied on a dedicated server.
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void m4cne$onBlockStateChanged(BlockPos pos, BlockState state, boolean isMoving,
            CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() == null) {
            return;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (!chunk.getLevel().isClientSide) {
            return;
        }
        ClientChunkRecorder.get().markBlockChanged(chunk.getPos(), pos);
    }
}
