package org.mesdag.opallight.light.render;

import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.mesdag.opallight.OpalLight;
import org.mesdag.opallight.light.engine.LightColorCache;

import java.util.Iterator;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

import static org.mesdag.opallight.light.engine.LightMeshLayout.*;

public final class LightMaskMeshCache {
    private final ClientLevel level;
    private final LightColorCache colors;
    private final LightMaskRenderer renderer = new LightMaskRenderer();

    LightMaskMeshCache(ClientLevel level, LightColorCache colors) {
        this.level = level;
        this.colors = colors;
    }

    void captureTerrainFog() {
        renderer.captureTerrainFog();
    }

    private final Long2ObjectOpenHashMap<VertexBuffer> buffers = new Long2ObjectOpenHashMap<>();
    static final long DYNAMIC_TRANSITION_NANOS = 70_000_000L;

    record Transition(VertexBuffer previous, long startedAt,
                      @Nullable LightTransitionColors colors) implements AutoCloseable {
        @Override
        public void close() {
            if (colors != null) colors.close();
            previous.close();
        }
    }

    private final Long2ObjectOpenHashMap<Transition> transitions = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<AABB> bounds = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirtyGroups = new LongOpenHashSet();
    private final LongOpenHashSet urgentGroups = new LongOpenHashSet();
    private final LongOpenHashSet dynamicPriorityGroups = new LongOpenHashSet();
    private final LongOpenHashSet colorDirtyGroups = new LongOpenHashSet();
    // 颜色失效但无范围表示全量重算；局部范围保留至网格成功发布。
    private final Long2ObjectOpenHashMap<LightUpdateBounds> colorDirtyBounds = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet batchedDirtyGroups = new LongOpenHashSet();
    private final LongOpenHashSet batchedDeferredGroups = new LongOpenHashSet();
    private final LongOpenHashSet batchedHardGroups = new LongOpenHashSet();
    private final LongOpenHashSet deferredColorGroups = new LongOpenHashSet();
    private boolean batchingDirty;
    private boolean batchingDynamic;
    private @Nullable LightMeshBatch<LightMaskMeshBuilder.BuiltMesh> dynamicBatch;
    private final LongOpenHashSet suspendedDynamicGroups = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<AtomicBoolean> inFlight = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap groupVersions = new Long2LongOpenHashMap();
    private final LongOpenHashSet failedGroups = new LongOpenHashSet();
    private final LightMaskMeshParts parts = new LightMaskMeshParts();
    private @Nullable LightMaskReloadTransaction reloadTransaction;
    private final ConcurrentLinkedQueue<MeshResult> completed = new ConcurrentLinkedQueue<>();
    private static final int MESH_WORKER_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    private static final ExecutorService meshWorkers = Executors.newFixedThreadPool(MESH_WORKER_COUNT, task -> {
        Thread thread = new Thread(task, "OpalLight mesh builder");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong epoch = new AtomicLong();
    private final LongArrayList visibleGroups = new LongArrayList();

    private record MeshResult(long key, long epoch, long version, @Nullable LightMaskMeshBuilder.BuiltMesh mesh,
                              @Nullable Throwable error) {
    }

    boolean hasMeshes() {
        return !buffers.isEmpty();
    }

    private void dirty(long key) {
        dirty(key, false);
    }

    private void dirty(long key, boolean geometryOnly) {
        dirty(key, geometryOnly, false);
    }

    private void dirty(long key, boolean geometryOnly, boolean deferColor) {
        if (!geometryOnly) {
            if (!deferColor) colorDirtyBounds.remove(key);
            colorDirtyGroups.add(key);
        }
        if (batchingDirty) {
            batchedDirtyGroups.add(key);
            if (!deferColor) {
                batchedHardGroups.add(key);
                batchedDeferredGroups.remove(key);
            } else if (!batchedHardGroups.contains(key)) batchedDeferredGroups.add(key);
            return;
        }
        AtomicBoolean cancellation = inFlight.get(key);
        if (deferColor && cancellation != null && !cancellation.get() && reloadTransaction == null
            && !batchingDynamic && (dynamicBatch == null || !dynamicBatch.contains(key))) {
            // 先发布在途网格再追赶最新颜色，避免持续取消导致饥饿。
            deferredColorGroups.add(key);
            return;
        }
        deferredColorGroups.remove(key);
        if (dynamicBatch != null) dynamicBatch.invalidate(key);
        if (cancellation != null) cancellation.set(true);
        if (reloadTransaction != null) reloadTransaction.invalidate(key);
        failedGroups.remove(key);
        dirtyGroups.add(key);
        groupVersions.addTo(key, 1);
    }

    public void beginDirtyBatch(boolean dynamic) {
        batchingDirty = true;
        batchingDynamic = dynamic && reloadTransaction == null;
    }

    public void endDirtyBatch() {
        batchingDirty = false;
        for (long key : batchedDirtyGroups) dirty(key, true, batchedDeferredGroups.contains(key));
        if (batchingDynamic) {
            LongOpenHashSet members = new LongOpenHashSet(batchedDirtyGroups);
            members.addAll(suspendedDynamicGroups);
            suspendedDynamicGroups.clear();
            if (!members.isEmpty()) {
                dynamicBatch = new LightMeshBatch<>(members);
                dynamicPriorityGroups.addAll(members);
            }
        }
        batchingDynamic = false;
        batchedDirtyGroups.clear();
        batchedDeferredGroups.clear();
        batchedHardGroups.clear();
    }

    boolean hasPendingDynamicBatch() {
        return dynamicBatch != null;
    }

    void invalidateDynamicBatch() {
        suspendedDynamicGroups.addAll(dynamicPriorityGroups);
        if (dynamicBatch != null) {
            suspendedDynamicGroups.addAll(dynamicBatch.keys());
            dynamicBatch.close();
            dynamicBatch = null;
        }
        for (long key : suspendedDynamicGroups) dirty(key);
    }

    void resumeDynamicBatch() {
        if (suspendedDynamicGroups.isEmpty()) return;
        dynamicBatch = new LightMeshBatch<>(suspendedDynamicGroups);
        dynamicPriorityGroups.addAll(suspendedDynamicGroups);
        suspendedDynamicGroups.clear();
    }

    public void invalidateChangedGeometry(long packedPos) {
        markBlockAffected(packedPos, true);
    }

    public void markLightingChanged(long packedPos) {
        markBlockAffected(packedPos, false);
    }

    private void markBlockAffected(long packedPos, boolean immediate) {
        int x = BlockPos.getX(packedPos), y = BlockPos.getY(packedPos), z = BlockPos.getZ(packedPos);
        Boolean colorNear = null;
        for (int gx = (x - 1) >> GROUP_XZ_BLOCK_SHIFT; gx <= (x + 1) >> GROUP_XZ_BLOCK_SHIFT; gx++) {
            for (int gy = (y - 1) >> GROUP_Y_BLOCK_SHIFT; gy <= (y + 1) >> GROUP_Y_BLOCK_SHIFT; gy++) {
                for (int gz = (z - 1) >> GROUP_XZ_BLOCK_SHIFT; gz <= (z + 1) >> GROUP_XZ_BLOCK_SHIFT; gz++) {
                    long key = SectionPos.asLong(gx, gy, gz);
                    boolean hasBuffer = buffers.containsKey(key);
                    boolean staged = reloadTransaction != null && reloadTransaction.contains(key);
                    if (!hasBuffer && (!immediate || !(inFlight.containsKey(key) || dirtyGroups.contains(key) || staged)))
                        continue;
                    if (hasBuffer && !parts.hasCachedGeometryNear(key, x, y, z)) {
                        if (colorNear == null)
                            colorNear = colors.hasColorNear(BlockPos.of(packedPos));
                        if (!colorNear && !inFlight.containsKey(key) && !dirtyGroups.contains(key) && !staged) continue;
                    }
                    parts.markBlockChanged(key, x, y, z);
                    dirty(key, true);
                    if (immediate && hasBuffer) urgentGroups.add(key);
                }
            }
        }
    }

    public void markDirtyAroundSection(long sectionKey) {
        int x = SectionPos.x(sectionKey) << 4, y = SectionPos.y(sectionKey) << 4, z = SectionPos.z(sectionKey) << 4;
        markDirtyForBounds(x, y, z, x + 15, y + 15, z + 15);
    }

    void markDirtyForBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        markColorChanged(minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    void markColorChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean immediate) {
        LightUpdateBounds changed = new LightUpdateBounds(minX, minY, minZ, maxX, maxY, maxZ);
        for (int gx = (minX - 1) >> GROUP_XZ_BLOCK_SHIFT; gx <= (maxX + 1) >> GROUP_XZ_BLOCK_SHIFT; gx++) {
            for (int gy = (minY - 1) >> GROUP_Y_BLOCK_SHIFT; gy <= (maxY + 1) >> GROUP_Y_BLOCK_SHIFT; gy++) {
                for (int gz = (minZ - 1) >> GROUP_XZ_BLOCK_SHIFT; gz <= (maxZ + 1) >> GROUP_XZ_BLOCK_SHIFT; gz++) {
                    long key = SectionPos.asLong(gx, gy, gz);
                    LightUpdateBounds previous = colorDirtyBounds.get(key);
                    if (previous != null) colorDirtyBounds.put(key, previous.union(changed));
                    else if (!colorDirtyGroups.contains(key)) colorDirtyBounds.put(key, changed);
                    dirty(key, false, true);
                    if (immediate && reloadTransaction == null) dynamicPriorityGroups.add(key);
                }
            }
        }
    }

    public void discardChunk(ChunkPos chunkPos) {
        int gx = chunkPos.x >> GROUP_XZ_SECTION_SHIFT;
        int gz = chunkPos.z >> GROUP_XZ_SECTION_SHIFT;
        int minGX = gx - ((chunkPos.x & 1) == 0 ? 1 : 0);
        int maxGX = gx + ((chunkPos.x & 1) == 1 ? 1 : 0);
        int minGZ = gz - ((chunkPos.z & 1) == 0 ? 1 : 0);
        int maxGZ = gz + ((chunkPos.z & 1) == 1 ? 1 : 0);
        Iterator<Long2ObjectOpenHashMap.Entry<VertexBuffer>> iterator = buffers.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            if (SectionPos.x(key) < minGX || SectionPos.x(key) > maxGX
                || SectionPos.z(key) < minGZ || SectionPos.z(key) > maxGZ) continue;
            entry.getValue().close();
            closeTransition(key);
            iterator.remove();
            bounds.remove(key);
            parts.remove(key);
            dirty(key);
        }
        for (long key : inFlight.keySet()) {
            if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) dirty(key);
        }
        if (dynamicBatch != null) {
            for (long key : dynamicBatch.keys()) {
                if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                    && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) dirty(key);
            }
        }
        if (reloadTransaction != null) reloadTransaction.discardChunk(minGX, maxGX, minGZ, maxGZ);
    }

    public void refreshLoadedChunk(ChunkPos chunkPos) {
        int gx = chunkPos.x >> GROUP_XZ_SECTION_SHIFT;
        int gz = chunkPos.z >> GROUP_XZ_SECTION_SHIFT;
        int minGX = gx - ((chunkPos.x & 1) == 0 ? 1 : 0);
        int maxGX = gx + ((chunkPos.x & 1) == 1 ? 1 : 0);
        int minGZ = gz - ((chunkPos.z & 1) == 0 ? 1 : 0);
        int maxGZ = gz + ((chunkPos.z & 1) == 1 ? 1 : 0);
        for (long key : buffers.keySet()) {
            if (SectionPos.x(key) < minGX || SectionPos.x(key) > maxGX || SectionPos.z(key) < minGZ || SectionPos.z(key) > maxGZ)
                continue;
            parts.markChunkLoaded(key, chunkPos);
            dirty(key);
        }
        for (long key : inFlight.keySet()) {
            if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) dirty(key);
        }
        if (dynamicBatch != null) {
            for (long key : dynamicBatch.keys()) {
                if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                    && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) dirty(key);
            }
        }
        if (reloadTransaction != null) {
            for (long key : reloadTransaction.keys()) {
                if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                    && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) dirty(key);
            }
        }
    }

    void draw(Matrix4f viewMatrix, Matrix4f projectionMatrix, Camera camera, LightMaskShader shader,
                     boolean reloadInProgress, LongPredicate propagationPending) {
        Minecraft minecraft = Minecraft.getInstance();
        Frustum frustum = minecraft.levelRenderer.getFrustum();
        if (level == null) return;
        processAsync(level, frustum, !reloadInProgress, propagationPending);
        if (reloadTransaction != null && !reloadInProgress && !hasVisiblePending(frustum)) commitDefinitionReload();
        expireTransitions();
        if (buffers.isEmpty()) return;
        visibleGroups.clear();
        Vec3 cameraPos = camera.getPosition();
        for (Long2ObjectOpenHashMap.Entry<VertexBuffer> entry : buffers.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            if (entry.getValue().isInvalid()) {
                dirty(key);
                continue;
            }
            AABB box = bounds.get(key);
            if (!frustum.isVisible(box)) continue;
            if (!TerrainSectionVisibility.isVisible(box)) continue;
            if (renderer.isFullyFogged(box, cameraPos)) {
                continue;
            }
            visibleGroups.add(key);
        }
        renderer.draw(viewMatrix, projectionMatrix, camera, shader, visibleGroups, buffers, transitions);
    }

    private void expireTransitions() {
        if (transitions.isEmpty()) return;
        long now = System.nanoTime();
        var iterator = transitions.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue().startedAt() < DYNAMIC_TRANSITION_NANOS && buffers.containsKey(entry.getLongKey()))
                continue;
            entry.getValue().close();
            iterator.remove();
        }
    }

    private void closeTransition(long key) {
        Transition transition = transitions.remove(key);
        if (transition != null) transition.close();
    }

    private boolean hasVisiblePending(@Nullable Frustum frustum) {
        for (long key : dirtyGroups) {
            if (frustum == null || frustum.isVisible(bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds)))
                return true;
        }
        return false;
    }

    private void commitDefinitionReload() {
        if (reloadTransaction == null) return;

        reloadTransaction.commit(buffers, bounds, parts);
        reloadTransaction = null;
    }

    private static AABB groupBounds(long key) {
        int x = SectionPos.x(key) << GROUP_XZ_BLOCK_SHIFT;
        int y = SectionPos.y(key) << GROUP_Y_BLOCK_SHIFT;
        int z = SectionPos.z(key) << GROUP_XZ_BLOCK_SHIFT;
        int xzSize = 1 << GROUP_XZ_BLOCK_SHIFT, ySize = 1 << GROUP_Y_BLOCK_SHIFT;
        return new AABB(x - 1, y - 1, z - 1, x + xzSize + 1, y + ySize + 1, z + xzSize + 1);
    }

    OptionalLong firstVisiblePropagationGroup(ClientLevel level, LongPredicate propagationPending) {
        var minecraft = Minecraft.getInstance();
        var frustum = minecraft.levelRenderer.getFrustum();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double nearest = Double.POSITIVE_INFINITY;
        OptionalLong selected = OptionalLong.empty();
        for (long key : dirtyGroups) {
            if (!propagationPending.test(key)) continue;
            AABB box = bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds);
            if (!frustum.isVisible(box) || renderer.isFullyFogged(box, camera)) continue;
            double distance = box.getCenter().distanceToSqr(camera);
            if (distance >= nearest) continue;
            int section = level.getSectionIndex(SectionPos.y(key) << GROUP_Y_BLOCK_SHIFT);
            if (section < 0 || section >= level.getSectionsCount()) continue;
            int sx = SectionPos.x(key) << GROUP_XZ_SECTION_SHIFT;
            int sz = SectionPos.z(key) << GROUP_XZ_SECTION_SHIFT;
            boolean hasGeometry = false;
            for (int dx = 0; dx < 2 && !hasGeometry; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    var chunk = level.getChunkSource().getChunkForLighting(sx + dx, sz + dz);
                    if (chunk instanceof LevelChunk loaded && !loaded.getSections()[section].hasOnlyAir()) {
                        hasGeometry = true;
                        break;
                    }
                }
            }
            if (hasGeometry) {
                nearest = distance;
                selected = OptionalLong.of(key);
            }
        }
        return selected;
    }

    void finishCompletedMeshes(ClientLevel level) {
        processAsync(level, null, false, unused -> false);
    }

    private void processAsync(ClientLevel level, @Nullable Frustum frustum, boolean schedule,
                                     LongPredicate propagationPending) {
        RenderRegionCache regions = schedule && !dirtyGroups.isEmpty() ? new RenderRegionCache() : null;
        MeshResult result;
        while ((result = completed.poll()) != null) {
            if (result.epoch() != epoch.get()) {
                if (result.mesh() != null) result.mesh().close();
                continue;
            }
            inFlight.remove(result.key());
            if (result.version() != groupVersions.get(result.key())) {
                if (result.mesh() != null) result.mesh().close();
                continue;
            }
            if (result.error() instanceof CancellationException) continue;
            if (result.error() != null) {
                OpalLight.LOGGER.error("Failed to build colored light mesh", result.error());
                failDynamicBatch(result.key());
                failedGroups.add(result.key());
                if (result.mesh() != null) result.mesh().close();
                continue;
            }
            if (dynamicBatch != null && dynamicBatch.contains(result.key())) {
                dynamicBatch.stage(result.key(), result.mesh());
                continue;
            }
            boolean followup = deferredColorGroups.remove(result.key());
            boolean dynamicFollowup = dynamicPriorityGroups.contains(result.key());
            LightUpdateBounds followupBounds = colorDirtyBounds.get(result.key());
            applyMesh(result.key(), result.mesh(), true);
            if (followup) {
                dirty(result.key());
                if (followupBounds != null) colorDirtyBounds.put(result.key(), followupBounds);
                if (dynamicFollowup) dynamicPriorityGroups.add(result.key());
            }
        }

        if (dynamicBatch != null && dynamicBatch.isReady()) {
            commitDynamicBatch();
        }
        if (!schedule) return;
        for (int available = MESH_WORKER_COUNT - inFlight.size(); available > 0; available--) {
            if (scheduleNext(level, frustum, urgentGroups.iterator(), regions, propagationPending, true))
                continue;
            if (scheduleNext(level, frustum, dynamicPriorityGroups.iterator(), regions, propagationPending, true))
                continue;
            if (!scheduleNext(level, frustum, dirtyGroups.iterator(), regions, propagationPending, false)) break;
        }
    }

    private record PreparedMesh(VertexBuffer buffer, @Nullable LightTransitionColors colors) implements AutoCloseable {
        @Override
        public void close() {
            if (colors != null) colors.close();
            buffer.close();
        }
    }

    private void commitDynamicBatch() {
        var ready = dynamicBatch;
        if (ready == null) return;
        Long2ObjectOpenHashMap<LightMaskMeshBuilder.BuiltMesh> meshes = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<PreparedMesh> prepared = new Long2ObjectOpenHashMap<>();
        boolean smooth = true;
        for (long key : ready.keys()) {
            var mesh = ready.take(key);
            meshes.put(key, mesh);
            if (mesh == null || !buffers.containsKey(key) || parts.hasChangedBlocks(key)) smooth = false;
        }
        try {
            for (long key : ready.keys()) {
                var mesh = meshes.get(key);
                VertexBuffer old = buffers.get(key);
                if (mesh == null || old != null && mesh.unchangedVertices()) continue;
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                prepared.put(key, new PreparedMesh(buffer, null));
                buffer.bind();
                mesh.markUploaded();
                buffer.upload(mesh.mesh());
                VertexBuffer.unbind();
                if (smooth && mesh.matchingLayout()) {
                    var colors = LightTransitionColors.create(old, mesh.mesh().drawState().vertexCount());
                    prepared.put(key, new PreparedMesh(buffer, colors));
                }
            }
        } catch (Throwable error) {
            VertexBuffer.unbind();
            for (PreparedMesh mesh : prepared.values()) mesh.close();
            for (var mesh : meshes.values()) if (mesh != null) mesh.close();
            failedGroups.addAll(ready.keys());
            ready.close();
            dynamicBatch = null;
            OpalLight.LOGGER.error("Failed to prepare colored light batch", error);
            return;
        }
        // 全部上传成功后才替换可见缓冲区，同批过渡从同一时刻开始。
        dynamicBatch = null;
        long startedAt = System.nanoTime();
        for (long key : ready.keys()) applyMesh(key, meshes.get(key), smooth, startedAt, prepared.get(key));
        ready.close();
    }

    private void applyMesh(long key, @Nullable LightMaskMeshBuilder.BuiltMesh mesh, boolean stageReload) {
        applyMesh(key, mesh, stageReload, System.nanoTime(), null);
    }

    private void applyMesh(long key, @Nullable LightMaskMeshBuilder.BuiltMesh mesh, boolean stageReload, long startedAt, @Nullable PreparedMesh prepared) {
        VertexBuffer oldBuffer = buffers.get(key);
        if (mesh == null) {
            closeTransition(key);
            if (stageReload && reloadTransaction != null) {
                reloadTransaction.stage(key, null, null);
            } else {
                if (oldBuffer != null) {
                    oldBuffer.close();
                    buffers.remove(key);
                }
                bounds.remove(key);
                parts.remove(key);
            }
            dirtyGroups.remove(key);
            colorDirtyGroups.remove(key);
            colorDirtyBounds.remove(key);
            dynamicPriorityGroups.remove(key);
            urgentGroups.remove(key);
            return;
        }
        // 量化后的顶点未变时保留 GPU 缓冲，但仍提交最新几何与失效状态。
        if (oldBuffer != null && reloadTransaction == null && mesh.unchangedVertices()) {
            try (mesh) {
                closeTransition(key);
                parts.replace(key, mesh.geometry());
                dirtyGroups.remove(key);
                colorDirtyGroups.remove(key);
                colorDirtyBounds.remove(key);
                dynamicPriorityGroups.remove(key);
                urgentGroups.remove(key);
            }
            return;
        }
        VertexBuffer replacement = prepared == null ? new VertexBuffer(VertexBuffer.Usage.STATIC) : prepared.buffer();
        try (mesh) {
            if (prepared == null) {
                replacement.bind();
                mesh.markUploaded();
                replacement.upload(mesh.mesh());
                VertexBuffer.unbind();
            }
            if (stageReload && reloadTransaction != null) {
                reloadTransaction.stage(key, replacement, mesh.geometry());
            } else {
                boolean smooth = stageReload && dynamicPriorityGroups.contains(key) && oldBuffer != null
                    && !parts.hasChangedBlocks(key);
                closeTransition(key);
                buffers.put(key, replacement);
                if (oldBuffer != null) {
                    if (smooth) {
                        LightTransitionColors colors = prepared != null ? prepared.colors()
                            : mesh.matchingLayout() ? LightTransitionColors.create(oldBuffer, mesh.mesh().drawState().vertexCount()) : null;
                        transitions.put(key, new Transition(oldBuffer, startedAt, colors));
                    } else oldBuffer.close();
                }
                parts.replace(key, mesh.geometry());
            }
            dirtyGroups.remove(key);
            colorDirtyGroups.remove(key);
            colorDirtyBounds.remove(key);
            dynamicPriorityGroups.remove(key);
            urgentGroups.remove(key);
        } catch (Throwable error) {
            replacement.close();
            VertexBuffer.unbind();
            OpalLight.LOGGER.error("Failed to upload colored light mesh", error);
            failedGroups.add(key);
        }
    }

    private boolean scheduleNext(ClientLevel level, @Nullable Frustum frustum, LongIterator iterator,
                                        RenderRegionCache regions, LongPredicate propagationPending,
                                        boolean dynamicPriority) {
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (!dirtyGroups.contains(key)) continue;
            if (suspendedDynamicGroups.contains(key)) continue;
            if (dynamicBatch != null && dynamicBatch.hasResult(key)) continue;
            if (inFlight.containsKey(key)) continue;
            if (failedGroups.contains(key)) continue;
            if (!dynamicPriority && propagationPending.test(key)) continue;
            AABB box = bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds);
            if ((dynamicBatch == null || !dynamicBatch.contains(key)) && frustum != null && !frustum.isVisible(box))
                continue;
            try {
                LightMaskMeshBuilder.MeshSnapshot snapshot = LightMaskMeshBuilder.snapshot(colors, level, key, epoch.get(), regions);
                if (snapshot == null) {
                    if (dynamicBatch != null && dynamicBatch.contains(key)) dynamicBatch.stage(key, null);
                    else applyMesh(key, null, true);
                    return true;
                }
                long version = groupVersions.get(key);
                Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry = parts.previousGeometry(key);
                LongSet changedBlocks = parts.changedBlocks(key);
                boolean geometryOnly = !colorDirtyGroups.contains(key);
                // 重载时旧几何尚未替换，必须全量着色。
                LightUpdateBounds colorChanges = reloadTransaction == null ? colorDirtyBounds.get(key) : null;
                AtomicBoolean cancellation = new AtomicBoolean();
                inFlight.put(key, cancellation);
                meshWorkers.execute(() -> {
                    try {
                        LightMaskMeshBuilder.BuiltMesh mesh = LightMaskMeshBuilder.build(snapshot, previousGeometry, changedBlocks,
                            cancellation::get, geometryOnly, colorChanges);
                        if (cancellation.get()) {
                            if (mesh != null) mesh.close();
                            throw new CancellationException();
                        }
                        publish(new MeshResult(snapshot.key(), snapshot.epoch(), version, mesh,
                            null));
                    } catch (CancellationException cancelled) {
                        if (snapshot.epoch() == epoch.get())
                            publish(new MeshResult(snapshot.key(), snapshot.epoch(), version, null,
                                cancelled));
                    } catch (Throwable error) {
                        publish(new MeshResult(snapshot.key(), snapshot.epoch(), version, null,
                            error));
                    }
                });
            } catch (Throwable error) {
                OpalLight.LOGGER.error("Failed to snapshot colored light mesh", error);
                failDynamicBatch(key);
                failedGroups.add(key);
                return false;
            }
            return true;
        }
        return false;
    }

    public void beginDefinitionReload() {
        LongOpenHashSet carryDirty = new LongOpenHashSet(dirtyGroups);
        carryDirty.addAll(inFlight.keySet());
        if (reloadTransaction != null) {
            carryDirty.addAll(reloadTransaction.keys());
            reloadTransaction.close();
        }
        reloadTransaction = new LightMaskReloadTransaction();
        clearJobs();
        dirtyGroups.addAll(carryDirty);
        colorDirtyGroups.addAll(carryDirty);
    }

    private void failDynamicBatch(long key) {
        if (dynamicBatch == null || !dynamicBatch.contains(key)) return;
        failedGroups.addAll(dynamicBatch.keys());
        for (long member : dynamicBatch.keys()) {
            AtomicBoolean cancellation = inFlight.get(member);
            if (cancellation != null) cancellation.set(true);
            groupVersions.addTo(member, 1);
        }
        dynamicBatch.close();
        dynamicBatch = null;
    }

    private synchronized void publish(MeshResult result) {
        if (result.epoch() != epoch.get()) {
            if (result.mesh() != null) result.mesh().close();
        } else completed.add(result);
    }

    private synchronized void clearJobs() {
        if (dynamicBatch != null) dynamicBatch.close();
        dynamicBatch = null;
        suspendedDynamicGroups.clear();
        batchingDynamic = false;
        epoch.incrementAndGet();
        for (AtomicBoolean cancellation : inFlight.values()) cancellation.set(true);
        inFlight.clear();
        urgentGroups.clear();
        dynamicPriorityGroups.clear();
        for (Transition transition : transitions.values()) transition.close();
        transitions.clear();
        groupVersions.clear();
        failedGroups.clear();
        MeshResult result;
        while ((result = completed.poll()) != null) {
            if (result.mesh() != null) result.mesh().close();
        }
        dirtyGroups.clear();
        colorDirtyGroups.clear();
        colorDirtyBounds.clear();
        batchedDirtyGroups.clear();
        batchedDeferredGroups.clear();
        batchedHardGroups.clear();
        deferredColorGroups.clear();
        batchingDirty = false;
    }

    public void invalidate() {
        clearJobs();
        renderer.clearTerrainFog();
        if (reloadTransaction != null) reloadTransaction.close();
        reloadTransaction = null;
        for (VertexBuffer buffer : buffers.values()) buffer.close();
        buffers.clear();
        parts.clear();
        bounds.clear();
    }
}
