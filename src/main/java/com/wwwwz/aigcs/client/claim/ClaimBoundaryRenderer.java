package com.wwwwz.aigcs.client.claim;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wwwwz.aigcs.client.ClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the "claim boundary wall" in the world.
 * <p>
 * Every chunk of the claim list can switch its boundary wall on. The wall is
 * drawn along the outer edge of the claimed area (shared edges of chunks of the
 * same player are skipped), spans the full build height of the dimension and
 * uses the owner colour of the claim. The render style follows the vanilla
 * world border: a semi transparent, two sided quad strip.
 */
public final class ClaimBoundaryRenderer {
    /** Upper bound of wall quads per frame. */
    private static final int MAX_WALLS = 8192;
    /** Alpha of the wall; low enough to look through it like a world border. */
    private static final int WALL_ALPHA = 0x50;
    /** Extra chunks around the render distance that still get a wall. */
    private static final int RADIUS_MARGIN = 4;

    private ClaimBoundaryRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!ClientConfig.CLAIM_SHOW.getAsBoolean()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        ClaimStore store = ClaimStore.get();
        String dimension = level.dimension().location().toString();
        Vec3 camera = event.getCamera().getPosition();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        int centerChunkX = Mth.floor(camera.x) >> 4;
        int centerChunkZ = Mth.floor(camera.z) >> 4;
        int radius = Mth.clamp(minecraft.options.getEffectiveRenderDistance() + RADIUS_MARGIN, 8, 64);
        boolean showOthers = ClientConfig.CLAIM_SHOW_OTHERS.getAsBoolean();
        String selfId = store.selfId();

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();

        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.debugStructureQuads());
        int walls = 0;
        for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius && walls < MAX_WALLS; chunkX++) {
            for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius && walls < MAX_WALLS; chunkZ++) {
                ClaimStore.Ref ref = store.refAt(dimension, chunkX, chunkZ);
                if (ref == null || !ref.boundary() || !store.isVisible(ref.owner())) {
                    continue;
                }
                boolean self = ref.owner().id().equals(selfId);
                if (!self && !showOthers) {
                    continue;
                }
                int color = ref.owner().color() & 0xFFFFFF;
                String ownerId = ref.owner().id();
                int minBlockX = chunkX << 4;
                int minBlockZ = chunkZ << 4;
                int maxBlockX = minBlockX + 16;
                int maxBlockZ = minBlockZ + 16;
                // Only the outer edge of the claimed area is drawn; a chunk of
                // the same player next to this one hides the shared edge. The
                // walls sit a tiny bit outside the chunk to avoid z-fighting
                // with blocks that touch the chunk border.
                if (!sameOwner(store.refAt(dimension, chunkX - 1, chunkZ), ownerId)) {
                    walls += wall(consumer, matrix, color, minBlockX - 0.01F, minY, minBlockZ,
                            minBlockX - 0.01F, maxY, maxBlockZ, walls);
                }
                if (!sameOwner(store.refAt(dimension, chunkX + 1, chunkZ), ownerId)) {
                    walls += wall(consumer, matrix, color, maxBlockX + 0.01F, minY, minBlockZ,
                            maxBlockX + 0.01F, maxY, maxBlockZ, walls);
                }
                if (!sameOwner(store.refAt(dimension, chunkX, chunkZ - 1), ownerId)) {
                    walls += wall(consumer, matrix, color, minBlockX, minY, minBlockZ - 0.01F,
                            maxBlockX, maxY, minBlockZ - 0.01F, walls);
                }
                if (!sameOwner(store.refAt(dimension, chunkX, chunkZ + 1), ownerId)) {
                    walls += wall(consumer, matrix, color, minBlockX, minY, maxBlockZ + 0.01F,
                            maxBlockX, maxY, maxBlockZ + 0.01F, walls);
                }
            }
        }
        pose.popPose();
        buffers.endBatch(RenderType.debugStructureQuads());
    }

    private static boolean sameOwner(ClaimStore.Ref ref, String ownerId) {
        return ref != null && ref.owner().id().equals(ownerId);
    }

    /**
     * Emits one vertical quad of the wall. The two vertices of each side share
     * x or z, so the quad always forms a wall along one chunk edge.
     *
     * @return number of quads that fit below the frame limit
     */
    private static int wall(VertexConsumer consumer, Matrix4f matrix, int rgb, float x0, float y0,
            float z0, float x1, float y1, float z1, int drawn) {
        if (drawn >= MAX_WALLS) {
            return 0;
        }
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, WALL_ALPHA);
        consumer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, WALL_ALPHA);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, WALL_ALPHA);
        consumer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, WALL_ALPHA);
        return 1;
    }
}
