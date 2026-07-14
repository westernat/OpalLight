package org.mesdag.opallight.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.mesdag.opallight.OpalLight;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongToIntFunction;

import static org.mesdag.opallight.light.LightManager.lightMaskShader;

/** 在渲染线程中维护可独立替换的彩色光照网格缓存。 */
public final class LightMaskMeshCache {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final AtomicMeshPublication<RetainedGpuResource<VertexBuffer>.Lease> MESHES =
            new AtomicMeshPublication<>(
            RetainedGpuResource<VertexBuffer>.Lease::close,
            RenderSystem::assertOnRenderThread
    );
    private static final LongOpenHashSet DIRTY_SECTIONS = new LongOpenHashSet();
    private static final RenderUploadBudget RENDER_MESH_BUDGET =
            // 留出调度与循环开销，实测单片才能稳定落在对外 4 ms 门槛内。
            new RenderUploadBudget(3_500_000L, System::nanoTime);
    private static final GenerationCoordinator<MeshRequestKey, MeshBuildSnapshot, MeshBuildSnapshot.Result>
            MESH_COORDINATOR = new GenerationCoordinator<>(
                    "OpalLight-MeshWorker",
                    MeshBuildSnapshot::build,
                    ignored -> { },
                    MeshBuildSnapshot.Result::close
            );
    private static final long GENERATION_CACHE_BYTES = 128L * 1024L * 1024L;
    private static final long STREAMING_CPU_MESH_BYTES = 16L * 1024L * 1024L;
    /**
     * 候选捕获每处理这一批体素才重新读取一次时间预算。逐体素计时会在大范围重建时产生数千次
     * {@code nanoTime} 与循环调度开销；128 个体素仍把最坏工作单元限制在 section 的 1/32，
     * 同时避免重新退化为不可抢占的整 section 扫描。
     */
    private static final int CANDIDATE_VOXELS_PER_BUDGET_CHECK = 128;
    private static final CompositeGenerationStore<RetainedGpuResource<VertexBuffer>.Lease> GENERATIONS =
            new CompositeGenerationStore<>(64L, GENERATION_CACHE_BYTES);
    private static final Reference2ObjectOpenHashMap<BlockState, BakedModel> MODEL_CACHE = new Reference2ObjectOpenHashMap<>();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static Object currentStateToken;
    private static Object requestedStateToken;
    private static boolean requestedStateCacheHit;
    private static LightGeneration<RetainedGpuResource<VertexBuffer>.Lease> requestedGeneration;
    private static RgbLightEngine.ChunkSnapshot requestedCpuState;
    private static LightGeneration.RevisionKey requestedRevisions;
    private static long nextMeshGenerationId = 1L;
    private static long meshRevision;
    private static PendingMeshRebuild pendingMeshRebuild;

    /** worker 结果必须与创建它的全部请求身份一致，generation id 防止同 revision 重用旧结果。 */
    private record MeshRequestKey(
            long meshRevision,
            Object stateToken,
            LightGeneration.RevisionKey revisions,
            RgbLightEngine.ChunkSnapshot cpuState,
            long generationId
    ) {
    }

    private LightMaskMeshCache() {
    }

    public static void markDirty(long sectionKey) {
        DIRTY_SECTIONS.add(sectionKey);
        meshRevision++;
    }

    /**
     * 方块或模型回调到达时立刻使旧快照/worker/staging 过期，但继续显示完整 active 代。
     * 真正受影响的 section 仍由 RGB/ModelData 批处理收敛后通过 {@link #markDirty(long)} 合并，
     * 这里不提前发布也不重复扩散脏集合。
     */
    public static void invalidatePendingBuild() {
        invalidatePendingBuildAndTakeCpuState();
    }

    /**
     * 取消尚未提交的 GPU staging，并把它对应的完整 CPU 候选交还给事件编排器。
     * 调用方必须在新的方块或生命周期 delta 被处理前，用该快照重设 owner 引擎的计算基底。
     */
    static RgbLightEngine.ChunkSnapshot invalidatePendingBuildAndTakeCpuState() {
        RgbLightEngine.ChunkSnapshot cancelledCpuState = pendingMeshRebuild != null
                && pendingMeshRebuild.atomicBulkReplacement
                ? pendingMeshRebuild.cpuState
                : requestedCpuState;
        meshRevision++;
        if (pendingMeshRebuild != null) {
            cancelPendingMeshRebuild();
        }
        if (cancelledCpuState != null) {
            requestedStateToken = currentStateToken;
            requestedStateCacheHit = false;
            requestedCpuState = null;
            requestedRevisions = null;
            closeRequestedGeneration();
        }
        return cancelledCpuState;
    }

    /**
     * 查询一代 CPU/GPU 都有效的精确缓存，并暂存独立 owner 等待下一次 draw 原子采用。
     * 返回 {@code null} 表示只能走 CPU-only 或完整冷重建路径。
     */
    static RgbLightEngine.ChunkSnapshot prepareCachedBulkState(
            Object stateToken,
            LightGeneration.RevisionKey revisions
    ) {
        closeRequestedGeneration();
        LightGeneration<RetainedGpuResource<VertexBuffer>.Lease> hit = GENERATIONS.retainExact(
                stateToken,
                revisions,
                lease -> !lease.value().isInvalid()
        );
        if (hit == null) {
            return null;
        }
        requestedGeneration = hit;
        return hit.cpuState();
    }

    public static void selectBulkState(
            Object stateToken,
            LightGeneration.RevisionKey revisions,
            RgbLightEngine.ChunkSnapshot cpuState,
            boolean cacheHit
    ) {
        requestedStateToken = stateToken;
        requestedStateCacheHit = cacheHit;
        requestedRevisions = revisions;
        requestedCpuState = cpuState;
        if (stateToken == null) {
            currentStateToken = null;
            closeRequestedGeneration();
            requestedRevisions = null;
            requestedCpuState = null;
            GENERATIONS.discardActive();
        } else if (!cacheHit) {
            closeRequestedGeneration();
        } else if (stateToken.equals(currentStateToken)) {
            // CPU 已直接激活同一代；屏幕上的 Atomic map 本来就是目标代，无需再 retain/交换一次。
            closeRequestedGeneration();
            requestedStateCacheHit = false;
            requestedCpuState = null;
            requestedRevisions = null;
        }
    }

    public static void removeChunk(int chunkX, int chunkZ) {
        LongOpenHashSet chunkKeys = new LongOpenHashSet();
        chunkKeys.add(ChunkPos.asLong(chunkX, chunkZ));
        removeChunks(chunkKeys);
    }

    /**
     * 一次遍历移除一批区块，避免区块流送时反复扫描全部网格。
     *
     * <p>{@link #MESHES} 与 {@link #GENERATIONS} 通过独立 lease 共享底层 VBO。
     * 卸载时只关闭实际包含目标区块 section 的 owner；若因视距边缘流送而卸载一个
     * 与当前建筑无关的远端区块，活动 VBO 与状态令牌都应继续保留，否则每次跨区块都会
     * 将本可复用的整建筑网格全量重建。</p>
     *
     * <p>若活动状态确实受影响，必须同时清除 current/requested token，防止一个已经缺失
     * section 的部分 VBO Map 被重新放回批量缓存。</p>
     */
    public static void removeChunks(LongSet chunkKeys) {
        if (chunkKeys.isEmpty()) {
            return;
        }
        if (pendingMeshRebuild != null && pendingMeshRebuild.dependsOnChunks(chunkKeys)) {
            cancelPendingMeshRebuild();
            meshRevision++;
        }
        boolean activeStateAffected = BulkStateDependencies.removedChunksAffectMeshState(
                chunkKeys, new LongOpenHashSet(MESHES.activeSectionKeys())
        );
        for (long sectionKey : MESHES.activeSectionKeys()) {
            long chunkKey = ChunkPos.asLong(
                    PackedPosition.sectionX(sectionKey),
                    PackedPosition.sectionZ(sectionKey)
            );
            if (chunkKeys.contains(chunkKey)) {
                RetainedGpuResource<VertexBuffer>.Lease removed = MESHES.removeActiveForLegacy(sectionKey);
                if (removed != null) {
                    removed.close();
                }
            }
        }
        GENERATIONS.removeCachedIf(generation -> generation.anyGpuMeshMatches(
                (sectionKey, ignored) -> chunkKeys.contains(ChunkPos.asLong(
                        PackedPosition.sectionX(sectionKey), PackedPosition.sectionZ(sectionKey)
                ))
        ));
        if (requestedGeneration != null && requestedGeneration.anyGpuMeshMatches(
                (sectionKey, ignored) -> chunkKeys.contains(ChunkPos.asLong(
                        PackedPosition.sectionX(sectionKey), PackedPosition.sectionZ(sectionKey)
                ))
        )) {
            closeRequestedGeneration();
            requestedStateCacheHit = false;
        }
        DIRTY_SECTIONS.removeIf(sectionKey -> chunkKeys.contains(ChunkPos.asLong(
                PackedPosition.sectionX(sectionKey),
                PackedPosition.sectionZ(sectionKey)
        )));
        if (activeStateAffected) {
            GENERATIONS.discardActive();
            currentStateToken = null;
            requestedStateToken = null;
            requestedStateCacheHit = false;
        }
    }

    public static void draw(Matrix4f viewMatrix, Camera camera, ClientLevel level, RgbLightEngine engine) {
        cancelStaleMeshRebuild();
        activateRequestedState();
        rebuildDirty(level, engine);
        if (MESHES.activeSize() == 0 || lightMaskShader == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        int blockAtlas = minecraft.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
        Vec3 cameraPos = camera.getPosition();
        Frustum frustum = minecraft.levelRenderer.getFrustum();
        lightMaskShader.setSampler("Sampler0", blockAtlas);
        lightMaskShader.MODEL_VIEW_MATRIX.set(MODEL_VIEW.set(viewMatrix).translate(
                (float) -cameraPos.x, (float) -cameraPos.y, (float) -cameraPos.z
        ));
        lightMaskShader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        lightMaskShader.apply();
        try {
            MESHES.forEachActive((sectionKey, lease) -> {
                VertexBuffer buffer = lease.value();
                if (!buffer.isInvalid() && isVisible(sectionKey, frustum)) {
                    buffer.bind();
                    buffer.draw();
                }
            });
        } finally {
            lightMaskShader.clear();
            VertexBuffer.unbind();
        }
    }

    public static void invalidate() {
        cancelPendingMeshRebuild();
        meshRevision++;
        MESHES.reset();
        closeRequestedGeneration();
        GENERATIONS.clear();
        currentStateToken = null;
        requestedStateToken = null;
        requestedStateCacheHit = false;
        requestedCpuState = null;
        requestedRevisions = null;
        DIRTY_SECTIONS.clear();
        MODEL_CACHE.clear();
    }

    private static void activateRequestedState() {
        Object requested = requestedStateToken;
        if (requested == null || requested.equals(currentStateToken)) {
            return;
        }
        long started = System.nanoTime();
        LightGeneration<RetainedGpuResource<VertexBuffer>.Lease> restored = requestedStateCacheHit
                ? requestedGeneration
                : null;
        if (restored != null) {
            long restoredGenerationId = restored.generationId();
            AtomicMeshPublication.OwnedState<RetainedGpuResource<VertexBuffer>.Lease> previous;
            try (AtomicMeshPublication<RetainedGpuResource<VertexBuffer>.Lease>.Stage stage =
                         MESHES.begin(restoredGenerationId)) {
                restored.forEachGpuMesh((sectionKey, lease) ->
                        stage.put(sectionKey, lease.retain(), lease.bytes())
                );
                previous = stage.commit();
            }
            requestedGeneration = null;
            long elapsed = System.nanoTime() - started;
            RuntimeException failure = publishStagedGeneration(restored, requested, previous, true);
            if (isGenerationActive(restoredGenerationId)) {
                OpalLight.LOGGER.debug(
                        "RGB mesh state restore: buffers={}, time={}us",
                        MESHES.activeSize(), elapsed / 1_000L
                );
            }
            rethrow(failure);
            return;
        }

        /*
         * miss 时保持旧 active 完整可见。rebuildDirty 会把目标完整上传到不可见 staging，
         * 只有所有 VBO 成功后才一次交换；这里绝不能提前关闭或搬走旧 map。
         */
        requestedStateCacheHit = false;
    }

    private static void closeRequestedGeneration() {
        if (requestedGeneration != null) {
            requestedGeneration.close();
            requestedGeneration = null;
        }
    }

    private static void rebuildDirty(ClientLevel level, RgbLightEngine engine) {
        if (pendingMeshRebuild == null) {
            if (DIRTY_SECTIONS.isEmpty()) {
                return;
            }
            LongOpenHashSet lightSections = new LongOpenHashSet();
            if (requestedCpuState != null) {
                requestedCpuState.collectLightSectionKeys(lightSections);
            } else {
                engine.collectLightSectionKeys(lightSections);
            }
            pendingMeshRebuild = new PendingMeshRebuild(
                    meshRevision,
                    DIRTY_SECTIONS.toLongArray(),
                    lightSections,
                    BulkMeshPublicationPolicy.requiresAtomicReplacement(
                            requestedStateToken, currentStateToken, requestedStateCacheHit
                    ),
                    requestedStateToken,
                    requestedRevisions,
                    requestedCpuState,
                    nextMeshGenerationId++
            );
        }

        PendingMeshRebuild pending = pendingMeshRebuild;
        if (!pending.matchesCurrentRequest()) {
            cancelPendingMeshRebuild();
            return;
        }
        try {
            if (pending.shouldDrainUploadBeforeCpu()) {
                pending.processUploadSlice();
                return;
            }
            if (!pending.cpuComplete()) {
                pending.processCpuSlice(level, engine);
                if (!pending.cpuComplete()) {
                    return;
                }
            }
            pending.processUploadSlice();
            if (pending.uploadComplete()) {
                pending.finish();
                DIRTY_SECTIONS.clear();
                pendingMeshRebuild = null;
            }
        } catch (RuntimeException | Error failure) {
            cancelPendingMeshRebuild();
            throw failure;
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static void cancelStaleMeshRebuild() {
        if (pendingMeshRebuild != null && !pendingMeshRebuild.matchesCurrentRequest()) {
            cancelPendingMeshRebuild();
        }
    }

    private static void cancelPendingMeshRebuild() {
        MESH_COORDINATOR.invalidate();
        if (pendingMeshRebuild != null) {
            pendingMeshRebuild.close();
            pendingMeshRebuild = null;
        }
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    /**
     * 把已经进入 Atomic map 的完整 GPU 状态与复合 generation、token 统一完成提交。
     *
     * <p>{@link CompositeGenerationStore#publish(LightGeneration)} 在静态校验通过后保证立即接管
     * owner，但后续清理仍可能抛错。因此这里依据 active id 判断提交是否已发生：已提交就先完成
     * token/请求簿记并关闭旧 Atomic owner；未提交则把旧 map 原子放回并关闭失败的新代。</p>
     */
    private static RuntimeException publishStagedGeneration(
            LightGeneration<RetainedGpuResource<VertexBuffer>.Lease> generation,
            Object stateToken,
            AtomicMeshPublication.OwnedState<RetainedGpuResource<VertexBuffer>.Lease> previous,
            boolean cacheHit
    ) {
        long generationId = generation.generationId();
        RuntimeException failure = null;
        long activeGpuGenerationId = MESHES.activeGenerationId();
        if (activeGpuGenerationId != generationId) {
            failure = new IllegalStateException(
                    "CPU/GPU generation 不一致: cpu=" + generationId + ", gpu=" + activeGpuGenerationId
            );
        } else {
            try {
                GENERATIONS.publish(generation);
            } catch (RuntimeException publishFailure) {
                failure = publishFailure;
            }
        }

        if (isGenerationActive(generationId)) {
            try {
                LightManager.activatePublishedCpuState(generation.cpuState(), generationId);
                currentStateToken = stateToken;
                requestedStateCacheHit = false;
                requestedCpuState = null;
                requestedRevisions = null;
                if (cacheHit) {
                    DIRTY_SECTIONS.clear();
                }
            } catch (RuntimeException activationFailure) {
                failure = mergeFailures(failure, activationFailure);
            } finally {
                try {
                    previous.close();
                } catch (RuntimeException closeFailure) {
                    failure = mergeFailures(failure, closeFailure);
                }
            }
            return failure;
        }

        try {
            AtomicMeshPublication.OwnedState<RetainedGpuResource<VertexBuffer>.Lease> failedNew =
                    MESHES.adopt(previous);
            failedNew.close();
        } catch (RuntimeException rollbackFailure) {
            failure = mergeFailures(failure, rollbackFailure);
        }
        try {
            generation.close();
        } catch (RuntimeException closeFailure) {
            failure = mergeFailures(failure, closeFailure);
        }
        requestedStateToken = currentStateToken;
        requestedStateCacheHit = false;
        requestedCpuState = null;
        requestedRevisions = null;
        return failure;
    }

    private static boolean isGenerationActive(long generationId) {
        return GENERATIONS.activeGenerationId() == generationId;
    }

    private static RuntimeException mergeFailures(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrow(RuntimeException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    private static MeshBuildSnapshot.BuiltSection buildSection(
            ClientLevel level,
            long sectionKey,
            LongToIntFunction lightLookup
    ) {
        int sectionX = PackedPosition.sectionX(sectionKey);
        int sectionY = PackedPosition.sectionY(sectionKey);
        int sectionZ = PackedPosition.sectionZ(sectionKey);
        if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
            return null;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }
        LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
        if (section.hasOnlyAir()) {
            return null;
        }

        ByteBufferBuilder bytes = new ByteBufferBuilder(65_536);
        boolean transferred = false;
        try {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            BlockModelShaper shaper = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper();
            RandomSource random = RandomSource.create(0L);
            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
            int[] neighborLights = new int[PackedPosition.DIRECTION_COUNT];
            int originX = sectionX << 4;
            int originY = sectionY << 4;
            int originZ = sectionZ << 4;

            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                    BlockState state = section.getBlockState(localX, localY, localZ);
                    if (state.isAir()) {
                        continue;
                    }
                    int worldX = originX + localX;
                    int worldY = originY + localY;
                    int worldZ = originZ + localZ;
                    blockPos.set(worldX, worldY, worldZ);
                    if (LightManager.packedEmission(level, blockPos, state) != 0) {
                        continue;
                    }

                    long packedPos = PackedPosition.pack(worldX, worldY, worldZ);
                    int unculledLight = LightMaskMeshPrefilter.sampleNeighborLights(
                            lightLookup, packedPos, neighborLights
                    );
                    if (unculledLight == 0) {
                        // 六邻全部无彩光时不可能生成遮罩顶点，禁止触碰模型和 ModelData。
                        continue;
                    }
                    BakedModel model = MODEL_CACHE.computeIfAbsent(state, shaper::getBlockModel);
                    /*
                     * level 只提供方块实体的原始 ModelData；BakedModel 还可以根据世界、位置和
                     * BlockState 派生最终数据。必须与原版 SectionCompiler 保持相同的两步调用，
                     * 否则连接模型或动态材质在彩光遮罩中会使用错误几何，并留下残影。
                     */
                    ModelData modelData = level.getModelData(blockPos);
                    modelData = model.getModelData(level, blockPos, state, modelData);
                    long seed = state.getSeed(blockPos);

                    for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
                        Direction direction = DIRECTIONS[directionIndex];
                        long neighborPacked = PackedPosition.offset(packedPos, directionIndex);
                        int light = neighborLights[directionIndex];
                        if (light == 0) {
                            continue;
                        }
                        neighborPos.set(neighborPacked);
                        if (!Block.shouldRenderFace(state, level, blockPos, direction, neighborPos)) {
                            continue;
                        }
                        random.setSeed(seed);
                        for (BakedQuad quad : model.getQuads(state, direction, random, modelData, null)) {
                            renderQuad(builder, quad, worldX, worldY, worldZ, light);
                        }
                    }

                    if (unculledLight != 0) {
                        random.setSeed(seed);
                        for (BakedQuad quad : model.getQuads(state, null, random, modelData, null)) {
                            renderQuad(builder, quad, worldX, worldY, worldZ, unculledLight);
                        }
                    }
                    }
                }
            }

            MeshData mesh = builder.build();
            if (mesh == null) {
                return null;
            }
            MeshBuildSnapshot.BuiltSection result = new MeshBuildSnapshot.BuiltSection(mesh, bytes);
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                bytes.close();
            }
        }
    }

    private static void renderQuad(BufferBuilder builder, BakedQuad quad, int worldX, int worldY, int worldZ, int light) {
        int[] vertices = quad.getVertices();
        float red = PackedLight.red(light) / 15.0F;
        float green = PackedLight.green(light) / 15.0F;
        float blue = PackedLight.blue(light) / 15.0F;
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            float x = Float.intBitsToFloat(vertices[offset]);
            float y = Float.intBitsToFloat(vertices[offset + 1]);
            float z = Float.intBitsToFloat(vertices[offset + 2]);
            float u = Float.intBitsToFloat(vertices[offset + 4]);
            float v = Float.intBitsToFloat(vertices[offset + 5]);
            builder.addVertex(worldX + x, worldY + y, worldZ + z)
                    .setColor(red, green, blue, 1.0F)
                    .setUv(u, v);
        }
    }

    private static boolean isVisible(long sectionKey, Frustum frustum) {
        if (frustum == null) {
            return true;
        }
        int minX = PackedPosition.sectionX(sectionKey) << 4;
        int minY = PackedPosition.sectionY(sectionKey) << 4;
        int minZ = PackedPosition.sectionZ(sectionKey) << 4;
        return frustum.isVisible(new AABB(minX, minY, minZ, minX + 16, minY + 16, minZ + 16));
    }

    /**
     * 跨帧保存一次完整目标网格。CPU 构建结果和 GPU staging 在提交前都不可见；任何新 revision
     * 到达都会关闭本对象并从仍保留的 DIRTY_SECTIONS 重新开始，因此不会发布半旧半新的代。
     */
    private static final class PendingMeshRebuild implements AutoCloseable {
        private final long revision;
        private final long[] sections;
        private final LongOpenHashSet dirtySectionSet;
        private final LongOpenHashSet lightSections;
        private final boolean atomicBulkReplacement;
        private final Object stateToken;
        private final LightGeneration.RevisionKey revisions;
        private final RgbLightEngine.ChunkSnapshot cpuState;
        private final long generationId;
        private final MeshRequestKey requestKey;
        private final long startedNanos = System.nanoTime();
        private Long2ObjectOpenHashMap<MeshBuildSnapshot.BuiltSection> built =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<RetainedGpuResource<VertexBuffer>.Lease> generationResources =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> copiedSections =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<List<MeshBuildSnapshot.Candidate>> capturedCandidates =
                new Long2ObjectOpenHashMap<>();
        private final long[] captureSections;
        private CpuPhase cpuPhase;
        private int captureSectionIndex;
        private int candidateSectionIndex;
        private int candidateVoxelIndex;
        private int fallbackSectionIndex;
        private int prefilteredSections;
        private long meshCpuNanos;
        private long workerMeshNanos;
        private long maxCpuSliceNanos;
        private int cpuSliceCount;
        private long uploadNanos;
        private long maxUploadSliceNanos;
        private int uploadSliceCount;
        private AtomicMeshPublication<RetainedGpuResource<VertexBuffer>.Lease>.Stage stage;
        private long[] activeKeys;
        private final LongArrayList uploadKeys = new LongArrayList();
        private int activeIndex;
        private int uploadIndex;
        private long gpuBytes;
        private long pendingCpuBytes;
        private int nonEmptySections;
        private boolean finished;
        private PalettedContainer<BlockState> candidateStates;
        private List<MeshBuildSnapshot.Candidate> currentCandidates;
        private BlockModelShaper candidateShaper;
        private final BlockPos.MutableBlockPos candidateBlockPos = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos candidateNeighborPos = new BlockPos.MutableBlockPos();
        private final int[] candidateNeighborLights = new int[PackedPosition.DIRECTION_COUNT];

        private PendingMeshRebuild(
                long revision,
                long[] sections,
                LongOpenHashSet lightSections,
                boolean atomicBulkReplacement,
                Object stateToken,
                LightGeneration.RevisionKey revisions,
                RgbLightEngine.ChunkSnapshot cpuState,
                long generationId
        ) {
            this.revision = revision;
            this.sections = sections;
            this.dirtySectionSet = new LongOpenHashSet(sections);
            this.lightSections = lightSections;
            this.atomicBulkReplacement = atomicBulkReplacement;
            this.stateToken = stateToken;
            this.revisions = revisions;
            this.cpuState = cpuState;
            this.generationId = generationId;
            this.requestKey = new MeshRequestKey(revision, stateToken, revisions, cpuState, generationId);
            // 面可见性已经在渲染线程直接查询真实世界；worker 不再读取邻居调色板，复制六邻域只会
            // 放大主线程快照成本和内存，因此这里只冻结真正会扫描的目标 section。
            this.captureSections = sections.clone();
            this.cpuPhase = cpuState != null && sections.length > 16
                    ? CpuPhase.CAPTURE_SECTIONS
                    : CpuPhase.FALLBACK;
        }

        private boolean matchesCurrentRequest() {
            return revision == meshRevision
                    && Objects.equals(stateToken, requestedStateToken)
                    && Objects.equals(revisions, requestedRevisions)
                    && cpuState == requestedCpuState;
        }

        private boolean cpuComplete() {
            return cpuPhase == CpuPhase.COMPLETE;
        }

        /**
         * 精确回退采用“先保留旧 active，再构建一片、上传一片”的流水线。stage 在最终提交前
         * 仍然不可见，所以降低 CPU 原生内存峰值不会重新引入分片闪烁。
         */
        private boolean shouldDrainUploadBeforeCpu() {
            return cpuPhase == CpuPhase.FALLBACK && (stage == null || hasUploadWork());
        }

        /** 卸载目标区块或其水平邻居会改变边界面；更远的流送事件与本次快照无关。 */
        private boolean dependsOnChunks(LongSet chunkKeys) {
            for (long sectionKey : sections) {
                int sectionX = PackedPosition.sectionX(sectionKey);
                int sectionZ = PackedPosition.sectionZ(sectionKey);
                if (chunkKeys.contains(ChunkPos.asLong(sectionX, sectionZ))
                        || chunkKeys.contains(ChunkPos.asLong(sectionX - 1, sectionZ))
                        || chunkKeys.contains(ChunkPos.asLong(sectionX + 1, sectionZ))
                        || chunkKeys.contains(ChunkPos.asLong(sectionX, sectionZ - 1))
                        || chunkKeys.contains(ChunkPos.asLong(sectionX, sectionZ + 1))) {
                    return true;
                }
            }
            return false;
        }

        private void processCpuSlice(ClientLevel level, RgbLightEngine engine) {
            if (cpuPhase == CpuPhase.WAITING_WORKER) {
                pollWorker();
                return;
            }
            RenderUploadBudget.Slice slice = switch (cpuPhase) {
                case CAPTURE_SECTIONS -> RENDER_MESH_BUDGET.run(
                        () -> cpuPhase == CpuPhase.CAPTURE_SECTIONS
                                && captureSectionIndex < captureSections.length,
                        () -> captureNextSection(level)
                );
                case CAPTURE_CANDIDATES -> RENDER_MESH_BUDGET.run(
                        () -> cpuPhase == CpuPhase.CAPTURE_CANDIDATES
                                && candidateSectionIndex < sections.length,
                        () -> captureNextCandidateBatch(level)
                );
                case FALLBACK -> RENDER_MESH_BUDGET.run(
                        () -> fallbackSectionIndex < sections.length
                                && pendingCpuBytes < STREAMING_CPU_MESH_BYTES,
                        () -> buildNextFallbackSection(level, engine)
                );
                case WAITING_WORKER, COMPLETE -> throw new IllegalStateException("CPU 网格阶段状态错误");
            };
            meshCpuNanos = saturatedAdd(meshCpuNanos, slice.elapsedNanos());
            maxCpuSliceNanos = Math.max(maxCpuSliceNanos, slice.elapsedNanos());
            cpuSliceCount++;
            advanceCpuPhase(level);
        }

        private void captureNextSection(ClientLevel level) {
            long sectionKey = captureSections[captureSectionIndex++];
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
                return;
            }
            LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                return;
            }
            LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
            section.acquire();
            try {
                copiedSections.put(sectionKey, section.getStates().copy());
            } finally {
                section.release();
            }
        }

        private void captureNextCandidateBatch(ClientLevel level) {
            for (int index = 0;
                 index < CANDIDATE_VOXELS_PER_BUDGET_CHECK && candidateSectionIndex < sections.length;
                 index++) {
                captureNextCandidateVoxel(level);
            }
        }

        private void captureNextCandidateVoxel(ClientLevel level) {
            long sectionKey = sections[candidateSectionIndex];
            if (candidateStates == null) {
                if (!LightMaskMeshPrefilter.sectionCanContainLitSurface(lightSections, sectionKey)) {
                    prefilteredSections++;
                    candidateSectionIndex++;
                    return;
                }
                candidateStates = copiedSections.get(sectionKey);
                if (candidateStates == null) {
                    candidateSectionIndex++;
                    return;
                }
                currentCandidates = new ArrayList<>();
                candidateVoxelIndex = 0;
            }

            // 游标以体素为单位保存，所以即使预算在批次之间耗尽，下一帧也会从同一 section 精确续跑。
            int voxelIndex = candidateVoxelIndex++;
            int localX = voxelIndex & 15;
            int localZ = (voxelIndex >>> 4) & 15;
            int localY = (voxelIndex >>> 8) & 15;
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            int originX = sectionX << 4;
            int originY = sectionY << 4;
            int originZ = sectionZ << 4;
            if (candidateShaper == null) {
                candidateShaper = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper();
            }
            try {
                        BlockState state = candidateStates.get(localX, localY, localZ);
                        if (state.isAir()) {
                            return;
                        }
                        int worldX = originX + localX;
                        int worldY = originY + localY;
                        int worldZ = originZ + localZ;
                        long packedPos = PackedPosition.pack(worldX, worldY, worldZ);
                        if (cpuState.directEmissionAt(packedPos) != 0) {
                            return;
                        }
                        int unculledLight = LightMaskMeshPrefilter.sampleNeighborLights(
                                cpuState::lightAt, packedPos, candidateNeighborLights
                        );
                        if (unculledLight == 0) {
                            return;
                        }
                        candidateBlockPos.set(worldX, worldY, worldZ);
                        BakedModel model = MODEL_CACHE.computeIfAbsent(state, candidateShaper::getBlockModel);
                        ModelData modelData = level.getModelData(candidateBlockPos);
                        modelData = model.getModelData(level, candidateBlockPos, state, modelData);
                        boolean[] visibleFaces = new boolean[PackedPosition.DIRECTION_COUNT];
                        for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
                            if (candidateNeighborLights[directionIndex] == 0) {
                                continue;
                            }
                            candidateNeighborPos.set(PackedPosition.offset(packedPos, directionIndex));
                            visibleFaces[directionIndex] = Block.shouldRenderFace(
                                    state,
                                    level,
                                    candidateBlockPos,
                                    DIRECTIONS[directionIndex],
                                    candidateNeighborPos
                            );
                        }
                        currentCandidates.add(new MeshBuildSnapshot.Candidate(
                                packedPos,
                                state,
                                model,
                                modelData,
                                state.getSeed(candidateBlockPos),
                                candidateNeighborLights,
                                visibleFaces,
                                unculledLight
                        ));
            } finally {
                if (candidateVoxelIndex == 4096) {
                    if (!currentCandidates.isEmpty()) {
                        capturedCandidates.put(sectionKey, currentCandidates);
                    }
                    candidateStates = null;
                    currentCandidates = null;
                    candidateVoxelIndex = 0;
                    candidateSectionIndex++;
                }
            }
        }

        private void buildNextFallbackSection(ClientLevel level, RgbLightEngine engine) {
            long sectionKey = sections[fallbackSectionIndex++];
            if (!LightMaskMeshPrefilter.sectionCanContainLitSurface(lightSections, sectionKey)) {
                prefilteredSections++;
                return;
            }
            LongToIntFunction lightLookup = cpuState == null ? engine::getLight : cpuState::lightAt;
            MeshBuildSnapshot.BuiltSection mesh = buildSection(level, sectionKey, lightLookup);
            if (mesh != null) {
                enqueueBuilt(sectionKey, mesh);
            }
        }

        private void advanceCpuPhase(ClientLevel level) {
            if (cpuPhase == CpuPhase.CAPTURE_SECTIONS
                    && captureSectionIndex == captureSections.length) {
                cpuPhase = CpuPhase.CAPTURE_CANDIDATES;
            }
            if (cpuPhase == CpuPhase.CAPTURE_CANDIDATES
                    && candidateSectionIndex == sections.length) {
                MeshBuildSnapshot snapshot = new MeshBuildSnapshot(
                        capturedCandidates,
                        sections
                );
                copiedSections.clear();
                capturedCandidates.clear();
                MESH_COORDINATOR.submit(requestKey, snapshot);
                cpuPhase = CpuPhase.WAITING_WORKER;
            }
            if (cpuPhase == CpuPhase.FALLBACK && fallbackSectionIndex == sections.length) {
                cpuPhase = CpuPhase.COMPLETE;
            }
        }

        private void pollWorker() {
            Optional<Throwable> failure = MESH_COORDINATOR.takeFailure();
            if (failure.isPresent()) {
                OpalLight.LOGGER.warn("异步 CPU 网格构建失败，回退到精确渲染线程路径", failure.orElseThrow());
                switchToFallback();
                return;
            }
            Optional<GenerationCoordinator.Completed<MeshRequestKey, MeshBuildSnapshot.Result>> completed =
                    MESH_COORDINATOR.poll(requestKey);
            if (completed.isEmpty()) {
                return;
            }
            GenerationCoordinator.Completed<MeshRequestKey, MeshBuildSnapshot.Result> candidate =
                    completed.orElseThrow();
            try (MeshBuildSnapshot.Result result = candidate.value()) {
                // 直接接管整张 map，避免 putAll 扩容失败后出现“结果已 claim、owner 尚未接管”的窗口。
                built = result.claimSections();
                for (long sectionKey : built.keySet()) {
                    MeshBuildSnapshot.BuiltSection mesh = built.get(sectionKey);
                    uploadKeys.add(sectionKey);
                    pendingCpuBytes = saturatedAdd(pendingCpuBytes, mesh.gpuBytes());
                    nonEmptySections++;
                }
            }
            workerMeshNanos = candidate.workerNanos();
            cpuPhase = CpuPhase.COMPLETE;
        }

        private void switchToFallback() {
            MESH_COORDINATOR.invalidate();
            copiedSections.clear();
            capturedCandidates.clear();
            candidateStates = null;
            currentCandidates = null;
            candidateVoxelIndex = 0;
            fallbackSectionIndex = 0;
            prefilteredSections = 0;
            cpuPhase = CpuPhase.FALLBACK;
        }

        private void enqueueBuilt(long sectionKey, MeshBuildSnapshot.BuiltSection mesh) {
            built.put(sectionKey, mesh);
            uploadKeys.add(sectionKey);
            pendingCpuBytes = saturatedAdd(pendingCpuBytes, mesh.gpuBytes());
            nonEmptySections++;
        }

        private enum CpuPhase {
            CAPTURE_SECTIONS,
            CAPTURE_CANDIDATES,
            WAITING_WORKER,
            FALLBACK,
            COMPLETE
        }

        private void processUploadSlice() {
            initializeUpload();
            RenderUploadBudget.Slice slice = RENDER_MESH_BUDGET.run(this::hasUploadWork, this::uploadNext);
            uploadNanos = saturatedAdd(uploadNanos, slice.elapsedNanos());
            maxUploadSliceNanos = Math.max(maxUploadSliceNanos, slice.elapsedNanos());
            uploadSliceCount++;
        }

        private void initializeUpload() {
            if (stage != null) {
                return;
            }
            stage = MESHES.begin(generationId);
            activeKeys = MESHES.activeSectionKeys();
        }

        private boolean hasUploadWork() {
            return activeIndex < activeKeys.length || uploadIndex < uploadKeys.size();
        }

        private void uploadNext() {
            if (activeIndex < activeKeys.length) {
                long sectionKey = activeKeys[activeIndex++];
                if (dirtySectionSet.contains(sectionKey)) {
                    return;
                }
                RetainedGpuResource<VertexBuffer>.Lease activeOwner = MESHES.active(sectionKey);
                RetainedGpuResource<VertexBuffer>.Lease stagingOwner = activeOwner.retain();
                stage.put(sectionKey, stagingOwner, stagingOwner.bytes());
                if (atomicBulkReplacement) {
                    generationResources.put(sectionKey, activeOwner.retain());
                    gpuBytes = saturatedAdd(gpuBytes, activeOwner.bytes());
                }
                return;
            }

            long sectionKey = uploadKeys.getLong(uploadIndex++);
            MeshBuildSnapshot.BuiltSection mesh = built.remove(sectionKey);
            if (mesh == null) {
                throw new IllegalStateException("待上传 CPU 网格缺失");
            }
            pendingCpuBytes = Math.max(0L, pendingCpuBytes - mesh.gpuBytes());
            VertexBuffer buffer = null;
            RetainedGpuResource<VertexBuffer>.Lease stagingOwner = null;
            try {
                buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                mesh.upload(buffer);
                stagingOwner = RetainedGpuResource.create(
                        buffer,
                        mesh.gpuBytes(),
                        VertexBuffer::close,
                        RenderSystem::assertOnRenderThread
                );
                buffer = null;
                stage.put(sectionKey, stagingOwner, stagingOwner.bytes());
                if (atomicBulkReplacement) {
                    generationResources.put(sectionKey, stagingOwner.retain());
                    gpuBytes = saturatedAdd(gpuBytes, stagingOwner.bytes());
                }
                stagingOwner = null;
            } finally {
                if (stagingOwner != null) {
                    stagingOwner.close();
                }
                if (buffer != null) {
                    buffer.close();
                }
                mesh.close();
            }
        }

        private boolean uploadComplete() {
            return stage != null && !hasUploadWork();
        }

        private void finish() {
            AtomicMeshPublication.OwnedState<RetainedGpuResource<VertexBuffer>.Lease> previous;
            if (atomicBulkReplacement) {
                GpuMeshSet<RetainedGpuResource<VertexBuffer>.Lease> gpuMeshes = null;
                GpuMeshSet<RetainedGpuResource<VertexBuffer>.Lease>.Lease gpuLease = null;
                LightGeneration<RetainedGpuResource<VertexBuffer>.Lease> candidate = null;
                boolean resourcesTransferred = false;
                boolean publicationOwnsCandidate = false;
                Throwable primaryFailure = null;
                try {
                    gpuMeshes = new GpuMeshSet<>(
                            generationResources,
                            gpuBytes,
                            RetainedGpuResource<VertexBuffer>.Lease::close,
                            RenderSystem::assertOnRenderThread
                    );
                    // GpuMeshSet 构造完成后才转移 map 内 owner，构造失败仍由 PendingMeshRebuild.close 收尾。
                    generationResources.clear();
                    resourcesTransferred = true;
                    gpuLease = gpuMeshes.acquire();
                    candidate = new LightGeneration<>(
                            generationId,
                            stateToken,
                            revisions,
                            cpuState,
                            gpuLease,
                            true
                    );
                    gpuLease = null;
                    previous = stage.commit();
                    stage = null;
                    // 此调用无论成功还是回滚都会消费 candidate；必须在进入调用前转移所有权，
                    // 否则 publish 已成功但后续 CPU 激活或簿记异常时，finally 会再次关闭 active owner。
                    publicationOwnsCandidate = true;
                    LightGeneration<RetainedGpuResource<VertexBuffer>.Lease> publicationCandidate = candidate;
                    candidate = null;
                    RuntimeException failure = publishStagedGeneration(
                            publicationCandidate,
                            stateToken,
                            previous,
                            false
                    );
                    rethrow(failure);
                } catch (RuntimeException | Error failure) {
                    primaryFailure = failure;
                    throw failure;
                } finally {
                    RuntimeException cleanupFailure = null;
                    if (!publicationOwnsCandidate && candidate != null) {
                        try {
                            candidate.close();
                        } catch (RuntimeException failure) {
                            cleanupFailure = failure;
                        }
                    } else if (gpuLease != null) {
                        try {
                            gpuLease.close();
                        } catch (RuntimeException failure) {
                            cleanupFailure = failure;
                        }
                    }
                    if (!publicationOwnsCandidate && resourcesTransferred && gpuMeshes != null) {
                        try {
                            gpuMeshes.discardUnleased();
                        } catch (RuntimeException failure) {
                            cleanupFailure = mergeFailures(cleanupFailure, failure);
                        }
                    }
                    if (cleanupFailure != null) {
                        if (primaryFailure != null) {
                            primaryFailure.addSuppressed(cleanupFailure);
                        } else {
                            throw cleanupFailure;
                        }
                    }
                }
            } else {
                previous = stage.commit();
                stage = null;
                GENERATIONS.discardActive();
                previous.close();
            }
            finished = true;
            closeBuiltMeshes();
            long elapsed = System.nanoTime() - startedNanos;
            if (sections.length > 16 || elapsed > 2_000_000L) {
                OpalLight.LOGGER.debug(
                        "RGB mesh staged rebuild: dirty={}, prefiltered={}, nonEmpty={}, buffers={}, atomic={}, "
                                + "cpuTotal={}us, cpuMaxSlice={}us, cpuSliceCount={}, "
                                + "worker={}us, uploadTotal={}us, uploadMaxSlice={}us, "
                                + "uploadSliceCount={}, elapsed={}us",
                        sections.length, prefilteredSections, nonEmptySections, MESHES.activeSize(),
                        atomicBulkReplacement,
                        meshCpuNanos / 1_000L, maxCpuSliceNanos / 1_000L, cpuSliceCount,
                        workerMeshNanos / 1_000L,
                        uploadNanos / 1_000L, maxUploadSliceNanos / 1_000L, uploadSliceCount,
                        elapsed / 1_000L
                );
            }
        }

        @Override
        public void close() {
            if (finished) {
                return;
            }
            finished = true;
            RuntimeException failure = null;
            if (stage != null) {
                try {
                    stage.close();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                stage = null;
            }
            for (RetainedGpuResource<VertexBuffer>.Lease owner : generationResources.values()) {
                try {
                    owner.close();
                } catch (RuntimeException exception) {
                    failure = mergeFailures(failure, exception);
                }
            }
            generationResources.clear();
            try {
                closeBuiltMeshes();
            } catch (RuntimeException exception) {
                failure = mergeFailures(failure, exception);
            }
            rethrow(failure);
        }

        private void closeBuiltMeshes() {
            RuntimeException failure = null;
            for (MeshBuildSnapshot.BuiltSection mesh : built.values()) {
                try {
                    mesh.close();
                } catch (RuntimeException exception) {
                    failure = mergeFailures(failure, exception);
                }
            }
            built.clear();
            rethrow(failure);
        }
    }

}
