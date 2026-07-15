package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/// CPU 网格 worker 的完整不可变输入。
///
/// 渲染线程先从复制后的方块状态调色板解析模型、最终 ModelData 和六个面的可见性，worker
/// 只读取派生后的候选表。类中刻意不保存可变客户端世界、客户端单例、方块实体、世界访问器
/// 或共享模型缓存，因此取消、换世界和资源重载后不会从后台继续读取可变游戏状态。
final class MeshBuildSnapshot {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final long MAX_WORKER_RESULT_BYTES = 64L * 1024L * 1024L;

    static final class Candidate {
        private final long position;
        private final BlockState state;
        private final BakedModel model;
        private final ModelData modelData;
        private final long seed;
        private final int[] faceCornerLights;
        private final int visibleFaceMask;

        Candidate(
                long position,
                BlockState state,
                BakedModel model,
                ModelData modelData,
                long seed,
                int[] faceCornerLights,
                int visibleFaceMask
        ) {
            this.position = position;
            this.state = Objects.requireNonNull(state, "state");
            this.model = Objects.requireNonNull(model, "model");
            this.modelData = Objects.requireNonNull(modelData, "modelData");
            Objects.requireNonNull(faceCornerLights, "faceCornerLights");
            if (faceCornerLights.length != LightMaskMeshPrefilter.FACE_SAMPLE_COUNT) {
                throw new IllegalArgumentException("faceCornerLights must contain all 24 face corners");
            }
            int validFaceBits = (1 << PackedPosition.DIRECTION_COUNT) - 1;
            if ((visibleFaceMask & ~validFaceBits) != 0) {
                throw new IllegalArgumentException("visibleFaceMask contains an unknown direction bit");
            }
            this.seed = seed;
            this.faceCornerLights = faceCornerLights.clone();
            this.visibleFaceMask = visibleFaceMask;
        }

        long position() {
            return position;
        }

        BlockState state() {
            return state;
        }

        BakedModel model() {
            return model;
        }

        ModelData modelData() {
            return modelData;
        }

        long seed() {
            return seed;
        }

        boolean faceHasLight(int directionIndex) {
            return LightMaskMeshPrefilter.faceHasLight(faceCornerLights, directionIndex);
        }

        boolean isFaceVisible(int directionIndex) {
            return (visibleFaceMask & 1 << directionIndex) != 0;
        }

        void smoothColor(
                int directionIndex,
                float x,
                float y,
                float z,
                float[] output
        ) {
            LightMaskMeshPrefilter.smoothFaceColorAtVertex(
                    faceCornerLights, directionIndex, x, y, z, output
            );
        }
    }

    private final Long2ObjectOpenHashMap<List<Candidate>> candidatesBySection;
    private final long[] targetSections;

    MeshBuildSnapshot(
            Long2ObjectOpenHashMap<List<Candidate>> candidatesBySection,
            long[] targetSections
    ) {
        // 列表在 section 捕获完成后不再修改；只复制 Map 索引即可，避免再次复制所有候选引用。
        this.candidatesBySection = new Long2ObjectOpenHashMap<>(candidatesBySection);
        this.targetSections = targetSections.clone();
    }

    Result build(GenerationCoordinator.CancellationToken cancellation) {
        Long2ObjectOpenHashMap<BuiltSection> built = new Long2ObjectOpenHashMap<>();
        long resultBytes = 0L;
        try {
            for (long sectionKey : targetSections) {
                ensureNotCancelled(cancellation);
                List<Candidate> candidates = candidatesBySection.get(sectionKey);
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                BuiltSection mesh = buildSection(candidates, cancellation);
                if (mesh != null) {
                    if (mesh.gpuBytes() > MAX_WORKER_RESULT_BYTES - resultBytes) {
                        mesh.close();
                        throw new MeshBudgetExceededException();
                    }
                    built.put(sectionKey, mesh);
                    resultBytes += mesh.gpuBytes();
                }
            }
            return new Result(built);
        } catch (RuntimeException | Error failure) {
            closeBuilt(built);
            throw failure;
        }
    }

    private @Nullable BuiltSection buildSection(
            List<Candidate> candidates,
            GenerationCoordinator.CancellationToken cancellation
    ) {
        ByteBufferBuilder bytes = new ByteBufferBuilder(65_536);
        boolean transferred = false;
        try {
            BufferBuilder builder = new BufferBuilder(
                    bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR
            );
            RandomSource random = RandomSource.create(0L);
            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
            float[] smoothColor = new float[3];
            int visited = 0;
            for (Candidate candidate : candidates) {
                if ((visited++ & 63) == 0) {
                    ensureNotCancelled(cancellation);
                }
                blockPos.set(candidate.position());
                int worldX = blockPos.getX();
                int worldY = blockPos.getY();
                int worldZ = blockPos.getZ();
                for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
                    Direction direction = DIRECTIONS[directionIndex];
                    if (!candidate.isFaceVisible(directionIndex)) {
                        continue;
                    }
                    random.setSeed(candidate.seed());
                    for (BakedQuad quad : candidate.model().getQuads(
                            candidate.state(), direction, random, candidate.modelData(), null
                    )) {
                        renderQuad(
                                builder, quad, worldX, worldY, worldZ,
                                candidate, smoothColor
                        );
                    }
                }

                random.setSeed(candidate.seed());
                for (BakedQuad quad : candidate.model().getQuads(
                        candidate.state(), null, random, candidate.modelData(), null
                )) {
                    renderQuad(
                            builder, quad, worldX, worldY, worldZ,
                            candidate, smoothColor
                    );
                }
            }

            MeshData mesh = builder.build();
            if (mesh == null) {
                return null;
            }
            BuiltSection result = new BuiltSection(mesh, bytes);
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                bytes.close();
            }
        }
    }

    private static void renderQuad(
            BufferBuilder builder,
            BakedQuad quad,
            int worldX,
            int worldY,
            int worldZ,
            Candidate candidate,
            float[] smoothColor
    ) {
        int[] vertices = quad.getVertices();
        int directionIndex = quad.getDirection().get3DDataValue();
        if (!candidate.faceHasLight(directionIndex) || LightMaskMeshPrefilter.notBoundaryQuad(vertices, directionIndex)) {
            return;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            float x = Float.intBitsToFloat(vertices[offset]);
            float y = Float.intBitsToFloat(vertices[offset + 1]);
            float z = Float.intBitsToFloat(vertices[offset + 2]);
            candidate.smoothColor(directionIndex, x, y, z, smoothColor);
            builder.addVertex(worldX + x, worldY + y, worldZ + z)
                    .setColor(smoothColor[0], smoothColor[1], smoothColor[2], 1.0F)
                    .setUv(
                            Float.intBitsToFloat(vertices[offset + 4]),
                            Float.intBitsToFloat(vertices[offset + 5])
                    );
        }
    }

    private static void ensureNotCancelled(GenerationCoordinator.CancellationToken cancellation) {
        if (cancellation.isCancelled()) {
            throw new CancellationException("CPU mesh task is stale");
        }
    }

    private static void closeBuilt(Long2ObjectOpenHashMap<BuiltSection> built) {
        Throwable failure = null;
        for (BuiltSection mesh : built.values()) {
            try {
                mesh.close();
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        built.clear();
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /// worker 结果；只含尚未上传的原生顶点内存，绝不包含 OpenGL 对象。
    static final class Result implements AutoCloseable {
        private final Long2ObjectOpenHashMap<BuiltSection> sections;
        private boolean claimed;

        private Result(Long2ObjectOpenHashMap<BuiltSection> sections) {
            this.sections = sections;
        }

        Long2ObjectOpenHashMap<BuiltSection> claimSections() {
            if (claimed) {
                throw new IllegalStateException("CPU mesh result can only be claimed once");
            }
            claimed = true;
            return sections;
        }

        @Override
        public void close() {
            if (!claimed) {
                closeBuilt(sections);
                claimed = true;
            }
        }
    }

    /// 单个 section 的 CPU 顶点数据；上传和关闭由 Render thread 的 staging 阶段负责。
    static final class BuiltSection implements AutoCloseable {
        private final MeshData mesh;
        private final ByteBufferBuilder bytes;
        private final long gpuBytes;
        private boolean uploaded;

        BuiltSection(MeshData mesh, ByteBufferBuilder bytes) {
            this.mesh = mesh;
            this.bytes = bytes;
            long vertexBytes = mesh.vertexBuffer().remaining();
            long indexBytes = mesh.indexBuffer() == null ? 0L : mesh.indexBuffer().remaining();
            this.gpuBytes = vertexBytes + indexBytes;
        }

        long gpuBytes() {
            return gpuBytes;
        }

        void upload(VertexBuffer buffer) {
            if (uploaded) {
                throw new IllegalStateException("CPU mesh cannot be uploaded more than once");
            }
            // VertexBuffer.upload 接管 MeshData；即使上传抛错，本层也不能再二次关闭它。
            uploaded = true;
            try {
                buffer.upload(mesh);
            } finally {
                bytes.close();
            }
        }

        @Override
        public void close() {
            if (!uploaded) {
                uploaded = true;
                try {
                    mesh.close();
                } finally {
                    bytes.close();
                }
            }
        }
    }

    /// 整代 CPU 原生内存超过预算时，调用方改走有界的逐片构建/上传回退。
    static final class MeshBudgetExceededException extends RuntimeException {
        private MeshBudgetExceededException() {
            super("CPU mesh candidate exceeds 64 MiB and requires exact streaming fallback");
        }
    }
}
