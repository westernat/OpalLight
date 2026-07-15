package org.mesdag.opallight.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LightEngine;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.mesdag.opallight.OpalLight;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    static @Nullable ShaderInstance lightMaskShader;
    private static volatile RenderType lightMask;

    private static RenderType lightMask() {
        RenderType mask = lightMask;
        if (mask == null) {
            synchronized (LightManager.class) {
                mask = lightMask;
                if (mask == null) {
                    mask = RenderType.create(
                            "opallight_light_mask",
                            DefaultVertexFormat.POSITION_TEX_COLOR,
                            VertexFormat.Mode.QUADS,
                            256,
                            false,
                            true,
                            RenderType.CompositeState.builder()
                                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> lightMaskShader))
                                    .setTextureState(RenderType.BLOCK_SHEET)
                                    .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
                                    .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                                    .setWriteMaskState(RenderType.COLOR_WRITE)
                                    .setLayeringState(new RenderStateShard.LayeringStateShard(
                                            "opallight_polygon_offset",
                                            () -> {
                                                RenderSystem.polygonOffset(-1.0F, -1.0F);
                                                RenderSystem.enablePolygonOffset();
                                            },
                                            () -> {
                                                RenderSystem.polygonOffset(0.0F, 0.0F);
                                                RenderSystem.disablePolygonOffset();
                                            }
                                    ))
                                    .createCompositeState(false)
                    );
                    lightMask = mask;
                }
            }
        }
        return mask;
    }

    private static final Direction[] DIRECTIONS = Direction.values();
    // 区块流会在极短时间内分批到达；按真实时间合并，避免高帧率下过早提交半批数据。
    private static final long CHUNK_LIFECYCLE_QUIET_NANOS = 50_000_000L;
    private static final long CHUNK_LIFECYCLE_MAX_WAIT_NANOS = 500_000_000L;
    private static final long BULK_UPDATE_QUIET_NANOS = 8_000_000L;
    private static final long BULK_UPDATE_MAX_WAIT_NANOS = 50_000_000L;
    private static final long OWNER_PROPAGATION_SLICE_NANOS = 8_000_000L;
    private static final int MAX_ASYNC_SNAPSHOT_SECTIONS = 1_024;
    private static final Map<BlockState, OpalColor> COLOR_CACHE = new Reference2ObjectOpenHashMap<>();
    private static final RgbLightEngine ENGINE = new RgbLightEngine();
    /*
     * 光源注册表是当前加载域的精确索引，不是缓存。它不设置条目上限；缓存预算耗尽时
     * 最多丢失加速机会，绝不能从这里裁掉真实光源。bulk identity 只冻结这个索引，
     * 不再为每次 4096/65536 更新重新扫描整个客户端视距。
     */
    private static final SourceRegistry SOURCE_REGISTRY = new SourceRegistry();
    private static final GenerationCoordinator<RgbGenerationKey, AsyncRgbTask, AsyncRgbResult> RGB_COORDINATOR =
            new GenerationCoordinator<>(
                    "OpalLight-RgbWorker",
                    (task, cancellation) -> new AsyncRgbResult(
                            RgbLightEngine.buildCandidate(
                                    task.pendingWork, task.worldSnapshot, cancellation::isCancelled
                            ),
                            task.bulkIdentity,
                            task.generationRevisions,
                            task.identityNanos,
                            task.worldSnapshot.captureNanos()
                    )
            );
    private static final LongOpenHashSet LOADED_CHUNKS = new LongOpenHashSet();
    private static final LongOpenHashSet PENDING_CHUNK_SCANS = new LongOpenHashSet();
    private static final LongOpenHashSet PENDING_CHUNK_UNLOADS = new LongOpenHashSet();
    private static final Long2ObjectOpenHashMap<RgbLightEngine.ChunkSnapshot> PENDING_CHUNK_RESTORES = new Long2ObjectOpenHashMap<>();
    /*
     * 区块快照只是流式加载的加速层，不是光照主存储。双预算约束最坏内存：
     * 512 防止大量空区块留下 Map 元数据，2048 个权重单位约对应 16 MiB short[] 净数据；
     * 实际堆占用还包含 section 对象、Map 节点与快照元数据。
     * 淘汰只会让该区块下次重新扫描，不会限制世界规模或彩色光源数量。
     */
    private static final WeightedLruCache<Long, SoftReference<CachedChunk>> CHUNK_SNAPSHOTS =
            new WeightedLruCache<>(512, 2_048);
    private static final Long2LongOpenHashMap PENDING_CHUNK_FINGERPRINTS = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap LOADED_CHUNK_FINGERPRINTS = new Long2LongOpenHashMap();
    /*
     * 批量 generation 的 CPU 快照使用强引用字节预算。SoftReference 会在内存压力或 GC
     * 时无预警丢失“刚离开的稳定布局”，而固定保留两项又会让第三种常见布局必然抖动。
     * 这里的条目上限只防御大量极小 identity 的元数据膨胀；128 MiB 字节预算才是主要
     * 约束。淘汰的始终是可重算快照，不会删除 SourceRegistry 中任何真实光源。
     */
    private static final long BULK_STATE_CACHE_BYTES = 128L * 1024L * 1024L;
    private static final WeightedLruCache<BulkStateIdentity, CachedBulkState> BULK_STATE_SNAPSHOTS =
            new WeightedLruCache<>(64L, BULK_STATE_CACHE_BYTES);
    private static @Nullable ClientLevel activeLevel;
    private static @Nullable ClientLevelAccess access;
    private static volatile boolean fullRebuildRequested = true;
    /*
     * ModelData 可以在 BlockState 不变时改变模型。该标记让下一帧废弃旧 VBO 状态令牌，
     * 但不把它伪装成光照变化，因此不会启动 RGB 衰减/增强队列。
     */
    private static boolean pendingVisualOnlyChange;
    private static long lifecycleBatchStartedNanos;
    private static long lastLifecycleChangeNanos;
    private static long bulkBatchStartedNanos;
    private static long lastBulkChangeNanos;
    private static long contentRevision;
    private static long loadedIdentityRevision;
    private static long resourceRevision;
    private static long propagationRevision;
    private static long visualRevision;
    private static long levelSessionRevision;
    private static long chunkLifecycleRevision;
    private static @Nullable RgbGenerationKey submittedAsyncRgbKey;
    private static @Nullable RgbGenerationKey failedAsyncRgbKey;
    private static @Nullable SlicedRgbFallback slicedRgbFallback;
    private static int pendingSnapshotHits;
    private static int pendingSnapshotMisses;
    private static long pendingSnapshotCaptureNanos;
    private static long pendingSnapshotValidationNanos;

    private LightManager() {
    }

    @SuppressWarnings("deprecation")
    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(
                event.getResourceProvider(),
                // VulkanMod 仍通过旧的字符串资源名接管 shader；ResourceLocation 重载会绕开它。
                "opallight:light_mask",
                DefaultVertexFormat.POSITION_TEX_COLOR
        ), shader -> lightMaskShader = shader);
    }

    @SubscribeEvent
    public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((barrier, manager, preparationProfiler, reloadProfiler, backgroundExecutor, gameExecutor) ->
                LightDataLoader.INSTANCE.reload(
                        barrier, manager, preparationProfiler, reloadProfiler, backgroundExecutor, gameExecutor
                ).thenRunAsync(() -> {
                    COLOR_CACHE.clear();
                    SOURCE_REGISTRY.clear();
                    CHUNK_SNAPSHOTS.clear();
                    BULK_STATE_SNAPSHOTS.clear();
                    contentRevision++;
                    resourceRevision++;
                    fullRebuildRequested = true;
                    RGB_COORDINATOR.invalidate();
                    submittedAsyncRgbKey = null;
                    failedAsyncRgbKey = null;
                    LightMaskMeshCache.invalidate();
                }, gameExecutor)
        );
    }

    public static void blockChanged(
            ClientLevel level,
            BlockPos pos,
            BlockState previousState,
            BlockState currentState
    ) {
        ensureLevel(level);
        // 不能等 RGB 合批完成后才取消旧 mesh；否则旧 staging 可能用新几何提交旧光场。
        rebaseCancelledMeshCpuState();
        contentRevision++;
        markBulkContentChanged();
        int previousEmission = packedEmission(level, pos, previousState);
        int currentEmission = packedEmission(level, pos, currentState);
        if (isIncludedFromLightMesh(previousState, previousEmission)
                || isIncludedFromLightMesh(currentState, currentEmission)) {
            visualRevision++;
        }
        if (propagationSignature(level, pos, previousState) != propagationSignature(level, pos, currentState)) {
            propagationRevision++;
        }
        long packedPos = pos.asLong();
        SOURCE_REGISTRY.put(packedPos, currentEmission);
        long chunkKey = ChunkPos.asLong(PackedPosition.x(packedPos) >> 4, PackedPosition.z(packedPos) >> 4);
        updateFingerprint(LOADED_CHUNK_FINGERPRINTS, chunkKey, level, packedPos, previousState, currentState);
        updateFingerprint(PENDING_CHUNK_FINGERPRINTS, chunkKey, level, packedPos, previousState, currentState);
        ENGINE.queueBlockChange(packedPos);
    }

    /**
     * 接收 NeoForge {@code ModelDataManager.requestRefresh} 的外观变化。
     *
     * <p>{@code ModelData} 常用于管道连接、方块实体材质等动态模型。这些变化不一定
     * 伴随 BlockState 替换，所以原有 {@link #blockChanged} 路径捕获不到。我们只标记受影响的
     * 网格 section，并使依赖旧模型的批量 VBO 快照失效；RGB 体素本身不重算。</p>
     */
    public static void modelDataChanged(ClientLevel level, BlockPos pos) {
        ensureLevel(level);
        rebaseCancelledMeshCpuState();
        visualRevision++;
        pendingVisualOnlyChange = true;
        ENGINE.queueMeshChange(pos.asLong());
    }

    @SubscribeEvent
    public static void chunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level && event.getChunk() instanceof LevelChunk chunk) {
            ensureLevel(level);
            long chunkKey = chunk.getPos().toLong();
            boolean alreadyLoaded = !LOADED_CHUNKS.add(chunkKey);
            SoftReference<CachedChunk> reference = CHUNK_SNAPSHOTS.get(chunkKey);
            CachedChunk cached = reference == null ? null : reference.get();
            long validationStarted = System.nanoTime();
            long currentFingerprint = scanChunkIdentity(level, chunk);
            boolean valid = cached != null
                    && cached.revision == contentRevision
                    && cached.fingerprint == currentFingerprint;
            boolean resident = !valid
                    && alreadyLoaded
                    && loadedIdentityRevision == contentRevision
                    && LOADED_CHUNK_FINGERPRINTS.containsKey(chunkKey)
                    && LOADED_CHUNK_FINGERPRINTS.get(chunkKey) == currentFingerprint;
            if (reference != null && !valid) {
                CHUNK_SNAPSHOTS.remove(chunkKey);
            }
            pendingSnapshotValidationNanos += System.nanoTime() - validationStarted;
            if (valid) {
                pendingSnapshotHits++;
                PENDING_CHUNK_RESTORES.put(chunkKey, cached.snapshot);
                PENDING_CHUNK_SCANS.remove(chunkKey);
                PENDING_CHUNK_FINGERPRINTS.put(chunkKey, currentFingerprint);
                markLifecycleChanged();
            } else if (resident) {
                pendingSnapshotHits++;
                PENDING_CHUNK_RESTORES.put(
                        chunkKey,
                        ENGINE.snapshotChunk(chunk.getPos().x, chunk.getPos().z)
                );
                PENDING_CHUNK_SCANS.remove(chunkKey);
                PENDING_CHUNK_FINGERPRINTS.put(chunkKey, currentFingerprint);
            } else {
                pendingSnapshotMisses++;
                PENDING_CHUNK_RESTORES.remove(chunkKey);
                PENDING_CHUNK_SCANS.add(chunkKey);
                PENDING_CHUNK_FINGERPRINTS.put(chunkKey, currentFingerprint);
                markLifecycleChanged();
            }
        }
    }

    public static void chunkUnload(ClientLevel level, LevelChunk chunk) {
        if (level == activeLevel) {
            ChunkPos pos = chunk.getPos();
            long chunkKey = pos.toLong();
            if (ENGINE.isPropagationSettled()
                    && slicedRgbFallback == null
                    && !hasAsyncRgbWork()
                    && !PENDING_CHUNK_SCANS.contains(chunkKey)
                    && !PENDING_CHUNK_RESTORES.containsKey(chunkKey)) {
                long captureStarted = System.nanoTime();
                CachedChunk cached = new CachedChunk(
                        contentRevision,
                        fingerprint(chunk),
                        ENGINE.snapshotChunk(pos.x, pos.z)
                );
                CHUNK_SNAPSHOTS.removeValuesIf(reference -> reference.get() == null);
                CHUNK_SNAPSHOTS.put(chunkKey, new SoftReference<>(cached), cached.snapshot.sectionCount());
                pendingSnapshotCaptureNanos += System.nanoTime() - captureStarted;
            }
            LOADED_CHUNKS.remove(chunkKey);
            SOURCE_REGISTRY.removeChunk(chunkKey);
            LOADED_CHUNK_FINGERPRINTS.remove(chunkKey);
            PENDING_CHUNK_SCANS.remove(chunkKey);
            PENDING_CHUNK_RESTORES.remove(chunkKey);
            PENDING_CHUNK_FINGERPRINTS.remove(chunkKey);
            PENDING_CHUNK_UNLOADS.add(chunkKey);
            markLifecycleChanged();
        }
    }

    /**
     * 在原版更换客户端区块存储后，将 Mod 记录与新存储做一次差分。
     *
     * <p>视距收缩不保证对被裁掉的每个旧槽位调用 {@link ClientLevel#unload(LevelChunk)}，
     * 因此 {@link #chunkUnload(ClientLevel, LevelChunk)} 不能作为唯一真相源。此时旧 {@link LevelChunk}
     * 已经不可查询，无法安全捕获新快照；我们只清理其光场、网格和指纹。如果以后重新加载，
     * 以前已存在的快照仍必须通过 revision 与内容指纹验证；验证失败才按普通未命中路径
     * 重新扫描。这样既不复用过期数据，也不会无条件丢失已验证的加速快照。
     */
    public static void reconcileLoadedChunks(ClientLevel level) {
        if (level != activeLevel || LOADED_CHUNKS.isEmpty()) {
            return;
        }
        LongOpenHashSet unavailable = ChunkLifecycleReconciler.findUnavailable(
                LOADED_CHUNKS,
                chunkKey -> level.getChunkSource().getChunk(
                        ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), ChunkStatus.FULL, false
                ) != null
        );
        if (unavailable.isEmpty()) {
            return;
        }
        LOADED_CHUNKS.removeAll(unavailable);
        for (long chunkKey : unavailable) {
            SOURCE_REGISTRY.removeChunk(chunkKey);
            LOADED_CHUNK_FINGERPRINTS.remove(chunkKey);
            PENDING_CHUNK_SCANS.remove(chunkKey);
            PENDING_CHUNK_RESTORES.remove(chunkKey);
            PENDING_CHUNK_FINGERPRINTS.remove(chunkKey);
        }
        PENDING_CHUNK_UNLOADS.addAll(unavailable);
        markLifecycleChanged();
    }

    @SubscribeEvent
    public static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == activeLevel) {
            resetLevel(null);
        }
    }

    static int packedEmission(Level level, BlockPos pos, BlockState state) {
        int emission = state.getLightEmission(level, pos);
        if (emission <= 0) {
            return 0;
        }
        OpalColor color = COLOR_CACHE.get(state);
        if (color == null) {
            color = LightDataLoader.INSTANCE.getColor(state);
            COLOR_CACHE.put(state, color == null ? OpalColor.EMPTY : color);
        }
        if (color == null || color == OpalColor.EMPTY) {
            return 0;
        }
        return PackedLight.fromColor(color.r(), color.g(), color.b(), emission);
    }

    private static boolean isIncludedFromLightMesh(BlockState state, int emission) {
        return !state.isAir() && emission == 0;
    }

    /**
     * 由 GameRenderer 的兼容注入点绘制彩光遮罩。
     *
     * <p>这里不能改用普通的 level-stage 订阅替代：Iris、VulkanMod 等替代渲染器会重排
     * 事件派发与矩阵状态，而注入点拿到的是它们完成世界绘制后仍然有效的实际视图矩阵。</p>
     */
    public static void render(Matrix4f viewMatrix, Matrix4f projectionMatrix, Camera camera) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ensureLevel(level);
        flush(level);
        RenderType mask = lightMask();
        mask.setupRenderState();
        try {
            LightMaskMeshCache.draw(
                    viewMatrix,
                    projectionMatrix,
                    camera,
                    level,
                    ENGINE
            );
        } finally {
            mask.clearRenderState();
        }
    }

    private static void flush(ClientLevel level) {
        RGB_COORDINATOR.takeFailure().ifPresent(failure -> {
            failedAsyncRgbKey = submittedAsyncRgbKey;
            submittedAsyncRgbKey = null;
            OpalLight.LOGGER.error(
                    "RGB worker candidate build failed; retaining the active generation until the next revision",
                    failure
            );
        });
        long lifecycleStarted = System.nanoTime();
        boolean visualOnlyChange = pendingVisualOnlyChange;
        pendingVisualOnlyChange = false;
        boolean processChunkScans = shouldProcessChunkScans();
        int unloadedChunkCount = 0;
        int restoredChunkCount = 0;
        int scannedChunkCount = 0;
        if (fullRebuildRequested) {
            RGB_COORDINATOR.invalidate();
            submittedAsyncRgbKey = null;
            failedAsyncRgbKey = null;
            slicedRgbFallback = null;
            ENGINE.clear();
            PENDING_CHUNK_UNLOADS.clear();
            PENDING_CHUNK_RESTORES.clear();
            LOADED_CHUNK_FINGERPRINTS.clear();
            CHUNK_SNAPSHOTS.clear();
            PENDING_CHUNK_SCANS.addAll(LOADED_CHUNKS);
            LightMaskMeshCache.invalidate();
            fullRebuildRequested = false;
            if (!PENDING_CHUNK_SCANS.isEmpty()) {
                markLifecycleChanged();
            }
        }

        if (!PENDING_CHUNK_UNLOADS.isEmpty()) {
            unloadedChunkCount = PENDING_CHUNK_UNLOADS.size();
            ENGINE.unloadChunks(PENDING_CHUNK_UNLOADS);
            access.removeChunks(PENDING_CHUNK_UNLOADS);
            LightMaskMeshCache.removeChunks(PENDING_CHUNK_UNLOADS);
            PENDING_CHUNK_UNLOADS.clear();
        }

        if (!PENDING_CHUNK_RESTORES.isEmpty()) {
            LongOpenHashSet restoredChunks = new LongOpenHashSet(PENDING_CHUNK_RESTORES.keySet());
            restoredChunkCount = restoredChunks.size();
            for (RgbLightEngine.ChunkSnapshot snapshot : PENDING_CHUNK_RESTORES.values()) {
                ENGINE.restoreChunk(snapshot);
            }
            for (long chunkKey : restoredChunks) {
                long fingerprint = PENDING_CHUNK_FINGERPRINTS.remove(chunkKey);
                LOADED_CHUNK_FINGERPRINTS.put(chunkKey, fingerprint);
            }
            PENDING_CHUNK_RESTORES.clear();
            ENGINE.queueRestoredChunkBoundaries(restoredChunks);
        }

        if (processChunkScans) {
            LongOpenHashSet scannedChunks = new LongOpenHashSet();
            for (long chunkKey : PENDING_CHUNK_SCANS) {
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                chunk.findBlockLightSources((pos, state) -> {
                    int emission = packedEmission(level, pos, state);
                    if (emission != 0) {
                        SOURCE_REGISTRY.put(pos.asLong(), emission);
                        ENGINE.queueBlockChange(pos.asLong());
                    }
                });
                long fingerprint = PENDING_CHUNK_FINGERPRINTS.remove(chunkKey);
                LOADED_CHUNK_FINGERPRINTS.put(chunkKey, fingerprint);
                scannedChunks.add(chunkKey);
            }
            ENGINE.queueChunkBoundaries(scannedChunks);
            scannedChunkCount = scannedChunks.size();
            PENDING_CHUNK_SCANS.removeAll(scannedChunks);
            CHUNK_SNAPSHOTS.removeValuesIf(reference -> reference.get() == null);
        }

        long started = System.nanoTime();
        long identityNanos = 0L;
        long processNanos;
        long bulkSnapshotNanos = 0L;
        access.beginBatch();
        RgbLightEngine.Stats stats;
        BulkStateIdentity bulkIdentity = null;
        int pendingChangeCount = ENGINE.pendingChangeCount();
        boolean largeChangedWork = RgbWorkloadPolicy.shouldOffload(
                pendingChangeCount,
                ENGINE.allocatedSectionCount(),
                ENGINE.pendingChangesTouchExistingLight()
        );
        boolean lifecycleChanged = !PENDING_CHUNK_SCANS.isEmpty()
                || !PENDING_CHUNK_RESTORES.isEmpty()
                || unloadedChunkCount != 0
                || restoredChunkCount != 0
                || scannedChunkCount != 0;
        boolean asyncEligible = largeChangedWork && !lifecycleChanged;
        boolean sliceRequired = slicedRgbFallback != null
                || RgbWorkloadPolicy.shouldSliceLifecycleWork(
                lifecycleChanged, ENGINE.pendingPropagationCount()
        )
                || !asyncEligible && (largeChangedWork || RgbWorkloadPolicy.shouldSliceQueuedPropagation(
                ENGINE.pendingPropagationCount()
        ));
        boolean bulkCandidate = asyncEligible || sliceRequired;
        // SourceRegistry 覆盖完整已加载域后，零光源目标可严格证明为规范全零光场。
        boolean canonicalEmptyFastPath = asyncEligible && SOURCE_REGISTRY.isEmpty();
        boolean bulkCacheHit = false;
        boolean pairedGpuCacheHit = false;
        boolean asyncDeferred = false;
        boolean cpuActivationDeferred = false;
        RgbLightEngine.ChunkSnapshot selectedBulkSnapshot = null;
        LightGeneration.RevisionKey generationRevisions = null;
        // 批量代在 GPU staging 完成前必须继续对外暴露旧 CPU 光场；候选只作为不可变快照传递。
        RgbLightEngine.ChunkSnapshot activeCpuBeforeBulk = bulkCandidate ? ENGINE.snapshotAll() : null;
        try {
            RgbGenerationKey asyncKey = bulkCandidate ? currentRgbGenerationKey() : null;
            boolean forceSyncAfterWorkerFailure = bulkCandidate && asyncKey.equals(failedAsyncRgbKey);
            Optional<GenerationCoordinator.Completed<RgbGenerationKey, AsyncRgbResult>> completedAsync =
                    asyncEligible ? RGB_COORDINATOR.poll(asyncKey) : Optional.empty();
            if (sliceRequired && !shouldCaptureBulkUpdate()) {
                // 新变化到达时重新等待 quiet window，避免分帧回退发布 fill 的中间布局。
                stats = new RgbLightEngine.Stats(0, 0, 0, 0, 0);
                processNanos = 0L;
                asyncDeferred = true;
            } else if (sliceRequired) {
                RGB_COORDINATOR.invalidate();
                submittedAsyncRgbKey = null;
                failedAsyncRgbKey = null;
                if (slicedRgbFallback == null) {
                    slicedRgbFallback = new SlicedRgbFallback();
                }
                long sliceStarted = System.nanoTime();
                RgbLightEngine.SliceResult slice = ENGINE.processSlice(
                        access, OWNER_PROPAGATION_SLICE_NANOS
                );
                long sliceNanos = System.nanoTime() - sliceStarted;
                slicedRgbFallback.add(slice.stats(), sliceNanos);
                if (slice.complete()) {
                    stats = slicedRgbFallback.stats();
                    processNanos = slicedRgbFallback.processNanos;
                    logSlicedFallbackComplete(slicedRgbFallback);
                    slicedRgbFallback = null;
                    long identityStarted = System.nanoTime();
                    bulkIdentity = SOURCE_REGISTRY.isEmpty()
                            ? canonicalEmptyBulkStateIdentity()
                            : bulkStateIdentity();
                    generationRevisions = bulkGenerationRevisions();
                    identityNanos = System.nanoTime() - identityStarted;
                    clearBulkCaptureWindow();
                } else {
                    stats = new RgbLightEngine.Stats(0, 0, 0, 0, 0);
                    processNanos = 0L;
                    asyncDeferred = true;
                }
            } else if (completedAsync.isPresent()) {
                GenerationCoordinator.Completed<RgbGenerationKey, AsyncRgbResult> completed =
                        completedAsync.orElseThrow();
                AsyncRgbResult result = completed.value();
                ENGINE.publishCandidate(result.candidate);
                stats = result.candidate.stats();
                processNanos = completed.workerNanos();
                identityNanos = result.identityNanos;
                bulkSnapshotNanos = result.captureNanos;
                bulkIdentity = result.bulkIdentity;
                generationRevisions = result.generationRevisions;
                selectedBulkSnapshot = result.candidate.snapshot();
                ENGINE.drainDirtyMeshSections(LightMaskMeshCache::markDirty);
                ENGINE.activateAllWithoutMeshInvalidation(activeCpuBeforeBulk);
                cpuActivationDeferred = true;
                submittedAsyncRgbKey = null;
                failedAsyncRgbKey = null;
                clearBulkCaptureWindow();
                OpalLight.LOGGER.debug(
                        "RGB worker candidate accepted: checked={}, sections={}, capture={}us, worker={}us",
                        stats.checkedBlocks(), result.candidate.snapshot().sectionCount(),
                        result.captureNanos / 1_000L, completed.workerNanos() / 1_000L
                );
            } else if (bulkCandidate && asyncKey.equals(submittedAsyncRgbKey) && hasAsyncRgbWork()) {
                // 同一 revision 已在 worker 中；保持完整旧代，不重复捕获、不重复提交。
                stats = new RgbLightEngine.Stats(0, 0, 0, 0, 0);
                processNanos = 0L;
                asyncDeferred = true;
            } else if (bulkCandidate && !shouldCaptureBulkUpdate()) {
                // quiet/max-wait 到达前禁止 cache lookup/activation，避免发布 fill 的中间布局。
                stats = new RgbLightEngine.Stats(0, 0, 0, 0, 0);
                processNanos = 0L;
                asyncDeferred = true;
            } else if (bulkCandidate) {
                if (asyncKey.equals(submittedAsyncRgbKey)) {
                    // worker 异常退出且没有结果时允许当前 revision 重新提交。
                    submittedAsyncRgbKey = null;
                }
                long identityStarted = System.nanoTime();
                bulkIdentity = canonicalEmptyFastPath
                        ? canonicalEmptyBulkStateIdentity()
                        : bulkStateIdentity();
                generationRevisions = bulkGenerationRevisions();
                identityNanos = System.nanoTime() - identityStarted;
                selectedBulkSnapshot = LightMaskMeshCache.prepareCachedBulkState(
                        bulkIdentity, generationRevisions
                );
                if (selectedBulkSnapshot != null) {
                    pairedGpuCacheHit = true;
                    bulkCacheHit = true;
                    // exact CPU/GPU 命中也只清理候选已经吸收的 pending；CPU 切代随 VBO adopt 发生。
                    ENGINE.activateAllWithoutMeshInvalidation(activeCpuBeforeBulk);
                    cpuActivationDeferred = true;
                } else {
                    CachedBulkState cached = BULK_STATE_SNAPSHOTS.get(bulkIdentity);
                    if (cached != null && cached.resourceRevision == resourceRevision) {
                        selectedBulkSnapshot = cached.snapshot;
                        ENGINE.restoreAll(selectedBulkSnapshot);
                        ENGINE.drainDirtyMeshSections(LightMaskMeshCache::markDirty);
                        ENGINE.activateAllWithoutMeshInvalidation(activeCpuBeforeBulk);
                        cpuActivationDeferred = true;
                        bulkCacheHit = true;
                    } else if (cached != null) {
                        BULK_STATE_SNAPSHOTS.remove(bulkIdentity);
                    }
                }
                OpalLight.LOGGER.debug(
                        "RGB bulk lookup: fingerprint={}, entries={}, chunks={}, cpuHit={}, pairedGpuHit={}",
                        Long.toUnsignedString(bulkIdentity.logFingerprint), BULK_STATE_SNAPSHOTS.size(),
                        bulkIdentity.loadedChunks.length, bulkCacheHit, pairedGpuCacheHit
                );
                if (!bulkCacheHit && !canonicalEmptyFastPath) {
                    if (forceSyncAfterWorkerFailure) {
                        // 快照专用适配失败时只回退一次 live reference；每帧仍受预算约束。
                        failedAsyncRgbKey = null;
                        slicedRgbFallback = new SlicedRgbFallback();
                        long sliceStarted = System.nanoTime();
                        RgbLightEngine.SliceResult slice = ENGINE.processSlice(
                                access, OWNER_PROPAGATION_SLICE_NANOS
                        );
                        long sliceNanos = System.nanoTime() - sliceStarted;
                        slicedRgbFallback.add(slice.stats(), sliceNanos);
                        if (slice.complete()) {
                            stats = slicedRgbFallback.stats();
                            processNanos = slicedRgbFallback.processNanos;
                            logSlicedFallbackComplete(slicedRgbFallback);
                            slicedRgbFallback = null;
                            clearBulkCaptureWindow();
                        } else {
                            stats = new RgbLightEngine.Stats(0, 0, 0, 0, 0);
                            processNanos = 0L;
                            asyncDeferred = true;
                        }
                    } else {
                        stats = new RgbLightEngine.Stats(0, 0, 0, 0, 0);
                        processNanos = 0L;
                        asyncDeferred = true;
                    }
                    if (!forceSyncAfterWorkerFailure && shouldCaptureBulkUpdate()) {
                        RgbLightEngine.PendingWork pendingWork = ENGINE.snapshotPendingWork();
                        Optional<WorldLightSnapshot> capturedWorld = SnapshotBlockAndTintGetter.capture(
                                level,
                                LOADED_CHUNKS,
                                pendingWork,
                                (pos, state) -> packedEmission(level, pos, state),
                                MAX_ASYNC_SNAPSHOT_SECTIONS
                        );
                        if (capturedWorld.isPresent()) {
                            WorldLightSnapshot worldSnapshot = capturedWorld.orElseThrow();
                            RGB_COORDINATOR.submit(
                                    asyncKey,
                                    new AsyncRgbTask(
                                            pendingWork,
                                            worldSnapshot,
                                            bulkIdentity,
                                            generationRevisions,
                                            identityNanos
                                    )
                            );
                            submittedAsyncRgbKey = asyncKey;
                            bulkSnapshotNanos = worldSnapshot.captureNanos();
                            clearBulkCaptureWindow();
                            OpalLight.LOGGER.debug(
                                    "RGB worker candidate submitted: changes={}, sections={}, capture={}us",
                                    pendingWork.changedBlockCount(), worldSnapshot.capturedSectionCount(),
                                    worldSnapshot.captureNanos() / 1_000L
                            );
                        } else {
                            // transient 预算溢出只放弃异步加速；live reference 跨帧精确收敛。
                            RGB_COORDINATOR.invalidate();
                            submittedAsyncRgbKey = null;
                            slicedRgbFallback = new SlicedRgbFallback();
                            long sliceStarted = System.nanoTime();
                            RgbLightEngine.SliceResult slice = ENGINE.processSlice(
                                    access, OWNER_PROPAGATION_SLICE_NANOS
                            );
                            long sliceNanos = System.nanoTime() - sliceStarted;
                            slicedRgbFallback.add(slice.stats(), sliceNanos);
                            if (slice.complete()) {
                                stats = slicedRgbFallback.stats();
                                processNanos = slicedRgbFallback.processNanos;
                                logSlicedFallbackComplete(slicedRgbFallback);
                                slicedRgbFallback = null;
                                asyncDeferred = false;
                                clearBulkCaptureWindow();
                            } else {
                                stats = new RgbLightEngine.Stats(0, 0, 0, 0, 0);
                                processNanos = 0L;
                                asyncDeferred = true;
                            }
                            OpalLight.LOGGER.warn(
                                    "RGB snapshot exceeded {} sections or contains dynamic block-entity occlusion; "
                                            + "using exact sliced propagation without truncation",
                                    MAX_ASYNC_SNAPSHOT_SECTIONS
                            );
                        }
                    }
                } else {
                    RGB_COORDINATOR.invalidate();
                    submittedAsyncRgbKey = null;
                    failedAsyncRgbKey = null;
                    long processStarted = System.nanoTime();
                    stats = canonicalEmptyFastPath && !bulkCacheHit
                            ? ENGINE.clearToCanonicalEmpty()
                            : ENGINE.process(access);
                    processNanos = System.nanoTime() - processStarted;
                    clearBulkCaptureWindow();
                }
            } else {
                long processStarted = System.nanoTime();
                stats = ENGINE.process(access);
                processNanos = System.nanoTime() - processStarted;
                clearBulkCaptureWindow();
            }
        } finally {
            access.endBatch();
        }
        if (stats.checkedBlocks() != 0) {
            loadedIdentityRevision = contentRevision;
        }
        if (bulkCandidate && !bulkCacheHit && !asyncDeferred) {
            long snapshotStarted = System.nanoTime();
            RgbLightEngine.ChunkSnapshot snapshot = selectedBulkSnapshot != null
                    ? selectedBulkSnapshot
                    : ENGINE.snapshotAll();
            selectedBulkSnapshot = snapshot;
            long publishedStateSnapshotNanos = System.nanoTime() - snapshotStarted;
            // 异步冷构建时优先保留更关键的 live-world capture 时间，不能被随后很小的
            // 冻结 CPU 状态引用复制覆盖；同步/分帧路径则继续记录发布快照自身耗时。
            if (bulkSnapshotNanos == 0L) {
                bulkSnapshotNanos = publishedStateSnapshotNanos;
            }
            BULK_STATE_SNAPSHOTS.put(
                    bulkIdentity,
                    new CachedBulkState(
                            resourceRevision,
                            snapshot
                    ),
                    bulkStateCacheWeight(bulkIdentity, snapshot)
            );
            OpalLight.LOGGER.debug(
                    "RGB bulk snapshot stored: fingerprint={}, entries={}, bytes={}, chunks={}",
                    Long.toUnsignedString(bulkIdentity.logFingerprint), BULK_STATE_SNAPSHOTS.size(),
                    BULK_STATE_SNAPSHOTS.totalWeight(), LOADED_CHUNKS.size()
            );
        }
        if (bulkCandidate && !asyncDeferred && !cpuActivationDeferred) {
            // 同步/分帧回退已经在 owner 引擎中得到目标；先冻结并排空其网格变化，再把可见 CPU
            // 状态放回旧代。GPU 完成 staging 后会在同一 generation 提交点激活目标。
            if (selectedBulkSnapshot == null) {
                selectedBulkSnapshot = ENGINE.snapshotAll();
            }
            ENGINE.drainDirtyMeshSections(LightMaskMeshCache::markDirty);
            ENGINE.activateAllWithoutMeshInvalidation(activeCpuBeforeBulk);
            cpuActivationDeferred = true;
        }
        if (bulkCandidate && !asyncDeferred) {
            LightMaskMeshCache.selectBulkState(
                    bulkIdentity,
                    generationRevisions,
                    selectedBulkSnapshot,
                    bulkCacheHit && pairedGpuCacheHit
            );
        } else if (visualOnlyChange || pendingChangeCount != 0 || stats.operations() != 0 || stats.valueChanges() != 0) {
            LightMaskMeshCache.selectBulkState(null, null, null, false);
        }
        if (!asyncDeferred) {
            ENGINE.drainDirtyMeshSections(LightMaskMeshCache::markDirty);
        }
        long elapsed = System.nanoTime() - started;
        if (!asyncDeferred && (stats.operations() > 10_000 || elapsed > 2_000_000L)) {
            OpalLight.LOGGER.debug(
                    "RGB light flush: checked={}, decrease={}, increase={}, changed={}, dirtyAttempts={}, sections={}, bulkHit={}, emptyFastPath={}, time={}us",
                    stats.checkedBlocks(), stats.decreaseSteps(), stats.increaseSteps(), stats.valueChanges(),
                    stats.meshDirtySectionAttempts(), ENGINE.allocatedSectionCount(), bulkCacheHit,
                    canonicalEmptyFastPath && !bulkCacheHit, elapsed / 1_000L
            );
            if (bulkCandidate) {
                OpalLight.LOGGER.debug(
                        "RGB bulk phases: sources={}, identity={}us, process={}us, snapshot={}us, worldScans=0",
                        SOURCE_REGISTRY.size(), identityNanos / 1_000L, processNanos / 1_000L,
                        bulkSnapshotNanos / 1_000L
                );
            }
        }
        if (unloadedChunkCount != 0 || restoredChunkCount != 0 || scannedChunkCount != 0) {
            OpalLight.LOGGER.debug(
                    "RGB chunk batch: unloaded={}, restored={}, scanned={}, hits={}, misses={}, capture={}us, validation={}us, total={}us",
                    unloadedChunkCount, restoredChunkCount, scannedChunkCount,
                    pendingSnapshotHits, pendingSnapshotMisses,
                    pendingSnapshotCaptureNanos / 1_000L,
                    pendingSnapshotValidationNanos / 1_000L,
                    (System.nanoTime() - lifecycleStarted) / 1_000L
            );
            pendingSnapshotHits = 0;
            pendingSnapshotMisses = 0;
            pendingSnapshotCaptureNanos = 0;
            pendingSnapshotValidationNanos = 0;
        }
    }

    private static void ensureLevel(ClientLevel level) {
        if (activeLevel != level) {
            resetLevel(level);
        }
    }

    private static void resetLevel(@Nullable ClientLevel level) {
        ENGINE.clear();
        SOURCE_REGISTRY.clear();
        LOADED_CHUNKS.clear();
        PENDING_CHUNK_SCANS.clear();
        PENDING_CHUNK_UNLOADS.clear();
        PENDING_CHUNK_RESTORES.clear();
        PENDING_CHUNK_FINGERPRINTS.clear();
        LOADED_CHUNK_FINGERPRINTS.clear();
        BULK_STATE_SNAPSHOTS.clear();
        CHUNK_SNAPSHOTS.clear();
        LightMaskMeshCache.invalidate();
        activeLevel = level;
        access = level == null ? null : new ClientLevelAccess(level);
        fullRebuildRequested = level != null;
        lifecycleBatchStartedNanos = 0;
        lastLifecycleChangeNanos = 0;
        clearBulkCaptureWindow();
        contentRevision = 0;
        loadedIdentityRevision = 0;
        resourceRevision = 0;
        propagationRevision = 0;
        visualRevision = 0;
        levelSessionRevision++;
        chunkLifecycleRevision = 0;
        RGB_COORDINATOR.invalidate();
        submittedAsyncRgbKey = null;
        failedAsyncRgbKey = null;
        slicedRgbFallback = null;
        pendingVisualOnlyChange = false;
        pendingSnapshotHits = 0;
        pendingSnapshotMisses = 0;
        pendingSnapshotCaptureNanos = 0;
        pendingSnapshotValidationNanos = 0;
    }

    /**
     * 只允许网格发布器在同一 generation 的 VBO 已经 active 后采用对应 CPU 快照。
     */
    static void activatePublishedCpuState(RgbLightEngine.ChunkSnapshot snapshot, long generationId) {
        if (snapshot == null) {
            throw new IllegalStateException("Bulk generation is missing its CPU light-field snapshot: " + generationId);
        }
        ENGINE.activateAllWithoutMeshInvalidation(snapshot);
    }

    private static boolean shouldProcessChunkScans() {
        if (PENDING_CHUNK_SCANS.isEmpty()) {
            lifecycleBatchStartedNanos = 0;
            lastLifecycleChangeNanos = 0;
            return false;
        }
        long now = System.nanoTime();
        if (now - lastLifecycleChangeNanos < CHUNK_LIFECYCLE_QUIET_NANOS
                && now - lifecycleBatchStartedNanos < CHUNK_LIFECYCLE_MAX_WAIT_NANOS) {
            return false;
        }
        lifecycleBatchStartedNanos = 0;
        lastLifecycleChangeNanos = 0;
        return true;
    }

    private static void markLifecycleChanged() {
        chunkLifecycleRevision++;
        // load/unload quiet window 期间也不能让旧 loaded-domain 的 mesh staging 抢先提交。
        rebaseCancelledMeshCpuState();
        RGB_COORDINATOR.invalidate();
        submittedAsyncRgbKey = null;
        failedAsyncRgbKey = null;
        long now = System.nanoTime();
        if (lifecycleBatchStartedNanos == 0) {
            lifecycleBatchStartedNanos = now;
        }
        lastLifecycleChangeNanos = now;
    }

    /**
     * 取消 GPU staging 时先接回其完整 CPU 候选，随后到达的 delta 才不会从旧光场错误续算。
     */
    private static void rebaseCancelledMeshCpuState() {
        RgbLightEngine.ChunkSnapshot cancelledCpuState =
                LightMaskMeshCache.invalidatePendingBuildAndTakeCpuState();
        if (cancelledCpuState != null) {
            ENGINE.rebasePendingChanges(cancelledCpuState);
        }
    }

    private static void markBulkContentChanged() {
        long now = System.nanoTime();
        if (bulkBatchStartedNanos == 0L) {
            bulkBatchStartedNanos = now;
        }
        lastBulkChangeNanos = now;
    }

    private static boolean shouldCaptureBulkUpdate() {
        if (bulkBatchStartedNanos == 0L) {
            return true;
        }
        long now = System.nanoTime();
        return now - lastBulkChangeNanos >= BULK_UPDATE_QUIET_NANOS
                || now - bulkBatchStartedNanos >= BULK_UPDATE_MAX_WAIT_NANOS;
    }

    private static void clearBulkCaptureWindow() {
        bulkBatchStartedNanos = 0L;
        lastBulkChangeNanos = 0L;
    }

    private static void logSlicedFallbackComplete(SlicedRgbFallback fallback) {
        OpalLight.LOGGER.debug(
                "RGB owner sliced fallback complete: total={}us, maxSlice={}us, slices={}",
                fallback.processNanos / 1_000L,
                fallback.maxSliceNanos / 1_000L,
                fallback.slices
        );
    }

    private static long fingerprint(LevelChunk chunk) {
        long hash = 0x6A09_E667_F3BC_C909L;
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            int blockIndex = sectionIndex << 12 | y << 8 | z << 4 | x;
                            hash ^= fingerprintContribution(blockIndex, state);
                        }
                    }
                }
            }
        }
        return hash;
    }

    /**
     * 在区块进入客户端加载域时，用一次已有的内容指纹遍历同步重建该区块的光源索引。
     *
     * <p>快照命中不能跳过这一步：区块卸载时注册表会删除其光源，而 RGB 区块快照只负责
     * 恢复光场，不是当前世界方块的身份真相。把两项工作合并在同一次 section 遍历中，
     * 避免先算指纹、再为索引完整扫描第二遍。</p>
     */
    private static long scanChunkIdentity(ClientLevel level, LevelChunk chunk) {
        long chunkKey = chunk.getPos().toLong();
        SOURCE_REGISTRY.removeChunk(chunkKey);
        long hash = 0x6A09_E667_F3BC_C909L;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }
                        int blockIndex = sectionIndex << 12 | y << 8 | z << 4 | x;
                        hash ^= fingerprintContribution(blockIndex, state);
                        // 先复用原版 findBlockLightSources 的 palette 粗筛条件，绝大多数普通
                        // 建筑方块无需调用带世界上下文的动态发光查询，避免让区块指纹合并扫描
                        // 反过来放大区块加载成本。
                        if (state.hasDynamicLightEmission()
                                || state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) != 0) {
                            pos.set(baseX + x, minY + (sectionIndex << 4) + y, baseZ + z);
                            int emission = packedEmission(level, pos, state);
                            if (emission != 0) {
                                SOURCE_REGISTRY.put(pos.asLong(), emission);
                            }
                        }
                    }
                }
            }
        }
        return hash;
    }

    private static void updateFingerprint(
            Long2LongOpenHashMap fingerprints,
            long chunkKey,
            ClientLevel level,
            long packedPos,
            BlockState previousState,
            BlockState currentState
    ) {
        if (!fingerprints.containsKey(chunkKey)) {
            return;
        }
        int blockIndex = (PackedPosition.y(packedPos) - level.getMinBuildHeight()) << 8
                | (PackedPosition.z(packedPos) & 15) << 4
                | PackedPosition.x(packedPos) & 15;
        long fingerprint = fingerprints.get(chunkKey);
        if (!previousState.isAir()) {
            fingerprint ^= fingerprintContribution(blockIndex, previousState);
        }
        if (!currentState.isAir()) {
            fingerprint ^= fingerprintContribution(blockIndex, currentState);
        }
        fingerprints.put(chunkKey, fingerprint);
    }

    private static long fingerprintContribution(int blockIndex, BlockState state) {
        long value = (long) blockIndex << 32 ^ Block.getId(state) & 0xFFFF_FFFFL;
        value ^= value >>> 30;
        value *= 0xBF58_476D_1CE4_E5B9L;
        value ^= value >>> 27;
        value *= 0x94D0_49BB_1331_11EBL;
        return value ^ value >>> 31;
    }

    /**
     * 为一次大批量更新生成可重现的状态身份。
     *
     * <p>光源位置与颜色描述直接输入；{@code propagationRevision} 覆盖遮光规则变化；
     * {@code visualRevision} 防止复用带有旧模型几何的 VBO。区块依赖不采用整个视距，而是由
     * 当前 RGB 体素 section 与目标光源周围的 3×3 邻域推导，再通过内容指纹区分
     * “同一坐标但方块已改变”的区块。</p>
     *
     * <p>普通随机刻若没有改变遮光能力或可见模型，不应让完全相同的灯光布局
     * 失去快照命中机会。这一区分是跨区块往返时避免 4096/65536 光源重算的关键。</p>
     */
    private static BulkStateIdentity bulkStateIdentity() {
        SourceRegistry.FrozenIdentity sources = SOURCE_REGISTRY.freezeIdentity();
        long[] sourcePositions = sources.positionsView();
        short[] sourceColors = sources.emissionsView();
        LongOpenHashSet lightSections = new LongOpenHashSet();
        ENGINE.collectLightSectionKeys(lightSections);
        long[] relevantLoadedChunks = BulkStateDependencies.relevantLoadedChunks(
                LOADED_CHUNKS, lightSections, sources.chunkKeysView()
        );
        long[] relevantChunkFingerprints = BulkStateDependencies.relevantChunkFingerprints(
                relevantLoadedChunks, LOADED_CHUNK_FINGERPRINTS
        );
        long logFingerprint = fingerprintMix(0xBB67_AE85_84CA_A73BL ^ propagationRevision);
        for (int index = 0; index < sourcePositions.length; index++) {
            long position = sourcePositions[index];
            int color = Short.toUnsignedInt(sourceColors[index]);
            logFingerprint = fingerprintMix(logFingerprint ^ position ^ Long.rotateLeft(color, 19));
        }
        for (int index = 0; index < relevantLoadedChunks.length; index++) {
            logFingerprint = fingerprintMix(
                    logFingerprint ^ relevantLoadedChunks[index]
                            ^ Long.rotateLeft(relevantChunkFingerprints[index], 23)
            );
        }
        return new BulkStateIdentity(
                activeLevel.dimension().location().toString(),
                propagationRevision, visualRevision,
                relevantLoadedChunks, relevantChunkFingerprints,
                sourcePositions, sourceColors, logFingerprint
        );
    }

    /**
     * 规范全零代不依赖“被清除前旧光场覆盖过哪些 section”。只要同一世界会话、规则与模型
     * 修订一致，任意大批量全清都指向同一个 exact identity，避免不同建筑清空后制造多份零状态。
     */
    private static BulkStateIdentity canonicalEmptyBulkStateIdentity() {
        String dimension = activeLevel.dimension().location().toString();
        long fingerprint = fingerprintMix(
                0xA54F_F53A_5F1D_36F1L
                        ^ dimension.hashCode()
                        ^ propagationRevision
                        ^ Long.rotateLeft(visualRevision, 17)
        );
        return new BulkStateIdentity(
                dimension,
                propagationRevision,
                visualRevision,
                new long[0],
                new long[0],
                new long[0],
                new short[0],
                fingerprint
        );
    }

    /**
     * 返回会影响原版面遮光计算的稳定签名；等价的空气与非遮光灯具得到相同值。
     */
    private static long propagationSignature(Level level, BlockPos pos, BlockState state) {
        int opacity = Math.max(1, state.getLightBlock(level, pos));
        if (opacity >= 15) {
            return 16;
        }
        if (!state.canOcclude() || !state.useShapeForLightOcclusion()) {
            return opacity;
        }
        return 0x1_0000_0000L | Integer.toUnsignedLong(Block.getId(state));
    }

    private static long fingerprintMix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58_476D_1CE4_E5B9L;
        value ^= value >>> 27;
        value *= 0x94D0_49BB_1331_11EBL;
        return value ^ value >>> 31;
    }

    /**
     * 生成复合缓存的生命周期修订键。
     *
     * <p>光源内容、相关区块及传播签名已经逐项进入 {@link BulkStateIdentity}，不能再放入
     * 单调递增的 callback 计数，否则 color→air→同一 color 永远无法命中。这里仅保留
     * 不属于布局身份本身的世界会话、资源规则和模型生命周期。</p>
     */
    private static LightGeneration.RevisionKey bulkGenerationRevisions() {
        return new LightGeneration.RevisionKey(
                levelSessionRevision,
                0L,
                0L,
                resourceRevision,
                visualRevision,
                0L
        );
    }

    /**
     * worker candidate 的完整失效键。单调 revision 只负责拒绝迟到结果；跨批次缓存身份仍由
     * {@link BulkStateIdentity} 的规范化内容决定，因此不会破坏 color→air→color 热命中。
     */
    private static RgbGenerationKey currentRgbGenerationKey() {
        return new RgbGenerationKey(
                levelSessionRevision,
                activeLevel.dimension().location().toString(),
                contentRevision,
                chunkLifecycleRevision,
                resourceRevision,
                propagationRevision,
                visualRevision
        );
    }

    private static boolean hasAsyncRgbWork() {
        return RGB_COORDINATOR.hasWork();
    }

    /**
     * 估算缓存真正长期持有的 CPU 净载荷：两套 short section 加精确 identity 数组。
     * Map/对象头随 JVM 实现变化，不伪装成精确值；固定元数据防止全空布局被按零权重无限堆积。
     */
    private static long bulkStateCacheWeight(
            BulkStateIdentity identity,
            RgbLightEngine.ChunkSnapshot snapshot
    ) {
        long sectionBytes = (long) snapshot.sectionCount() * 4096L * Short.BYTES;
        long identityBytes = identity.estimatedBytes();
        return identityBytes > Long.MAX_VALUE - sectionBytes
                ? Long.MAX_VALUE
                : identityBytes + sectionBytes;
    }

    /**
     * 跨帧累计 live-world 精确回退；仅在引擎报告完整收敛后才允许对外发布。
     */
    private static final class SlicedRgbFallback {
        private long checkedBlocks;
        private long decreaseSteps;
        private long increaseSteps;
        private long valueChanges;
        private long meshDirtySectionAttempts;
        private long processNanos;
        private long maxSliceNanos;
        private int slices;

        void add(RgbLightEngine.Stats stats, long elapsedNanos) {
            checkedBlocks += stats.checkedBlocks();
            decreaseSteps += stats.decreaseSteps();
            increaseSteps += stats.increaseSteps();
            valueChanges += stats.valueChanges();
            meshDirtySectionAttempts += stats.meshDirtySectionAttempts();
            processNanos += elapsedNanos;
            maxSliceNanos = Math.max(maxSliceNanos, elapsedNanos);
            slices++;
        }

        RgbLightEngine.Stats stats() {
            return new RgbLightEngine.Stats(
                    (int) Math.min(Integer.MAX_VALUE, checkedBlocks),
                    decreaseSteps,
                    increaseSteps,
                    valueChanges,
                    meshDirtySectionAttempts
            );
        }
    }

    private record CachedChunk(long revision, long fingerprint, RgbLightEngine.ChunkSnapshot snapshot) {
    }

    private record RgbGenerationKey(
            long levelSessionRevision,
            String dimension,
            long contentRevision,
            long chunkLifecycleRevision,
            long resourceRevision,
            long propagationRevision,
            long visualRevision
    ) {
    }

    private record AsyncRgbTask(
            RgbLightEngine.PendingWork pendingWork,
            WorldLightSnapshot worldSnapshot,
            BulkStateIdentity bulkIdentity,
            LightGeneration.RevisionKey generationRevisions,
            long identityNanos
    ) {
    }

    private record AsyncRgbResult(
            RgbLightEngine.Candidate candidate,
            BulkStateIdentity bulkIdentity,
            LightGeneration.RevisionKey generationRevisions,
            long identityNanos,
            long captureNanos
    ) {
    }

    private record CachedBulkState(
            long resourceRevision,
            RgbLightEngine.ChunkSnapshot snapshot
    ) {
    }

    /**
     * Map 的 {@code hashCode} 只用于定位，{@link #equals(Object)} 还会比较排序后的相关区块、
     * 每个区块的 64 位内容指纹、全部光源位置及颜色，因此 Java 容器哈希碰撞不会误恢复。
     * 区块指纹本身是 64 位非加密摘要；其理论碰撞概率极低，这是用常数级身份大小换取
     * 批量场景快速比较的明确工程取舍。
     */
    private static final class BulkStateIdentity {
        private final String dimension;
        private final long propagationRevision;
        private final long visualRevision;
        private final long[] loadedChunks;
        private final long[] chunkFingerprints;
        private final long[] sourcePositions;
        private final short[] sourceColors;
        private final long logFingerprint;
        private final int hashCode;

        private BulkStateIdentity(
                String dimension,
                long propagationRevision,
                long visualRevision,
                long[] loadedChunks,
                long[] chunkFingerprints,
                long[] sourcePositions,
                short[] sourceColors,
                long logFingerprint
        ) {
            this.dimension = dimension;
            this.propagationRevision = propagationRevision;
            this.visualRevision = visualRevision;
            this.loadedChunks = loadedChunks;
            this.chunkFingerprints = chunkFingerprints;
            this.sourcePositions = sourcePositions;
            this.sourceColors = sourceColors;
            this.logFingerprint = logFingerprint;
            int hash = dimension.hashCode();
            hash = 31 * hash + Long.hashCode(propagationRevision);
            hash = 31 * hash + Long.hashCode(visualRevision);
            hash = 31 * hash + Arrays.hashCode(loadedChunks);
            hash = 31 * hash + Arrays.hashCode(chunkFingerprints);
            hash = 31 * hash + Arrays.hashCode(sourcePositions);
            this.hashCode = 31 * hash + Arrays.hashCode(sourceColors);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof BulkStateIdentity other)) {
                return false;
            }
            return dimension.equals(other.dimension)
                    && propagationRevision == other.propagationRevision
                    && visualRevision == other.visualRevision
                    && Arrays.equals(loadedChunks, other.loadedChunks)
                    && Arrays.equals(chunkFingerprints, other.chunkFingerprints)
                    && Arrays.equals(sourcePositions, other.sourcePositions)
                    && Arrays.equals(sourceColors, other.sourceColors);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        private long estimatedBytes() {
            // 数组净载荷 + identity/key/缓存节点的保守固定元数据。
            return 128L + (long) dimension.length() * Character.BYTES
                    + (long) loadedChunks.length * Long.BYTES
                    + (long) chunkFingerprints.length * Long.BYTES
                    + (long) sourcePositions.length * Long.BYTES
                    + (long) sourceColors.length * Short.BYTES;
        }
    }

    private static final class ClientLevelAccess implements RgbLightEngine.Access {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos fromPos = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos toPos = new BlockPos.MutableBlockPos();
        private final Long2ObjectOpenHashMap<AttenuationSection> attenuationCache = new Long2ObjectOpenHashMap<>();
        private long lastChunkKey = Long.MIN_VALUE;
        private boolean lastChunkLoaded;
        private int cacheEpoch = 1;

        private ClientLevelAccess(ClientLevel level) {
            this.level = level;
        }

        private void beginBatch() {
            cacheEpoch++;
            if (cacheEpoch == 0) {
                attenuationCache.clear();
                cacheEpoch = 1;
            }
            lastChunkKey = Long.MIN_VALUE;
        }

        private void removeChunks(LongOpenHashSet chunkKeys) {
            attenuationCache.keySet().removeIf(sectionKey ->
                    chunkKeys.contains(ChunkPos.asLong(
                            PackedPosition.sectionX(sectionKey),
                            PackedPosition.sectionZ(sectionKey)
                    ))
            );
            lastChunkKey = Long.MIN_VALUE;
        }

        private void endBatch() {
            attenuationCache.values().removeIf(section -> section.lastUsedEpoch != cacheEpoch);
        }

        @Override
        public boolean isLoaded(long pos) {
            int y = PackedPosition.y(pos);
            if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                return false;
            }
            long chunkKey = ChunkPos.asLong(PackedPosition.x(pos) >> 4, PackedPosition.z(pos) >> 4);
            if (chunkKey != lastChunkKey) {
                lastChunkKey = chunkKey;
                lastChunkLoaded = LOADED_CHUNKS.contains(chunkKey);
            }
            return lastChunkLoaded;
        }

        @Override
        public int emission(long pos) {
            fromPos.set(pos);
            BlockState state = level.getBlockState(fromPos);
            return packedEmission(level, fromPos, state);
        }

        @Override
        public int attenuation(long from, long to, int direction) {
            int cached = targetAttenuation(to);
            if (cached <= 16) {
                return cached;
            }
            fromPos.set(from);
            toPos.set(to);
            BlockState fromState = level.getBlockState(fromPos);
            BlockState toState = level.getBlockState(toPos);
            int opacity = Math.max(1, toState.getLightBlock(level, toPos));
            return LightEngine.getLightBlockInto(
                    level, fromState, fromPos, toState, toPos, DIRECTIONS[direction], opacity
            );
        }

        private int targetAttenuation(long pos) {
            long sectionKey = PackedPosition.sectionKey(pos);
            AttenuationSection section = attenuationCache.computeIfAbsent(
                    sectionKey, ignored -> new AttenuationSection()
            );
            section.lastUsedEpoch = cacheEpoch;
            int index = PackedPosition.index(pos);
            if (section.epochs[index] == cacheEpoch) {
                return section.values[index] & 0xFF;
            }

            toPos.set(pos);
            BlockState state = level.getBlockState(toPos);
            int opacity = Math.max(1, state.getLightBlock(level, toPos));
            int value;
            if (opacity >= 15) {
                value = 16;
            } else if (!state.canOcclude() || !state.useShapeForLightOcclusion()) {
                value = opacity;
            } else {
                value = 17;
            }
            section.epochs[index] = cacheEpoch;
            section.values[index] = (byte) value;
            return value;
        }

        private static final class AttenuationSection {
            private final byte[] values = new byte[4096];
            private final int[] epochs = new int[4096];
            private int lastUsedEpoch;
        }
    }
}
