package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FastColor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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
        return color != 0 ? new ColoredVertexConsumer(output, null, color) : output;
    }

    static VertexConsumer wrapFixed(VertexConsumer output, long color, float skyBrightness, float ambientLight) {
        return color != 0 ? new ColoredVertexConsumer(output, null, color, skyBrightness, ambientLight) : output;
    }

    private static final class ColoredVertexConsumer implements VertexConsumer {
        private final VertexConsumer output;
        private final @Nullable Vec3 cameraPos;
        private float red = 1.0F, green = 1.0F, blue = 1.0F;
        private float strength;
        private final float skyBrightness, ambientLight;

        private ColoredVertexConsumer(VertexConsumer output, @Nullable Vec3 cameraPos, long fixedColor) {
            this(output, cameraPos, fixedColor,
                    LightBrightness.skyBrightness(Minecraft.getInstance() == null ? null : Minecraft.getInstance().level),
                    LightBrightness.ambientLight(Minecraft.getInstance() == null ? null : Minecraft.getInstance().level));
        }

        private ColoredVertexConsumer(VertexConsumer output, @Nullable Vec3 cameraPos, long fixedColor,
                                      float skyBrightness, float ambientLight) {
            this.output = output;
            this.cameraPos = cameraPos;
            this.skyBrightness = skyBrightness;
            this.ambientLight = ambientLight;
            if (cameraPos == null) updateColor(fixedColor);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            sampleAt(x, y, z);
            output.addVertex(x, y, z);
            return this;
        }

        private void updateColor(long color) {
            float r = LightColorCache.channel(color, 32);
            float g = LightColorCache.channel(color, 16);
            float b = LightColorCache.channel(color, 0);
            strength = Math.max(r, Math.max(g, b));
            float tint = LightBrightness.TINT_INTENSITY * LightFalloff.edgeOpacity(strength);
            red = 1.0F - tint * (strength - r);
            green = 1.0F - tint * (strength - g);
            blue = 1.0F - tint * (strength - b);
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            output.setColor(tintedColor(FastColor.ARGB32.color(a, r, g, b)));
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
            // 限制彩光亮度并按天空光衰减，避免在原版天空光之上额外加亮。
            output.setUv2(blockLight(u, v), v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            output.setNormal(x, y, z);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int packedOverlay, int packedLight, float normalX, float normalY, float normalZ) {
            sampleAt(x, y, z);
            int sky = packedLight >>> 16;
            int light = packedLight & 0xFFFF0000 | blockLight(packedLight & 0xFFFF, sky);
            output.addVertex(x, y, z, tintedColor(color), u, v, packedOverlay, light, normalX, normalY, normalZ);
        }

        private void sampleAt(float x, float y, float z) {
            if (cameraPos != null)
                updateColor(LightColorCache.INSTANCE.sample(cameraPos.x + x, cameraPos.y + y, cameraPos.z + z));
        }

        private int tintedColor(int color) {
            return FastColor.ARGB32.color(FastColor.ARGB32.alpha(color),
                    Math.round(FastColor.ARGB32.red(color) * red),
                    Math.round(FastColor.ARGB32.green(color) * green),
                    Math.round(FastColor.ARGB32.blue(color) * blue));
        }

        private int blockLight(int existing, int sky) {
            return Math.max(existing, LightBrightness.entityLight(strength, sky, skyBrightness, ambientLight));
        }
    }
}
