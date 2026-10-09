package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.*;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static org.mesdag.opallight.light.LightMeshLayout.*;

final class LightMaskMeshBuilder {
    private static final int VERTEX_FLOATS = 9;
    static final VertexFormat VERTEX_FORMAT = LightMaskVertex.FORMAT;
    private static final int OUTPUT_VERTEX_BYTES = VERTEX_FORMAT.getVertexSize();
    private static final int QUAD_FLOATS = VERTEX_FLOATS * 4;
    private static final byte[] EMPTY_VERTICES = new byte[0];
    private static final ThreadLocal<LightMeshColorGrid> colorScratch =
            ThreadLocal.withInitial(() -> new LightMeshColorGrid(0));
    record MeshSnapshot(long key, long epoch, int sectionX, int sectionZ,
                        RenderChunkRegion[] regions, List<Long2LongOpenHashMap> colorSections,
                        BlockRenderDispatcher dispatcher, boolean shaderPackInUse) {}

    // 几何与顶点数组发布后只读。
    record BlockMesh(float[] geometry, byte[] vertices, LightUpdateBounds samples, int vertexOffset) {
        BlockMesh(float[] geometry, byte[] vertices, LightUpdateBounds samples) {
            this(geometry, vertices, samples, -1);
        }
    }

    record BuiltMesh(MeshData mesh, ByteBufferBuilder memory,
                     Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry,
                     boolean unchangedVertices, boolean matchingLayout) implements AutoCloseable {
        @Override
        public void close() {
            mesh.close();
            memory.close();
        }
    }

    static @Nullable MeshSnapshot snapshot(ClientLevel level, long key, long epoch, RenderRegionCache regionCache) {
        int sx = SectionPos.x(key) << GROUP_XZ_SECTION_SHIFT;
        int sy = SectionPos.y(key) << GROUP_Y_SECTION_SHIFT;
        int sz = SectionPos.z(key) << GROUP_XZ_SECTION_SHIFT;
        List<Long2LongOpenHashMap> colorSections = new ArrayList<>();
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 2; dz++) {
                    var lit = LightColorCache.INSTANCE.getSection(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                    if (lit == null) continue;
                    colorSections.add(lit);
                }
            }
        }
        if (colorSections.isEmpty()) return null;
        RenderChunkRegion[] regions = new RenderChunkRegion[4];
        boolean hasGeometry = false;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                regions[dx + dz * 2] = regionCache.createRegion(level, SectionPos.of(sx + dx, sy, sz + dz), false);
                hasGeometry |= regions[dx + dz * 2] != null;
            }
        }
        if (!hasGeometry) return null;
        return new MeshSnapshot(key, epoch, sx, sz, regions, colorSections,
                Minecraft.getInstance().getBlockRenderer(), LightShaderCompatibility.isShaderPackInUse());
    }

    private static LightMeshColorGrid collectColors(MeshSnapshot snapshot) {
        LightMeshColorGrid colors = colorScratch.get();
        colors.reset(snapshot.key());
        for (var section : snapshot.colorSections()) {
            for (var entry : section.long2LongEntrySet()) {
                colors.put(entry.getLongKey(), entry.getLongValue());
            }
        }
        return colors;
    }

    static @Nullable BuiltMesh build(MeshSnapshot snapshot,
                                    @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry,
                                    LongSet changedBlocks, BooleanSupplier cancelled) {
        return build(snapshot, previousGeometry, changedBlocks, cancelled, false, null);
    }

    static @Nullable BuiltMesh build(MeshSnapshot snapshot,
                                    @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry,
                                     LongSet changedBlocks, BooleanSupplier cancelled, boolean geometryOnly,
                                     @Nullable LightUpdateBounds colorChanges) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        LightMeshColorGrid colors = collectColors(snapshot);
        LongArrayList candidates;
        if (geometryOnly && previousGeometry != null) {
            candidates = new LongArrayList(previousGeometry.size() + changedBlocks.size());
            candidates.addAll(previousGeometry.keySet());
            for (long pos : changedBlocks) {
                if (!previousGeometry.containsKey(pos)) candidates.add(pos);
            }
        } else {
            candidates = collectCandidates(snapshot.key(), colors);
        }
        if (candidates.isEmpty()) return null;
        ByteBufferBuilder memory = new ByteBufferBuilder(65536);
        try {
            Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry = new Long2ObjectOpenHashMap<>();
            MeshData mesh = buildMesh(snapshot, colors, candidates, memory, geometry, previousGeometry, changedBlocks,
                cancelled, geometryOnly, colorChanges);
            if (mesh == null) {
                memory.close();
                return null;
            }
            boolean unchanged = sameVertices(previousGeometry, geometry);
            boolean matching = unchanged || sameLayout(previousGeometry, geometry);
            return new BuiltMesh(mesh, memory, geometry, unchanged, matching);
        } catch (RuntimeException | Error error) {
            memory.close();
            throw error;
        }
    }

    static boolean sameVertices(@Nullable Long2ObjectOpenHashMap<BlockMesh> previous,
                                Long2ObjectOpenHashMap<BlockMesh> current) {
        if (previous == null || previous.size() != current.size()) return false;
        for (var entry : current.long2ObjectEntrySet()) {
            BlockMesh old = previous.get(entry.getLongKey());
            if (old == null || old.vertexOffset() != entry.getValue().vertexOffset()
                || !java.util.Arrays.equals(old.vertices(), entry.getValue().vertices())) return false;
        }
        return true;
    }

    static boolean sameLayout(@Nullable Long2ObjectOpenHashMap<BlockMesh> previous,
                              Long2ObjectOpenHashMap<BlockMesh> current) {
        if (previous == null || previous.size() != current.size()) return false;
        for (var entry : current.long2ObjectEntrySet()) {
            BlockMesh old = previous.get(entry.getLongKey()), next = entry.getValue();
            if (old == null || old.vertexOffset() < 0 || old.vertexOffset() != next.vertexOffset()
                || old.vertices().length != next.vertices().length) return false;
            if (old.vertices() == next.vertices()) continue;
            for (int i = 0; i < next.vertices().length; i++) {
                int attribute = i % OUTPUT_VERTEX_BYTES;
                if ((attribute < 20 || attribute == 23) && old.vertices()[i] != next.vertices()[i]) return false;
            }
        }
        return true;
    }

    private static @Nullable MeshData buildMesh(MeshSnapshot snapshot, LightMeshColorGrid colors, LongArrayList candidates,
                                                ByteBufferBuilder memory,
                                                Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry,
                                                @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry,
                                                LongSet changedBlocks, BooleanSupplier cancelled, boolean geometryOnly,
                                                @Nullable LightUpdateBounds colorChanges) {
        int vertexCount = 0;
        RandomSource random = RandomSource.create();
        Map<BlockState, BakedModel> modelCache = new Reference2ObjectOpenHashMap<>();
        var dispatcher = snapshot.dispatcher();
        BlockModelShaper shaper = null;
        PoseStack pose = new PoseStack();
        GeometryVertexConsumer consumer = new GeometryVertexConsumer();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        float[] sampled = new float[3];
        float[] output = new float[12];
        EncodingScratch encoded = new EncodingScratch();
        int minX = SectionPos.x(snapshot.key()) << GROUP_XZ_BLOCK_SHIFT;
        int minY = SectionPos.y(snapshot.key()) << GROUP_Y_BLOCK_SHIFT;
        int minZ = SectionPos.z(snapshot.key()) << GROUP_XZ_BLOCK_SHIFT;
        ModelBlockRenderer.enableCaching();
        try {
            for (long packed : candidates) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                BlockMesh previous = previousGeometry == null ? null : previousGeometry.get(packed);
                if (previous != null && !changedBlocks.contains(packed)
                    && (geometryOnly || colorChanges != null && !colorChanges.intersects(previous.samples()))) {
                    geometry.put(packed, previous.vertexOffset() == vertexCount ? previous
                        : new BlockMesh(previous.geometry(), previous.vertices(), previous.samples(), vertexCount));
                    vertexCount += appendVertices(memory, previous.vertices());
                    continue;
                }
                int blockX = BlockPos.getX(packed), blockZ = BlockPos.getZ(packed);
                RenderChunkRegion region = snapshot.regions()[((blockX >> 4) - snapshot.sectionX())
                        + ((blockZ >> 4) - snapshot.sectionZ()) * 2];
                if (region == null) continue;
                float[] blockGeometry = previous == null ? null : previous.geometry();
                if (blockGeometry == null || changedBlocks.contains(packed)) {
                    pos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                    BlockState state = region.getBlockState(pos);
                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    if (shaper == null) shaper = dispatcher.getBlockModelShaper();
                    BakedModel model = modelCache.computeIfAbsent(state, shaper::getBlockModel);
                    var modelData = model.getModelData(region, pos, state, region.getModelData(pos));
                    random.setSeed(state.getSeed(pos));
                    consumer.clear();
                    for (RenderType renderType : model.getRenderTypes(state, random, modelData)) {
                        // 光影会移动这类几何，静态遮罩不能沿用旧深度。
                        if (snapshot.shaderPackInUse() && isPotentiallyWaving(state, region, pos, renderType)) continue;
                        pose.pushPose();
                        // 模型顶点留在方块局部坐标，避免大世界坐标提前舍入。
                        dispatcher.renderBatched(state, pos, region, pose, consumer, true, random, modelData, renderType);
                        pose.popPose();
                    }
                    blockGeometry = consumer.vertices();
                }
                byte[] vertices = encodeLightData(blockGeometry, colors, region, sampled, output, encoded, packed, minX, minY, minZ);
                if (vertices.length != 0) {
                    LightUpdateBounds samples = previous != null && blockGeometry == previous.geometry()
                        ? previous.samples() : sampleBounds(blockGeometry, packed);
                    geometry.put(packed, new BlockMesh(blockGeometry, vertices, samples, vertexCount));
                    vertexCount += appendVertices(memory, vertices);
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
            colors.finishSampling();
        }
        return finishMesh(memory, vertexCount);
    }

    private static boolean isPotentiallyWaving(BlockState state, RenderChunkRegion region, BlockPos pos,
                                               RenderType renderType) {
        if (renderType != RenderType.cutout() && renderType != RenderType.cutoutMipped()) return false;
        return state.is(BlockTags.LEAVES) || state.getCollisionShape(region, pos).isEmpty();
    }

    private static LongArrayList collectCandidates(long key, LightMeshColorGrid colors) {
        int minX = SectionPos.x(key) << GROUP_XZ_BLOCK_SHIFT;
        int minY = SectionPos.y(key) << GROUP_Y_BLOCK_SHIFT;
        int minZ = SectionPos.z(key) << GROUP_XZ_BLOCK_SHIFT;
        int sizeXZ = 1 << GROUP_XZ_BLOCK_SHIFT;
        int sizeY = 1 << GROUP_Y_BLOCK_SHIFT;
        BitSet occupied = new BitSet(sizeXZ * sizeXZ * sizeY);
        for (int index = colors.next(0); index >= 0; index = colors.next(index + 1)) {
            long packed = colors.position(index);
            int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
            int fromX = Math.max(x - 1, minX) - minX;
            int toX = Math.min(x + 1, minX + sizeXZ - 1) - minX + 1;
            if (fromX >= toX) continue;
            // 顶点插值会读取斜向体素，覆盖模型也要包含斜向相邻方块。
            for (int cy = Math.max(y - 1, minY); cy <= Math.min(y + 1, minY + sizeY - 1); cy++) {
                for (int cz = Math.max(z - 1, minZ); cz <= Math.min(z + 1, minZ + sizeXZ - 1); cz++) {
                    int row = ((cy - minY) * sizeXZ + cz - minZ) * sizeXZ;
                    occupied.set(row + fromX, row + toX);
                }
            }
        }
        LongArrayList candidates = new LongArrayList(occupied.cardinality());
        for (int index = occupied.nextSetBit(0); index >= 0; index = occupied.nextSetBit(index + 1)) {
            int cx = index % sizeXZ;
            int cz = index / sizeXZ % sizeXZ;
            int cy = index / (sizeXZ * sizeXZ);
            candidates.add(BlockPos.asLong(minX + cx, minY + cy, minZ + cz));
        }
        return candidates;
    }

    private static byte[] encodeLightData(float[] vertices, LightMeshColorGrid colors,
                                   BlockGetter view,
                                   float[] sampled, float[] output, EncodingScratch scratch,
                                   long packed, int originX, int originY, int originZ) {
        int blockX = BlockPos.getX(packed), blockY = BlockPos.getY(packed), blockZ = BlockPos.getZ(packed);
        java.nio.ByteBuffer encoded = scratch.acquire(vertices.length / VERTEX_FLOATS * OUTPUT_VERTEX_BYTES);
        for (int quad = 0; quad < vertices.length; quad += QUAD_FLOATS) {
            boolean lit = false;
            for (int vertex = 0; vertex < 4; vertex++) {
                int source = quad + vertex * VERTEX_FLOATS;
                int target = vertex * 3;
                // 面外体素作为锚点，墙角不能从墙外斜对角借用颜色。
                colors.sample(view, blockX + (double) vertices[source] + vertices[source + 6],
                    blockY + (double) vertices[source + 1] + vertices[source + 7],
                    blockZ + (double) vertices[source + 2] + vertices[source + 8],
                    blockX + (int) Math.signum(vertices[source + 6]),
                    blockY + (int) Math.signum(vertices[source + 7]),
                    blockZ + (int) Math.signum(vertices[source + 8]), sampled);
                output[target] = sampled[0];
                output[target + 1] = sampled[1];
                output[target + 2] = sampled[2];
                lit |= vertices[source + 5] > 0 && (sampled[0] > 0 || sampled[1] > 0 || sampled[2] > 0);
            }
            if (!lit) continue;
            for (int vertex = 0; vertex < 4; vertex++) {
                int source = quad + vertex * VERTEX_FLOATS;
                int target = vertex * 3;
                LightMaskVertex.write(encoded, blockX - originX + vertices[source],
                        blockY - originY + vertices[source + 1], blockZ - originZ + vertices[source + 2],
                    vertices[source + 3], vertices[source + 4], vertices[source + 5],
                        output[target], output[target + 1], output[target + 2]);
            }
        }
        return encoded.position() == 0 ? EMPTY_VERTICES
                : java.util.Arrays.copyOf(encoded.array(), encoded.position());
    }

    // 采样范围必须包含模型偏移与越界顶点。
    private static LightUpdateBounds sampleBounds(float[] vertices, long packed) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int source = 0; source < vertices.length; source += VERTEX_FLOATS) {
            int x = (int) Math.floor(BlockPos.getX(packed) + (double) vertices[source] + vertices[source + 6] - 0.5);
            int y = (int) Math.floor(BlockPos.getY(packed) + (double) vertices[source + 1] + vertices[source + 7] - 0.5);
            int z = (int) Math.floor(BlockPos.getZ(packed) + (double) vertices[source + 2] + vertices[source + 8] - 0.5);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1);
            maxY = Math.max(maxY, y + 1);
            maxZ = Math.max(maxZ, z + 1);
        }
        return new LightUpdateBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static final class EncodingScratch {
        private java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(0).order(java.nio.ByteOrder.nativeOrder());

        java.nio.ByteBuffer acquire(int bytes) {
            if (data.capacity() < bytes)
                data = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.nativeOrder());
            data.clear();
            return data;
        }
    }

    private static int appendVertices(ByteBufferBuilder memory, byte[] vertices) {
        org.lwjgl.system.MemoryUtil.memByteBuffer(memory.reserve(vertices.length), vertices.length).put(vertices);
        return vertices.length / OUTPUT_VERTEX_BYTES;
    }

    private static @Nullable MeshData finishMesh(ByteBufferBuilder memory, int vertexCount) {
        if (vertexCount == 0) return null;
        var mode = VertexFormat.Mode.QUADS;
        return new MeshData(java.util.Objects.requireNonNull(memory.build()), new MeshData.DrawState(
                VERTEX_FORMAT, vertexCount, mode.indexCount(vertexCount), mode,
                VertexFormat.IndexType.least(vertexCount)));
    }

    private static final class GeometryVertexConsumer implements VertexConsumer {
        private final FloatArrayList vertices = new FloatArrayList();
        private float x, y, z, u, v;
        private int color;

        private void clear() {
            vertices.clear();
        }

        private float[] vertices() {
            return vertices.toFloatArray();
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            float offset = faceSampleOffset(x, y, z, nx, ny, nz);
            float baseAlpha = FastColor.ARGB32.alpha(color) / 255F;
            // 遮罩直接使用纹理色，不重复乘 AO 与植被染色。
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);
            vertices.add(u);
            vertices.add(v);
            vertices.add(baseAlpha);
            vertices.add(nx * offset);
            vertices.add(ny * offset);
            vertices.add(nz * offset);
        }

        private float faceSampleOffset(float x, float y, float z, float nx, float ny, float nz) {
            if (nx > 0.999F && x > 0.9999F || nx < -0.999F && x < 0.0001F
                    || ny > 0.999F && y > 0.9999F || ny < -0.999F && y < 0.0001F
                    || nz > 0.999F && z > 0.9999F || nz < -0.999F && z < 0.0001F) {
                return 0.5F;
            }
            return 0;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.color = FastColor.ARGB32.color(alpha, red, green, blue);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            addVertex(x, y, z, color, u, v, 0, 0, nx, ny, nz);
            return this;
        }
    }

}
