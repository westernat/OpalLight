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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelDataManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static org.mesdag.opallight.light.LightMeshLayout.*;

/// 负责生成不可变区块快照，并在工作线程中构建彩光网格。
final class LightMaskMeshBuilder {
    private static final int VERTEX_FLOATS = 10;
    private static final int OUTPUT_VERTEX_BYTES = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
    private static final int QUAD_FLOATS = VERTEX_FLOATS * 4;
    private static final byte[] EMPTY_VERTICES = new byte[0];
    private static final ThreadLocal<LightMeshColorGrid> colorScratch =
            ThreadLocal.withInitial(() -> new LightMeshColorGrid(0));
    record MeshSnapshot(long key, long epoch, int sectionX, int sectionZ,
                        RenderChunkRegion[] regions, List<Long2LongOpenHashMap> colorSections,
                        BlockRenderDispatcher dispatcher, boolean shaderPackInUse) {}

    /// 原始模型用于重新着色，已编码顶点用于未变化方块的批量复制；发布后均只读。
    record BlockMesh(float[] geometry, byte[] vertices) {}

    /// 1.20.1 没有 {@code MeshData}，改用 {@code BufferBuilder} 的渲染缓冲直接交给 {@code VertexBuffer}。
    static final class BuiltMesh implements AutoCloseable {
        private final BufferBuilder.RenderedBuffer mesh;
        private final Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry;
        private boolean uploaded;

        BuiltMesh(BufferBuilder.RenderedBuffer mesh, Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry) {
            this.mesh = mesh;
            this.geometry = geometry;
        }

        BufferBuilder.RenderedBuffer mesh() {
            return mesh;
        }

        Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry() {
            return geometry;
        }

        /// {@code VertexBuffer#upload} 内部已经释放渲染缓冲，避免重复释放。
        void markUploaded() {
            this.uploaded = true;
        }

        @Override
        public void close() {
            if (uploaded) return;
            uploaded = true;
            mesh.release();
        }
    }

    static @Nullable MeshSnapshot snapshot(ClientLevel level, long key, long epoch, RenderRegionCache regionCache) {
        int sx = SectionPos.x(key) << GROUP_XZ_SECTION_SHIFT;
        int sy = SectionPos.y(key) << GROUP_Y_SECTION_SHIFT;
        int sz = SectionPos.z(key) << GROUP_XZ_SECTION_SHIFT;
        /// 颜色分段提交后不再原地修改；只捕获引用，体素裁剪与复制交给构建线程。
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
                /// 1.20.1 的区域缓存只接受方块范围，用这一层的两个对角精确圈出单个分段。
                BlockPos origin = new BlockPos((sx + dx) << 4, sy << 4, (sz + dz) << 4);
                regions[dx + dz * 2] = regionCache.createRegion(level, origin, origin.offset(15, 15, 15), 0);
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
        return build(snapshot, previousGeometry, changedBlocks, cancelled, false);
    }

    static @Nullable BuiltMesh build(MeshSnapshot snapshot,
                                    @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry,
                                    LongSet changedBlocks, BooleanSupplier cancelled, boolean geometryOnly) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        LightMeshColorGrid colors = collectColors(snapshot);
        LongArrayList candidates;
        if (geometryOnly && previousGeometry != null) {
            /// 颜色未变时，只有变化方块及其面剔除/AO 邻域可能出现新几何。
            candidates = new LongArrayList(previousGeometry.size() + changedBlocks.size());
            candidates.addAll(previousGeometry.keySet());
            for (long pos : changedBlocks) {
                if (!previousGeometry.containsKey(pos)) candidates.add(pos);
            }
        } else {
            candidates = collectCandidates(snapshot.key(), colors);
        }
        if (candidates.isEmpty()) return null;
        BufferBuilder memory = new BufferBuilder(32768);
        memory.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        try {
            Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry = new Long2ObjectOpenHashMap<>();
            BufferBuilder.RenderedBuffer mesh = buildMesh(snapshot, colors, candidates, memory, geometry, previousGeometry, changedBlocks, cancelled, geometryOnly);
            if (mesh == null) {
                memory.clear();
                return null;
            }
            return new BuiltMesh(mesh, geometry);
        } catch (RuntimeException | Error error) {
            memory.clear();
            throw error;
        }
    }

    private static @Nullable BufferBuilder.RenderedBuffer buildMesh(MeshSnapshot snapshot, LightMeshColorGrid colors, LongArrayList candidates,
                                                BufferBuilder memory,
                                                Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry,
                                                @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry,
                                                LongSet changedBlocks, BooleanSupplier cancelled, boolean geometryOnly) {
        int vertexCount = 0;
        RandomSource random = RandomSource.create();
        Map<BlockState, BakedModel> modelCache = new Reference2ObjectOpenHashMap<>();
        var dispatcher = snapshot.dispatcher();
        BlockModelShaper shaper = null;
        PoseStack pose = new PoseStack();
        GeometryVertexConsumer consumer = new GeometryVertexConsumer();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        float[] sampled = new float[3];
        float[] output = new float[16];
        EncodingScratch encoded = new EncodingScratch();
        int minX = SectionPos.x(snapshot.key()) << GROUP_XZ_BLOCK_SHIFT;
        int minY = SectionPos.y(snapshot.key()) << GROUP_Y_BLOCK_SHIFT;
        int minZ = SectionPos.z(snapshot.key()) << GROUP_XZ_BLOCK_SHIFT;
        ModelBlockRenderer.enableCaching();
        try {
            for (long packed : candidates) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                BlockMesh previous = previousGeometry == null ? null : previousGeometry.get(packed);
                if (geometryOnly && previous != null && !changedBlocks.contains(packed)) {
                    vertexCount += appendVertices(memory, previous.vertices());
                    geometry.put(packed, previous);
                    continue;
                }
                float[] blockGeometry = previous == null ? null : previous.geometry();
                if (blockGeometry == null || changedBlocks.contains(packed)) {
                    pos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                    int regionX = (pos.getX() >> 4) - snapshot.sectionX();
                    int regionZ = (pos.getZ() >> 4) - snapshot.sectionZ();
                    RenderChunkRegion region = snapshot.regions()[regionX + regionZ * 2];
                    if (region == null) continue;
                    BlockState state = region.getBlockState(pos);
                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    if (shaper == null) shaper = dispatcher.getBlockModelShaper();
                    BakedModel model = modelCache.computeIfAbsent(state, shaper::getBlockModel);
                    /// 1.20.1 的区域对象不直接暴露模型数据，改由区域所属的模型数据管理器读取。
                    ModelData modelData = model.getModelData(region, pos, state, modelDataAt(region, pos));
                    random.setSeed(state.getSeed(pos));
                    consumer.clear();
                    for (RenderType renderType : model.getRenderTypes(state, random, modelData)) {
                        if (renderType == RenderType.translucent()) continue;
                        /// 光影包在地形顶点着色器中移动这类几何；静态遮罩不能写入它的旧深度。
                        if (snapshot.shaderPackInUse() && isPotentiallyWaving(state, region, pos, renderType)) continue;
                        pose.pushPose();
                        /// 模型顶点留在方块局部坐标，避免大世界坐标提前舍入。
                        dispatcher.renderBatched(state, pos, region, pose, consumer, true, random, modelData, renderType);
                        pose.popPose();
                    }
                    blockGeometry = consumer.vertices();
                }
                byte[] vertices = colorize(blockGeometry, colors, sampled, output, encoded, packed, minX, minY, minZ);
                if (vertices.length != 0) {
                    vertexCount += appendVertices(memory, vertices);
                    geometry.put(packed, new BlockMesh(blockGeometry, vertices));
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }
        return finishMesh(memory, vertexCount);
    }

    private static ModelData modelDataAt(RenderChunkRegion region, BlockPos pos) {
        ModelDataManager manager = region.getModelDataManager();
        if (manager == null) return ModelData.EMPTY;
        ModelData data = manager.getAt(pos);
        return data == null ? ModelData.EMPTY : data;
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
            /// 顶点插值会读取斜向体素，覆盖模型也要包含斜向相邻方块。
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

    private static byte[] colorize(float[] vertices, LightMeshColorGrid colors,
                                   float[] sampled, float[] output, EncodingScratch scratch,
                                   long packed, int originX, int originY, int originZ) {
        int blockX = BlockPos.getX(packed), blockY = BlockPos.getY(packed), blockZ = BlockPos.getZ(packed);
        java.nio.ByteBuffer encoded = scratch.acquire(vertices.length / VERTEX_FLOATS * OUTPUT_VERTEX_BYTES);
        for (int quad = 0; quad < vertices.length; quad += QUAD_FLOATS) {
            boolean lit = false;
            for (int vertex = 0; vertex < 4; vertex++) {
                int source = quad + vertex * VERTEX_FLOATS;
                int target = vertex * 4;
                sampleColor(blockX + (double) vertices[source] + vertices[source + 7],
                        blockY + (double) vertices[source + 1] + vertices[source + 8],
                        blockZ + (double) vertices[source + 2] + vertices[source + 9], sampled, colors);
                float strength = Math.max(sampled[0], Math.max(sampled[1], sampled[2]));
                int baseColor = (int) vertices[source + 5];
                output[target] = strength > 0 ? sampled[0] / strength * ((baseColor >>> 16) & 255) / 255F : 0;
                output[target + 1] = strength > 0 ? sampled[1] / strength * ((baseColor >>> 8) & 255) / 255F : 0;
                output[target + 2] = strength > 0 ? sampled[2] / strength * (baseColor & 255) / 255F : 0;
                output[target + 3] = strength * vertices[source + 6] * 0.9F;
                lit |= output[target + 3] > 0;
            }
            if (!lit) continue;
            for (int vertex = 0; vertex < 4; vertex++) {
                int source = quad + vertex * VERTEX_FLOATS;
                int target = vertex * 4;
                encoded.putFloat(blockX - originX + vertices[source]);
                encoded.putFloat(blockY - originY + vertices[source + 1]);
                encoded.putFloat(blockZ - originZ + vertices[source + 2]);
                encoded.putFloat(vertices[source + 3]);
                encoded.putFloat(vertices[source + 4]);
                for (int channel = 0; channel < 4; channel++) {
                    encoded.put((byte) (int) (output[target + channel] * 255.0F));
                }
            }
        }
        return encoded.position() == 0 ? EMPTY_VERTICES
                : java.util.Arrays.copyOf(encoded.array(), encoded.position());
    }

    /// 一次网格构建复用编码缓冲，未受光方块不再各自分配临时数组。
    private static final class EncodingScratch {
        private java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(0).order(java.nio.ByteOrder.nativeOrder());

        java.nio.ByteBuffer acquire(int bytes) {
            if (data.capacity() < bytes)
                data = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.nativeOrder());
            data.clear();
            return data;
        }
    }

    private static int appendVertices(BufferBuilder memory, byte[] vertices) {
        /// Forge 为 {@code BufferBuilder} 提供了直接写入预编码顶点的批量入口。
        memory.putBulkData(java.nio.ByteBuffer.wrap(vertices));
        return vertices.length / OUTPUT_VERTEX_BYTES;
    }

    private static @Nullable BufferBuilder.RenderedBuffer finishMesh(BufferBuilder memory, int vertexCount) {
        if (vertexCount == 0) return null;
        return memory.endOrDiscardIfEmpty();
    }

    /// 1.20.1 的顶点流按逐段写入，这里先把一个顶点的状态攒齐，再在 {@code endVertex} 编码。
    private static final class GeometryVertexConsumer implements VertexConsumer {
        private final FloatArrayList vertices = new FloatArrayList();
        private float x, y, z, u, v;
        private int color = 0xFFFFFFFF;
        private float nx, ny, nz;

        private void clear() {
            vertices.clear();
        }

        private float[] vertices() {
            return vertices.toFloatArray();
        }

        private void emit(float x, float y, float z, float red, float green, float blue, float alpha,
                          float u, float v, float nx, float ny, float nz) {
            /// 原版已在此之前完成模型偏移、AO、方块染色和模型顶点变换。
            /// 原版按模型面是否贴着方块边界选取相邻方块亮度，不能用整个方块的遮挡属性代替。
            float offset = faceSampleOffset(x, y, z, nx, ny, nz);
            /// 用一个可精确表示的浮点整数保存原版顶点色，保留 AO 与植被染色。
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);
            vertices.add(u);
            vertices.add(v);
            vertices.add((float) packRgb(red, green, blue));
            vertices.add(alpha);
            vertices.add(nx * offset);
            vertices.add(ny * offset);
            vertices.add(nz * offset);
        }

        private static int packRgb(float red, float green, float blue) {
            return channel(red) << 16 | channel(green) << 8 | channel(blue);
        }

        private static int channel(float value) {
            int channel = Math.round(value * 255.0F);
            return channel < 0 ? 0 : Math.min(channel, 255);
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
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha,
                           float texU, float texV, int overlayUV, int lightmapUV,
                           float normalX, float normalY, float normalZ) {
            emit(x, y, z, red, green, blue, alpha, texU, texV, normalX, normalY, normalZ);
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.color = FastColor.ARGB32.color(alpha, red, green, blue);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float nx, float ny, float nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            return this;
        }

        @Override
        public void endVertex() {
            emit(x, y, z,
                    FastColor.ARGB32.red(color) / 255F, FastColor.ARGB32.green(color) / 255F,
                    FastColor.ARGB32.blue(color) / 255F, FastColor.ARGB32.alpha(color) / 255F,
                    u, v, nx, ny, nz);
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {}

        @Override
        public void unsetDefaultColor() {}
    }

    private static void sampleColor(double x, double y, double z, float[] result, LightMeshColorGrid colors) {
        double gx = x - 0.5, gy = y - 0.5, gz = z - 0.5;
        int bx = (int) Math.floor(gx), by = (int) Math.floor(gy), bz = (int) Math.floor(gz);
        float fx = (float) (gx - bx), fy = (float) (gy - by), fz = (float) (gz - bz);
        result[0] = result[1] = result[2] = 0;
        for (int dx = 0; dx <= 1; dx++) {
            float wx = dx == 0 ? 1 - fx : fx;
            if (wx == 0) continue;
            for (int dy = 0; dy <= 1; dy++) {
                float wy = dy == 0 ? 1 - fy : fy;
                if (wy == 0) continue;
                for (int dz = 0; dz <= 1; dz++) {
                    float wz = dz == 0 ? 1 - fz : fz;
                    if (wz == 0) continue;
                    long color = colors.get(bx + dx, by + dy, bz + dz);
                    if (color == 0) continue;
                    float weight = wx * wy * wz;
                    result[0] += LightColorCache.channel(color, 32) * weight;
                    result[1] += LightColorCache.channel(color, 16) * weight;
                    result[2] += LightColorCache.channel(color, 0) * weight;
                }
            }
        }
    }

}
