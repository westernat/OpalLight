package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/// 在实体原有顶点流中加入彩光，保留其纹理、深度和渲染层。
public final class ColoredLightBufferSource implements MultiBufferSource {
    private final MultiBufferSource delegate;
    private final @Nullable Vec3 cameraPos;
    private final long fixedColor;

    public ColoredLightBufferSource(MultiBufferSource delegate, Vec3 cameraPos) {
        this.delegate = delegate;
        this.cameraPos = cameraPos;
        this.fixedColor = 0;
    }

    public ColoredLightBufferSource(MultiBufferSource delegate, long fixedColor) {
        this.delegate = delegate;
        this.cameraPos = null;
        this.fixedColor = fixedColor;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return new ColoredVertexConsumer(delegate.getBuffer(renderType), cameraPos, fixedColor);
    }

    public static VertexConsumer wrapFixed(VertexConsumer output, long color) {
        return hasTint(color) ? new ColoredVertexConsumer(output, null, color) : output;
    }

    public static boolean hasTint(long color) {
        long red = color >>> 32 & 65535L;
        long green = color >>> 16 & 65535L;
        long blue = color & 65535L;
        return red != green || green != blue;
    }

    /// 1.20.1 的顶点流仍按 {@code vertex/color/uv/.../endVertex} 逐段写入。
    private static final class ColoredVertexConsumer implements VertexConsumer {
        private final VertexConsumer output;
        private final @Nullable Vec3 cameraPos;
        private float red = 1.0F, green = 1.0F, blue = 1.0F;

        private ColoredVertexConsumer(VertexConsumer output, @Nullable Vec3 cameraPos, long fixedColor) {
            this.output = output;
            this.cameraPos = cameraPos;
            if (cameraPos == null) updateColor(fixedColor);
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            if (cameraPos != null)
                updateColor(LightColorCache.INSTANCE.sample(cameraPos.x + x, cameraPos.y + y, cameraPos.z + z));
            output.vertex(x, y, z);
            return this;
        }

        private void updateColor(long color) {
            float r = LightColorCache.channel(color, 32);
            float g = LightColorCache.channel(color, 16);
            float b = LightColorCache.channel(color, 0);
            float strength = Math.max(r, Math.max(g, b));
            red = 1.0F - 0.35F * (strength - r);
            green = 1.0F - 0.35F * (strength - g);
            blue = 1.0F - 0.35F * (strength - b);
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            output.color(Math.round(r * red), Math.round(g * green), Math.round(b * blue), a);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            output.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            output.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            output.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            output.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            output.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            output.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            output.unsetDefaultColor();
        }
    }
}
