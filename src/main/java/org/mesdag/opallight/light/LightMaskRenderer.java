package org.mesdag.opallight.light;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import static org.mesdag.opallight.light.LightMeshLayout.GROUP_XZ_BLOCK_SHIFT;
import static org.mesdag.opallight.light.LightMeshLayout.GROUP_Y_BLOCK_SHIFT;

/// 对可见遮罩先裁决深度，再只给最近的模型面叠合彩光。
final class LightMaskRenderer {
    private record TerrainFog(float start, float end, float red, float green, float blue,
                              float alpha, FogShape shape) {}

    private static TerrainFog terrainFog;
    private static VertexBuffer[] drawBuffers = new VertexBuffer[0];
    private static VertexBuffer[] previousBuffers = new VertexBuffer[0];
    private static float[] drawOffsets = new float[0];
    private static float[] transitionWeights = new float[0];

    static void captureTerrainFog() {
        float[] color = RenderSystem.getShaderFogColor();
        terrainFog = new TerrainFog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                color[0], color[1], color[2], color[3], RenderSystem.getShaderFogShape());
    }

    static void clearTerrainFog() {
        terrainFog = null;
    }

    static boolean isFullyFogged(AABB box, Vec3 cameraPos) {
        TerrainFog fog = terrainFog;
        if (fog == null || fog.alpha() < 1.0F || fog.end() <= 0.0F || !Float.isFinite(fog.end())
                || fog.start() >= fog.end()) return false;
        float cutoff = fog.end();
        double dx = Math.max(0.0, Math.max(box.minX - cameraPos.x, cameraPos.x - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - cameraPos.y, cameraPos.y - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - cameraPos.z, cameraPos.z - box.maxZ));
        double horizontal = dx * dx + dz * dz;
        double distanceSquared = fog.shape() == FogShape.SPHERE
                ? horizontal + dy * dy : Math.max(horizontal, dy * dy);
        return distanceSquared >= (double) cutoff * cutoff;
    }

    static void draw(Matrix4f viewMatrix, Camera camera, ShaderInstance lightMaskShader, LongArrayList visibleGroups,
                     Long2ObjectOpenHashMap<VertexBuffer> buffers,
                     Long2ObjectOpenHashMap<LightMaskMeshCache.Transition> transitions) {
        if (visibleGroups.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        lightMaskShader.setSampler("Sampler0", minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
        Vec3 pos = camera.getPosition();
        prepareDraws(pos, visibleGroups, buffers, transitions);
        lightMaskShader.MODEL_VIEW_MATRIX.set(viewMatrix);
        lightMaskShader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        lightMaskShader.apply();
        TerrainFog fog = terrainFog;
        if (fog != null) {
            /// 原版后续渲染阶段可能改写全局雾状态，遮罩始终使用地形雾。
            lightMaskShader.FOG_START.set(fog.start());
            lightMaskShader.FOG_END.set(fog.end());
            lightMaskShader.FOG_COLOR.set(fog.red(), fog.green(), fog.blue(), fog.alpha());
            lightMaskShader.FOG_SHAPE.set(fog.shape().getIndex());
            lightMaskShader.FOG_START.upload();
            lightMaskShader.FOG_END.upload();
            lightMaskShader.FOG_COLOR.upload();
            lightMaskShader.FOG_SHAPE.upload();
        }
        Uniform groupOffset = lightMaskShader.getUniform("GroupOffset");
        if (groupOffset == null) throw new IllegalStateException("Missing colored light group offset uniform");
        Uniform transitionWeight = lightMaskShader.getUniform("TransitionWeight");
        if (transitionWeight == null) throw new IllegalStateException("Missing colored light transition uniform");
        try {
            GlStateManager._polygonOffset(-1.0F, -1.0F);
            GlStateManager._enablePolygonOffset();
            GlStateManager._enableDepthTest();
            GlStateManager._disableBlend();
            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._colorMask(false, false, false, false);
            drawVisibleBuffers(groupOffset, transitionWeight, visibleGroups.size(), false);
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._depthMask(false);
            GlStateManager._depthFunc(GL11.GL_EQUAL);
            GlStateManager._enableBlend();
            /// 增量来自模型纹理，避免把已雾化的目标颜色再次增亮。
            GlStateManager._blendFunc(GL11.GL_ONE, GL11.GL_ONE);
            drawVisibleBuffers(groupOffset, transitionWeight, visibleGroups.size(), true);
        } finally {
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager._disableBlend();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._depthMask(true);
            GlStateManager._disablePolygonOffset();
            GlStateManager._polygonOffset(0.0F, 0.0F);
            lightMaskShader.clear();
            VertexBuffer.unbind();
            java.util.Arrays.fill(drawBuffers, 0, visibleGroups.size(), null);
            java.util.Arrays.fill(previousBuffers, 0, visibleGroups.size(), null);
        }
    }

    private static void prepareDraws(Vec3 cameraPos, LongArrayList visibleGroups,
                                     Long2ObjectOpenHashMap<VertexBuffer> buffers,
                                     Long2ObjectOpenHashMap<LightMaskMeshCache.Transition> transitions) {
        if (drawBuffers.length < visibleGroups.size()) {
            int capacity = Math.max(visibleGroups.size(), drawBuffers.length * 2);
            drawBuffers = new VertexBuffer[capacity];
            previousBuffers = new VertexBuffer[capacity];
            drawOffsets = new float[capacity * 3];
            transitionWeights = new float[capacity];
        }
        long now = System.nanoTime();
        for (int i = 0; i < visibleGroups.size(); i++) {
            long key = visibleGroups.getLong(i);
            drawBuffers[i] = buffers.get(key);
            var transition = transitions.get(key);
            previousBuffers[i] = transition == null ? null : transition.previous();
            transitionWeights[i] = transition == null ? 1.0F
                : Math.min(1.0F, (float) (now - transition.startedAt()) / LightMaskMeshCache.DYNAMIC_TRANSITION_NANOS);
            drawOffsets[i * 3] = (float) ((SectionPos.x(key) << GROUP_XZ_BLOCK_SHIFT) - cameraPos.x);
            drawOffsets[i * 3 + 1] = (float) ((SectionPos.y(key) << GROUP_Y_BLOCK_SHIFT) - cameraPos.y);
            drawOffsets[i * 3 + 2] = (float) ((SectionPos.z(key) << GROUP_XZ_BLOCK_SHIFT) - cameraPos.z);
        }
    }

    private static void drawVisibleBuffers(Uniform groupOffset, Uniform transitionWeight, int count, boolean colorPass) {
        /// 两遍复用同一份缓冲引用和相机相对坐标。
        float uploadedWeight = Float.NaN;
        for (int i = 0; i < count; i++) {
            groupOffset.set(drawOffsets[i * 3], drawOffsets[i * 3 + 1], drawOffsets[i * 3 + 2]);
            groupOffset.upload();
            if (previousBuffers[i] != null) {
                if (colorPass) {
                    float weight = 1.0F - transitionWeights[i];
                    transitionWeight.set(weight);
                    transitionWeight.upload();
                    uploadedWeight = weight;
                }
                previousBuffers[i].bind();
                previousBuffers[i].draw();
            }
            if (colorPass) {
                float weight = transitionWeights[i];
                if (uploadedWeight != weight) {
                    transitionWeight.set(weight);
                    transitionWeight.upload();
                    uploadedWeight = weight;
                }
            }
            VertexBuffer buffer = drawBuffers[i];
            buffer.bind();
            buffer.draw();
        }
    }
}
