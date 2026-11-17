package com.bettercontent.spaceflight.client;

import com.bettercontent.spaceflight.BetterSpaceflight;
import com.bettercontent.spaceflight.travel.ServerTravelPolicy;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only visual geometry for existing policy coordinates. Intentionally dormant:
 * no server channel/provider can issue a presentation grant in this batch. This is
 * not a portal, an offer, a teleport, or a promise that a destination is available.
 */
@Mod.EventBusSubscriber(modid = BetterSpaceflight.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SpaceGateMarkerRenderer {
    private SpaceGateMarkerRenderer() {}

    @SubscribeEvent
    public static void afterTranslucent(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
            || !GateMarkerGeometry.authoritativeServerGrant()) return;
        // The false grant above is deliberate. Never substitute a client config,
        // dimension check or local policy prediction for authenticated server readiness.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || event.getCamera() == null || !event.getCamera().isInitialized()) return;
        var camera = event.getCamera().getPosition();
        var visible = GateMarkerGeometry.visible(minecraft.level.dimension().location().toString(),
            new ServerTravelPolicy.Position(camera.x, camera.y, camera.z), true);
        if (visible.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (var marker : visible) {
            var center = marker.center();
            double x = center.x(), y = center.y(), z = center.z(), radius = marker.radius();
            if (event.getFrustum() != null && !event.getFrustum().isVisible(
                new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius))) continue;
            pose.pushPose();
            try {
                pose.translate(x - camera.x, y - camera.y, z - camera.z);
                for (var edge : GateMarkerGeometry.outline(marker)) line(pose, lines, edge);
            } finally { pose.popPose(); }
        }
        buffers.endBatch(RenderType.lines());
        for (var marker : visible) {
            var center = marker.center();
            double x = center.x(), y = center.y(), z = center.z(), radius = marker.radius();
            if (event.getFrustum() != null && !event.getFrustum().isVisible(
                new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius))) continue;
            pose.pushPose();
            try {
                pose.translate(x - camera.x, y + 3 - camera.y, z - camera.z);
                pose.mulPose(event.getCamera().rotation());
                pose.scale(-0.025f, -0.025f, 0.025f);
                Font font = minecraft.font;
                String label = marker.label();
                font.drawInBatch(label, -font.width(label) / 2f, 0, 0xB4E6FF, false,
                    pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, 0xF000F0);
            } finally { pose.popPose(); }
        }
        buffers.endBatch();
    }

    private static void line(PoseStack pose, VertexConsumer consumer, GateMarkerGeometry.Segment edge) {
        var a = edge.start(); var b = edge.end();
        float dx = (float) (b.x() - a.x()), dy = (float) (b.y() - a.y()), dz = (float) (b.z() - a.z());
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0) return;
        var current = pose.last();
        consumer.vertex(current.pose(), (float) a.x(), (float) a.y(), (float) a.z())
            .color(116, 218, 255, 192).normal(current.normal(), dx / length, dy / length, dz / length).endVertex();
        consumer.vertex(current.pose(), (float) b.x(), (float) b.y(), (float) b.z())
            .color(116, 218, 255, 192).normal(current.normal(), dx / length, dy / length, dz / length).endVertex();
    }
}
