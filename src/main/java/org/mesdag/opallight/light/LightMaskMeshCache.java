package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mesdag.opallight.light.LightManager.lightMaskShader;

public final class LightMaskMeshCache {
    /// 每组覆盖水平方向二乘二个分段，高度方向一个分段。
    static final int GROUP_XZ_SECTION_SHIFT = 1;
    static final int GROUP_Y_SECTION_SHIFT = 0;
    static final int GROUP_XZ_BLOCK_SHIFT = 4 + GROUP_XZ_SECTION_SHIFT;
    static final int GROUP_Y_BLOCK_SHIFT = 4 + GROUP_Y_SECTION_SHIFT;
    private static final Long2ObjectOpenHashMap<VertexBuffer> buffers = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<AABB> bounds = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet dirtyGroups = new LongOpenHashSet();
    private static final LongOpenHashSet urgentGroups = new LongOpenHashSet();
    private static final LongOpenHashSet colorDirtyGroups = new LongOpenHashSet();
    private static final LongOpenHashSet batchedDirtyGroups = new LongOpenHashSet();
    private static boolean batchingDirty;
    private static final Long2ObjectOpenHashMap<AtomicBoolean> inFlight = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap groupVersions = new Long2LongOpenHashMap();
    private static final LongOpenHashSet failedGroups = new LongOpenHashSet();
    private static final LightMaskMeshParts parts = new LightMaskMeshParts();
    private static @Nullable LightMaskReloadTransaction reloadTransaction;
    private static final ConcurrentLinkedQueue<MeshResult> completed = new ConcurrentLinkedQueue<>();
    private static final ExecutorService meshWorkers = Executors.newFixedThreadPool(2, task -> {
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
        AtomicBoolean cancellation = inFlight.get(key);
        if (cancellation != null) cancellation.set(true);
        if (!geometryOnly) colorDirtyGroups.add(key);
        if (batchingDirty) {
            batchedDirtyGroups.add(key);
            return;
        }
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
        /// 入队时已记录颜色失效；提交批次只更新版本，保留纯几何变化的复用资格。
        for (long key : batchedDirtyGroups) dirty(key, true);
        batchedDirtyGroups.clear();
    }

    public static void invalidateChangedGeometry(long packedPos) {
        markBlockAffected(packedPos, true);
    }

    public static void markLightingChanged(long packedPos) {
        markBlockAffected(packedPos, false);
    }

    private static void markBlockAffected(long packedPos, boolean immediate) {
        int x = BlockPos.getX(packedPos), y = BlockPos.getY(packedPos), z = BlockPos.getZ(packedPos);
        for (int gx = (x - 1) >> GROUP_XZ_BLOCK_SHIFT; gx <= (x + 1) >> GROUP_XZ_BLOCK_SHIFT; gx++) {
            for (int gy = (y - 1) >> GROUP_Y_BLOCK_SHIFT; gy <= (y + 1) >> GROUP_Y_BLOCK_SHIFT; gy++) {
                for (int gz = (z - 1) >> GROUP_XZ_BLOCK_SHIFT; gz <= (z + 1) >> GROUP_XZ_BLOCK_SHIFT; gz++) {
                    long key = SectionPos.asLong(gx, gy, gz);
                    boolean hasBuffer = buffers.containsKey(key);
                    if (!hasBuffer && (!immediate || !(inFlight.containsKey(key) || dirtyGroups.contains(key)))) continue;
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
        for (int gx = (minX - 1) >> GROUP_XZ_BLOCK_SHIFT; gx <= (maxX + 1) >> GROUP_XZ_BLOCK_SHIFT; gx++) {
            for (int gy = (minY - 1) >> GROUP_Y_BLOCK_SHIFT; gy <= (maxY + 1) >> GROUP_Y_BLOCK_SHIFT; gy++) {
                for (int gz = (minZ - 1) >> GROUP_XZ_BLOCK_SHIFT; gz <= (maxZ + 1) >> GROUP_XZ_BLOCK_SHIFT; gz++) {
                    dirty(SectionPos.asLong(gx, gy, gz));
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
    }

    public static void draw(Matrix4f viewMatrix, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        Frustum frustum = minecraft.levelRenderer.getFrustum();
        ClientLevel level = minecraft.level;
        if (level == null || lightMaskShader == null) return;
        processAsync(level, frustum, !LightManager.isReloadInProgress());
        if (reloadTransaction != null && !LightManager.isReloadInProgress() && !hasVisiblePending(frustum)) commitDefinitionReload();
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
            if (frustum != null && !frustum.isVisible(box)) continue;
            if (LightMaskRenderer.isFullyFogged(box, cameraPos)) {
                continue;
            }
            visibleGroups.add(key);
        }
        LightMaskRenderer.draw(viewMatrix, camera, visibleGroups, buffers);
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

    static OptionalLong firstVisiblePropagationGroup(ClientLevel level) {
        var minecraft = Minecraft.getInstance();
        var frustum = minecraft.levelRenderer.getFrustum();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double nearest = Double.POSITIVE_INFINITY;
        OptionalLong selected = OptionalLong.empty();
        for (long key : dirtyGroups) {
            if (!LightPropagator.isGroupPropagationPending(key)) continue;
            AABB box = bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds);
            if (frustum != null && !frustum.isVisible(box) || LightMaskRenderer.isFullyFogged(box, camera)) continue;
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

    private static void processAsync(ClientLevel level, @Nullable Frustum frustum, boolean schedule) {
        /// 同一帧捕获的区域共享原版区块快照，下一帧重新捕获世界状态。
        RenderRegionCache regions = schedule && !dirtyGroups.isEmpty() ? new RenderRegionCache() : null;
        if (schedule) rebuildUrgent(level, frustum, regions);
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
            applyMesh(result.key(), result.mesh(), true);
        }

        if (!schedule) return;
        for (int available = 2 - inFlight.size(); available > 0; available--) {
            if (!scheduleNext(level, frustum, dirtyGroups.iterator(), regions)) break;
        }
    }

    private static void applyMesh(long key, @Nullable LightMaskMeshBuilder.BuiltMesh mesh, boolean stageReload) {
        VertexBuffer oldBuffer = buffers.get(key);
        if (mesh == null) {
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
            return;
        }
        VertexBuffer replacement = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
            replacement.bind();
            replacement.upload(mesh.mesh());
            VertexBuffer.unbind();
            if (stageReload && reloadTransaction != null) {
                reloadTransaction.stage(key, replacement, mesh.geometry());
            } else {
                buffers.put(key, replacement);
                if (oldBuffer != null) oldBuffer.close();
                parts.replace(key, mesh.geometry());
            }
            dirtyGroups.remove(key);
            colorDirtyGroups.remove(key);
        } catch (Throwable error) {
            replacement.close();
            VertexBuffer.unbind();
            org.mesdag.opallight.OpalLight.LOGGER.error("Failed to upload colored light mesh", error);
            failedGroups.add(key);
        } finally {
            mesh.close();
        }
    }

    private static void rebuildUrgent(ClientLevel level, @Nullable Frustum frustum, RenderRegionCache regions) {
        /// 可见几何变化先构建完整替换网格，再在本帧统一交换缓冲区。
        LongIterator iterator = urgentGroups.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (!dirtyGroups.contains(key) || !buffers.containsKey(key)) {
                iterator.remove();
                continue;
            }
            AABB box = bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds);
            if (frustum != null && !frustum.isVisible(box)) continue;
            iterator.remove();
            try {
                LightMaskMeshBuilder.MeshSnapshot snapshot = LightMaskMeshBuilder.snapshot(level, key, epoch.get(), regions);
                LightMaskMeshBuilder.BuiltMesh mesh = snapshot == null ? null : LightMaskMeshBuilder.build(snapshot,
                        parts.previousGeometry(key), parts.changedBlocks(key), () -> false, !colorDirtyGroups.contains(key));
                applyMesh(key, mesh, false);
            } catch (Throwable error) {
                org.mesdag.opallight.OpalLight.LOGGER.error("Failed to build colored light mesh", error);
                failedGroups.add(key);
            }
        }
    }

    private static boolean scheduleNext(ClientLevel level, @Nullable Frustum frustum, LongIterator iterator, RenderRegionCache regions) {
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (inFlight.containsKey(key)) continue;
            if (failedGroups.contains(key)) continue;
            if (LightPropagator.isGroupPropagationPending(key)) continue;
            AABB box = bounds.computeIfAbsent(key, LightMaskMeshCache::groupBounds);
            if (frustum != null && !frustum.isVisible(box)) continue;
            try {
                LightMaskMeshBuilder.MeshSnapshot snapshot = LightMaskMeshBuilder.snapshot(level, key, epoch.get(), regions);
                if (snapshot == null) {
                    /// 已知没有彩光或模型的组直接提交空结果，不占用工作线程和完成队列。
                    applyMesh(key, null, true);
                    return true;
                }
                long version = groupVersions.get(key);
                Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry = parts.previousGeometry(key);
                LongSet changedBlocks = parts.changedBlocks(key);
                boolean geometryOnly = !colorDirtyGroups.contains(key);
                AtomicBoolean cancellation = new AtomicBoolean();
                inFlight.put(key, cancellation);
                meshWorkers.execute(() -> {
                    try {
                        LightMaskMeshBuilder.BuiltMesh mesh = LightMaskMeshBuilder.build(snapshot, previousGeometry, changedBlocks,
                                cancellation::get, geometryOnly);
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
        groupVersions.clear();
        failedGroups.clear();
        MeshResult result;
        while ((result = completed.poll()) != null) {
            if (result.mesh() != null) result.mesh().close();
        }
        dirtyGroups.clear();
        colorDirtyGroups.clear();
        batchedDirtyGroups.clear();
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
