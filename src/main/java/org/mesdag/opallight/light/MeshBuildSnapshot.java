package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * CPU 网格 worker 的完整不可变输入。
 *
 * <p>渲染线程先从复制后的方块状态调色板解析模型、最终 ModelData 和六个面的可见性，worker
 * 只读取派生后的候选表。类中刻意不保存 {@code ClientLevel}、{@code Minecraft}、方块实体、
 * BlockGetter 或共享模型缓存，因此取消、换世界和资源重载后不会从后台继续读取可变游戏状态。</p>
 */
final class MeshBuildSnapshot {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final long MAX_WORKER_RESULT_BYTES = 64L * 1024L * 1024L;

    record Candidate(
            long position,
            BlockState state,
            BakedModel model,
            ModelData modelData,
            long seed,
            int[] neighborLights,
            boolean[] visibleFaces,
            int unculledLight
    ) {
        Candidate {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(modelData, "modelData");
            if (neighborLights.length != PackedPosition.DIRECTION_COUNT) {
                throw new IllegalArgumentException("neighborLights 必须包含六个方向");
            }
            if (visibleFaces.length != PackedPosition.DIRECTION_COUNT) {
                throw new IllegalArgumentException("visibleFaces 必须包含六个方向");
            }
            neighborLights = neighborLights.clone();
            visibleFaces = visibleFaces.clone();
        }

        @Override
        public int[] neighborLights() {
            return neighborLights.clone();
        }

        @Override
        public boolean[] visibleFaces() {
            return visibleFaces.clone();
        }

        int light(int directionIndex) {
            return neighborLights[directionIndex];
        }

        boolean isFaceVisible(int directionIndex) {
            return visibleFaces[directionIndex];
        }
    }

    private final Long2ObjectOpenHashMap<List<Candidate>> candidatesBySection;
    private final long[] targetSections;

    MeshBuildSnapshot(
            Long2ObjectOpenHashMap<List<Candidate>> candidatesBySection,
            long[] targetSections
    ) {
        this.candidatesBySection = new Long2ObjectOpenHashMap<>();
        candidatesBySection.forEach((sectionKey, candidates) ->
                this.candidatesBySection.put(sectionKey.longValue(), List.copyOf(candidates))
        );
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

    private BuiltSection buildSection(
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
                    int light = candidate.light(directionIndex);
                    if (light == 0) {
                        continue;
                    }
                    Direction direction = DIRECTIONS[directionIndex];
                    if (!candidate.isFaceVisible(directionIndex)) {
                        continue;
                    }
                    random.setSeed(candidate.seed());
                    for (BakedQuad quad : candidate.model().getQuads(
                            candidate.state(), direction, random, candidate.modelData(), null
                    )) {
                        renderQuad(builder, quad, worldX, worldY, worldZ, light);
                    }
                }

                random.setSeed(candidate.seed());
                for (BakedQuad quad : candidate.model().getQuads(
                        candidate.state(), null, random, candidate.modelData(), null
                )) {
                    renderQuad(builder, quad, worldX, worldY, worldZ, candidate.unculledLight());
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
            int light
    ) {
        int[] vertices = quad.getVertices();
        float red = PackedLight.red(light) / 15.0F;
        float green = PackedLight.green(light) / 15.0F;
        float blue = PackedLight.blue(light) / 15.0F;
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            builder.addVertex(
                            worldX + Float.intBitsToFloat(vertices[offset]),
                            worldY + Float.intBitsToFloat(vertices[offset + 1]),
                            worldZ + Float.intBitsToFloat(vertices[offset + 2])
                    )
                    .setColor(red, green, blue, 1.0F)
                    .setUv(
                            Float.intBitsToFloat(vertices[offset + 4]),
                            Float.intBitsToFloat(vertices[offset + 5])
                    );
        }
    }

    private static void ensureNotCancelled(GenerationCoordinator.CancellationToken cancellation) {
        if (cancellation.isCancelled()) {
            throw new CancellationException("CPU 网格任务已过期");
        }
    }

    private static void closeBuilt(Long2ObjectOpenHashMap<BuiltSection> built) {
        RuntimeException failure = null;
        for (BuiltSection mesh : built.values()) {
            try {
                mesh.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        built.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /** worker 结果；只含尚未上传的原生顶点内存，绝不包含 OpenGL 对象。 */
    static final class Result implements AutoCloseable {
        private final Long2ObjectOpenHashMap<BuiltSection> sections;
        private boolean claimed;

        private Result(Long2ObjectOpenHashMap<BuiltSection> sections) {
            this.sections = sections;
        }

        Long2ObjectOpenHashMap<BuiltSection> claimSections() {
            if (claimed) {
                throw new IllegalStateException("CPU 网格结果只能接管一次");
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

    /** 单个 section 的 CPU 顶点数据；上传和关闭由 Render thread 的 staging 阶段负责。 */
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
                throw new IllegalStateException("CPU 网格不能重复上传");
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

    /** 整代 CPU 原生内存超过预算时，调用方改走有界的逐片构建/上传回退。 */
    static final class MeshBudgetExceededException extends RuntimeException {
        private MeshBudgetExceededException() {
            super("CPU 网格候选超过 64 MiB，需使用流式精确回退");
        }
    }

}
