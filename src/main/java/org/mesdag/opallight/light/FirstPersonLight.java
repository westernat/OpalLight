package org.mesdag.opallight.light;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

/// 第一人称顶点使用屏幕空间坐标，统一按玩家手持光源的位置取色。
public final class FirstPersonLight {
    private FirstPersonLight() {}

    public static MultiBufferSource color(MultiBufferSource source) {
        if (LightColorCache.INSTANCE.isEmpty()) return source;
        var player = Minecraft.getInstance().player;
        if (player == null) return source;
        long color = LightColorCache.INSTANCE.sample(player.getX(), player.getEyeY() - 0.3, player.getZ());
        return ColoredLightBufferSource.hasTint(color) ? new ColoredLightBufferSource(source, color) : source;
    }
}
