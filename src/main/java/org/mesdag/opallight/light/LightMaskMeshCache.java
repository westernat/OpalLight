package org.mesdag.opallight.light;

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

import java.util.Iterator;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

import static org.mesdag.opallight.light.LightMeshLayout.*;

public final class LightMaskMeshCache {
    private static final Long2ObjectOpenHashMap<VertexBuffer> buffers = new Long2ObjectOpenHashMap<>();
    static final long DYNAMIC_TRANSITION_NANOS = 70_000_000L;

    record Transition(VertexBuffer previous, long startedAt,
                      @Nullable LightTransitionColors colors) implements AutoCloseable {
        @Override
        public void close() {
            if (colors != null) colors.close();
            previous.close();
        }
    }

    private static final Long2ObjectOpenHashMap<Transition> transitions = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<AABB> bounds = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet dirtyGroups = new LongOpenHashSet();
    private static final LongOpenHashSet urgentGroups = new LongOpenHashSet();
    private static final LongOpenHashSet dynamicPriorityGroups = new LongOpenHashSet();
    private static final LongOpenHashSet colorDirtyGroups = new LongOpenHashSet();
    // 颜色失效但无范围表示全量重算；局部范围保留至网格成功发布。
    private static final Long2ObjectOpenHashMap<LightUpdateBounds> colorDirtyBounds = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet batchedDirtyGroups = new LongOpenHashSet();
    private static final LongOpenHashSet batchedDeferredGroups = new LongOpenHashSet();
    private static final LongOpenHashSet batchedHardGroups = new LongOpenHashSet();
    private static final LongOpenHashSet deferredColorGroups = new LongOpenHashSet();
    private static boolean batchingDirty;
    private static final Long2ObjectOpenHashMap<AtomicBoolean> inFlight = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap groupVersions = new Long2LongOpenHashMap();
    private static final LongOpenHashSet failedGroups = new LongOpenHashSet();
    private static final LightMaskMeshParts parts = new LightMaskMeshParts();
    private static @Nullable LightMaskReloadTransaction reloadTransaction;
    private static final ConcurrentLinkedQueue<MeshResult> completed = new ConcurrentLinkedQueue<>();
    private static final int MESH_WORKER_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    private static final ExecutorService meshWorkers = Executors.newFixedThreadPool(MESH_WORKER_COUNT, task -> {
        Thread thread = new Thread(task, "OpalLight mesh builder");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong epoch = new AtomicLong();
    private static final LongArrayList visibleGroups = new LongArrayList();

    private record MeshResult(long key, long epoch, long version, @Nullable LightMaskMeshBuilder.BuiltMesh mesh,
                              @Nullable Throwable error) {}

    static boolean hasMeshes() {
        return !buffers.isEmpty();
    }

    private static void dirty(long key) {
        dirty(key, false);
    }

    private static void dirty(long key, boolean geometryOnly) {
        dirty(key, geometryOnly, false);
    }

    private static void dirty(long key, boolean geometryOnly, boolean deferColor) {
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
        if (deferColor && cancellation != null && !cancellation.get() && reloadTransaction == null) {
            // 先发布在途网格再追赶最新颜色，避免持续取消导致饥饿。
            deferredColorGroups.add(key);
            return;
        }
        deferredColorGroups.remove(key);
        if (cancellation != null) cancellation.set(true);
        if (reloadTransaction != null) reloadTransaction.invalidate(key);
        failedGroups.remove(key);
        dirtyGroups.add(key);
        groupVersions.addTo(key, 1);
    }

    public static void beginDirtyBatch() {
        batchingDirty = true;
    }

    public static void endDirtyBatch() {
        batchingDirty = false;
        for (long key : batchedDirtyGroups) dirty(key, true, batchedDeferredGroups.contains(key));
        batchedDirtyGroups.clear();
        batchedDeferredGroups.clear();
        batchedHardGroups.clear();
    }

    public static void invalidateChangedGeometry(long packedPos) {
        markBlockAffected(packedPos, true);
    }

    public static void markLightingChanged(long packedPos) {
        markBlockAffected(packedPos, false);
    }

    private static void markBlockAffected(long packedPos, boolean immediate) {
        int x = BlockPos.getX(packedPos), y = BlockPos.getY(packedPos), z = BlockPos.getZ(packedPos);
        Boolean colorNear = null;
        for (int gx = (x - 1) >> GROUP_XZ_BLOCK_SHIFT; gx <= (x + 1) >> GROUP_XZ_BLOCK_SHIFT; gx++) {
            for (int gy = (y - 1) >> GROUP_Y_BLOCK_SHIFT; gy <= (y + 1) >> GROUP_Y_BLOCK_SHIFT; gy++) {
                for (int gz = (z - 1) >> GROUP_XZ_BLOCK_SHIFT; gz <= (z + 1) >> GROUP_XZ_BLOCK_SHIFT; gz++) {
                    long key = SectionPos.asLong(gx, gy, gz);
                    boolean hasBuffer = buffers.containsKey(key);
                    boolean staged = reloadTransaction != null && reloadTransaction.contains(key);
                    if (!hasBuffer && (!immediate || !(inFlight.containsKey(key) || dirtyGroups.contains(key) || staged))) continue;
                    if (hasBuffer && !parts.hasCachedGeometryNear(key, x, y, z)) {
                        if (colorNear == null) colorNear = LightColorCache.INSTANCE.hasColorNear(BlockPos.of(packedPos));
                        if (!colorNear && !inFlight.containsKey(key) && !dirtyGroups.contains(key) && !staged) continue;
                    }
                    parts.markBlockChanged(key, x, y, z);
                    dirty(key, true);
                    if (immediate && hasBuffer) urgentGroups.add(key);
                }
            }
        }
    }

    public static void markDirtyAroundSection(long sectionKey) {
        int x = SectionPos.x(sectionKey) << 4, y = SectionPos.y(sectionKey) << 4, z = SectionPos.z(sectionKey) << 4;
        markDirtyForBounds(x, y, z, x + 15, y + 15, z + 15);
    }

    static void markDirtyForBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        markColorChanged(minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    static void markColorChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean immediate) {
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

    public static void discardChunk(ChunkPos chunkPos) {
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
        if (reloadTransaction != null) reloadTransaction.discardChunk(minGX, maxGX, minGZ, maxGZ);
    }

    public static void refreshLoadedChunk(ChunkPos chunkPos) {
        int gx = chunkPos.x >> GROUP_XZ_SECTION_SHIFT;
        int gz = chunkPos.z >> GROUP_XZ_SECTION_SHIFT;
        int minGX = gx - ((chunkPos.x & 1) == 0 ? 1 : 0);
        int maxGX = gx + ((chunkPos.x & 1) == 1 ? 1 : 0);
        int minGZ = gz - ((chunkPos.z & 1) == 0 ? 1 : 0);
        int maxGZ = gz + ((chunkPos.z & 1) == 1 ? 1 : 0);
        for (long key : buffers.keySet()) {
            if (SectionPos.x(key) < minGX || SectionPos.x(key) > maxGX || SectionPos.z(key) < minGZ || SectionPos.z(key) > maxGZ) continue;
            parts.markChunkLoaded(key, chunkPos);
            dirty(key);
        }
        for (long key : inFlight.keySet()) {
            if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                    && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) dirty(key);
        }
        if (reloadTransaction != null) {
            for (long key : reloadTransaction.keys()) {
                if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                        && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) dirty(key);
            }
        }
    }

    static void draw(Matrix4f viewMatrix, Matrix4f projectionMatrix, Camera camera, LightMaskShader shader,
                            boolean reloadInProgress, LongPredicate propagationPending) {
        Minecraft minecraft = Minecraft.getInstance();
        Frustum frustum = minecraft.levelRenderer.getFrustum();
        ClientLevel level = minecraft.level;
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
            if (LightMaskRenderer.isFullyFogged(box, cameraPos)) {
                continue;
            }
            visibleGroups.add(key);
        }
        LightMaskRenderer.draw(viewMatrix, projectionMatrix, camera, shader, visibleGroups, buffers, transitions);
    }

    private static void expireTransitions() {
        if (transitions.isEmpty()) return;
        long now = System.nanoTime();
        var iterator = transitions.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue().startedAt() < DYNAMIC_TRANSITION_NANOS && buffers.containsKey(entry.getLongKey())) continue;
            entry.getValue().close();
            iterator.remove();
        }
    }

    private static void closeTransition(long key) {
        Transition transition = transitions.remove(key);
        if (transition != null) transition.close();
    }

    private static boolean hasVisiblePending(@Nullable Frustum frustum) {
        for (long key : dirtyGroups) {
            if (frustum == null || frustum.isVisible(bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds))) return true;
        }
        return false;
    }

    private static void commitDefinitionReload() {
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

    static OptionalLong firstVisiblePropagationGroup(ClientLevel level, LongPredicate propagationPending) {
        var minecraft = Minecraft.getInstance();
        var frustum = minecraft.levelRenderer.getFrustum();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double nearest = Double.POSITIVE_INFINITY;
        OptionalLong selected = OptionalLong.empty();
        for (long key : dirtyGroups) {
            if (!propagationPending.test(key)) continue;
            AABB box = bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds);
            if (!frustum.isVisible(box) || LightMaskRenderer.isFullyFogged(box, camera)) continue;
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

    private static void processAsync(ClientLevel level, @Nullable Frustum frustum, boolean schedule,
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
                org.mesdag.opallight.OpalLight.LOGGER.error("Failed to build colored light mesh", result.error());
                failedGroups.add(result.key());
                if (result.mesh() != null) result.mesh().close();
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

        if (!schedule) return;
        for (int available = MESH_WORKER_COUNT - inFlight.size(); available > 0; available--) {
            if (scheduleNext(level, frustum, urgentGroups.iterator(), regions, propagationPending, true))
                continue;
            if (scheduleNext(level, frustum, dynamicPriorityGroups.iterator(), regions, propagationPending, true))
                continue;
            if (!scheduleNext(level, frustum, dirtyGroups.iterator(), regions, propagationPending, false)) break;
        }
    }

    private static void applyMesh(long key, @Nullable LightMaskMeshBuilder.BuiltMesh mesh, boolean stageReload) {
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
                parts.replace(key, mesh.geometry());
                dirtyGroups.remove(key);
                colorDirtyGroups.remove(key);
                colorDirtyBounds.remove(key);
                dynamicPriorityGroups.remove(key);
                urgentGroups.remove(key);
            }
            return;
        }
        VertexBuffer replacement = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try (mesh) {
            replacement.bind();
            mesh.markUploaded();
            replacement.upload(mesh.mesh());
            VertexBuffer.unbind();
            if (stageReload && reloadTransaction != null) {
                reloadTransaction.stage(key, replacement, mesh.geometry());
            } else {
                boolean smooth = stageReload && dynamicPriorityGroups.contains(key) && oldBuffer != null
                    && !parts.hasChangedBlocks(key);
                closeTransition(key);
                buffers.put(key, replacement);
                if (oldBuffer != null) {
                    if (smooth) {
                        LightTransitionColors colors = mesh.matchingLayout() ? LightTransitionColors.create(oldBuffer, mesh.mesh().drawState().vertexCount()) : null;
                        transitions.put(key, new Transition(oldBuffer, System.nanoTime(), colors));
                    }
                    else oldBuffer.close();
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
            org.mesdag.opallight.OpalLight.LOGGER.error("Failed to upload colored light mesh", error);
            failedGroups.add(key);
        }
    }

    private static boolean scheduleNext(ClientLevel level, @Nullable Frustum frustum, LongIterator iterator,
                                        RenderRegionCache regions, LongPredicate propagationPending,
                                        boolean dynamicPriority) {
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (!dirtyGroups.contains(key)) continue;
            if (inFlight.containsKey(key)) continue;
            if (failedGroups.contains(key)) continue;
            if (!dynamicPriority && propagationPending.test(key)) continue;
            AABB box = bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds);
            if (frustum != null && !frustum.isVisible(box)) continue;
            try {
                LightMaskMeshBuilder.MeshSnapshot snapshot = LightMaskMeshBuilder.snapshot(level, key, epoch.get(), regions);
                if (snapshot == null) {
                    applyMesh(key, null, true);
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
                        completed.add(new MeshResult(snapshot.key(), snapshot.epoch(), version, mesh,
                                null));
                    } catch (CancellationException cancelled) {
                        if (snapshot.epoch() == epoch.get()) completed.add(new MeshResult(snapshot.key(), snapshot.epoch(), version, null,
                                cancelled));
                    } catch (Throwable error) {
                        completed.add(new MeshResult(snapshot.key(), snapshot.epoch(), version, null,
                                error));
                    }
                });
            } catch (Throwable error) {
                org.mesdag.opallight.OpalLight.LOGGER.error("Failed to snapshot colored light mesh", error);
                failedGroups.add(key);
                return false;
            }
            return true;
        }
        return false;
    }

    public static void beginDefinitionReload() {
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

    private static void clearJobs() {
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

    public static void invalidate() {
        clearJobs();
        if (reloadTransaction != null) reloadTransaction.close();
        reloadTransaction = null;
        for (VertexBuffer buffer : buffers.values()) buffer.close();
        buffers.clear();
        parts.clear();
        bounds.clear();
    }
}
