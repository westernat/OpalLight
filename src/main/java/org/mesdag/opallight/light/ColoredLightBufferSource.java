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

    public static VertexConsumer wrap(VertexConsumer output, Vec3 cameraPos) {
        return new ColoredVertexConsumer(output, cameraPos, 0);
    }

    private static final class ColoredVertexConsumer implements VertexConsumer {
        private final VertexConsumer output;
        private final @Nullable Vec3 cameraPos;
        private final long fixedColor;
        private float red = 1.0F, green = 1.0F, blue = 1.0F;

        private ColoredVertexConsumer(VertexConsumer output, @Nullable Vec3 cameraPos, long fixedColor) {
            this.output = output;
            this.cameraPos = cameraPos;
            this.fixedColor = fixedColor;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            long color = cameraPos == null ? fixedColor : LightColorCache.INSTANCE.sample(cameraPos.x + x, cameraPos.y + y, cameraPos.z + z);
            float r = LightColorCache.channel(color, 32);
            float g = LightColorCache.channel(color, 16);
            float b = LightColorCache.channel(color, 0);
            float strength = Math.max(r, Math.max(g, b));
            red = 1.0F - 0.35F * (strength - r);
            green = 1.0F - 0.35F * (strength - g);
            blue = 1.0F - 0.35F * (strength - b);
            output.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            output.setColor(Math.round(r * red), Math.round(g * green), Math.round(b * blue), a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            output.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            output.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            output.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            output.setNormal(x, y, z);
            return this;
        }
    }
}
