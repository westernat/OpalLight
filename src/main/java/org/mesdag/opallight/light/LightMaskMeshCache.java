package org.mesdag.opallight.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;

public final class LightMaskMeshCache {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static boolean dirty;
    private static VertexBuffer buffer;

    public static void markDirty() {
        dirty = true;
    }

    public static void draw() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        if (dirty || buffer == null || buffer.isInvalid()) {
            upload(level);
        }

        if (buffer != null) {
            buffer.bind();
            buffer.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
            VertexBuffer.unbind();
        }
    }

    private static void upload(ClientLevel level) {
        MeshData mesh = build(level);
        dirty = false;

        if (mesh == null) {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            return;
        }

        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
    }

    private static MeshData build(ClientLevel level) {
        if (LightColorCache.INSTANCE.getSections().isEmpty()) return null;

        BufferBuilder builder = new BufferBuilder(new ByteBufferBuilder(8192), VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        RandomSource random = level.random;
        HashSet<BlockPos> renderedNull = new HashSet<>();
        BlockModelShaper shaper = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper();
        Map<BlockState, BakedModel> modelCache = new Reference2ObjectOpenHashMap<>();
        LayerLightEventListener listener = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (Map<Long, LightManager.Color> allLit : LightColorCache.INSTANCE.getSections().values()) {
            for (long packedPos : allLit.keySet()) {
                BlockPos airPos = BlockPos.of(packedPos);
                LightManager.Color airColor = LightColorCache.INSTANCE.get(airPos);
                for (Direction dir : DIRECTIONS) {
                    BlockPos solidPos = airPos.relative(dir.getOpposite());
                    BlockState solidState = level.getBlockState(solidPos);
                    if (solidState.isAir() || !Block.shouldRenderFace(solidState, level, solidPos, dir, airPos)) {
                        continue;
                    }

                    int wx = solidPos.getX(), wy = solidPos.getY(), wz = solidPos.getZ();
                    BakedModel model = modelCache.computeIfAbsent(solidState, shaper::getBlockModel);
                    long seed = solidState.getSeed(solidPos);
                    ModelData modelData = level.getModelData(solidPos);

                    random.setSeed(seed);
                    for (BakedQuad quad : model.getQuads(solidState, dir, random, modelData, null)) {
                        renderQuad(builder, quad, wx, wy, wz, listener, mutable, airColor);
                    }

                    if (renderedNull.add(solidPos)) {
                        random.setSeed(seed);
                        for (BakedQuad quad : model.getQuads(solidState, null, random, modelData, null)) {
                            renderQuad(builder, quad, wx, wy, wz, listener, mutable, airColor);
                        }
                    }
                }
            }
        }

        return builder.build();
    }

    private static void renderQuad(
            BufferBuilder consumer,
            BakedQuad quad,
            int wx, int wy, int wz,
            LayerLightEventListener listener,
            BlockPos.MutableBlockPos mutable,
            @Nullable LightManager.Color airColor
    ) {
        int[] vertices = quad.getVertices();
        int vertexSize = 8;

        float red = airColor == null ? 0 : airColor.r();
        float green = airColor == null ? 0 : airColor.g();
        float blue = airColor == null ? 0 : airColor.b();

        for (int i = 0; i < 4; i++) {
            int offset = i * vertexSize;
            float x = Float.intBitsToFloat(vertices[offset]);
            float y = Float.intBitsToFloat(vertices[offset + 1]);
            float z = Float.intBitsToFloat(vertices[offset + 2]);
            float u = Float.intBitsToFloat(vertices[offset + 4]);
            float v = Float.intBitsToFloat(vertices[offset + 5]);

            int bx = Math.round(wx + x);
            int by = Math.round(wy + y);
            int bz = Math.round(wz + z);

            int lightS = 0, lightC = 0;
            for (int dx = -1; dx <= 0; dx++) {
                mutable.setX(bx + dx);
                for (int dy = -1; dy <= 0; dy++) {
                    mutable.setY(by + dy);
                    for (int dz = -1; dz <= 0; dz++) {
                        mutable.setZ(bz + dz);
                        int lightV = listener.getLightValue(mutable);
                        if (lightV > 0) {
                            lightS += lightV;
                            lightC++;
                        }
                    }
                }
            }
            float alpha = lightC == 0 ? 0 : Mth.clamp((float) lightS / lightC / 15F * 0.5F - 0.1F, 0, 1);

            consumer.addVertex(wx + x, wy + y, wz + z)
                    .setColor(red, green, blue, alpha)
                    .setUv(u, v);
        }
    }

    public static void invalidate() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        markDirty();
    }
}
