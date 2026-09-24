package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/// 仅暂存彩光定义变更涉及的网格，并在可见部分准备完成后一次提交。
final class LightMaskReloadTransaction implements AutoCloseable {
    private record Ready(@Nullable VertexBuffer buffer,
                         @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry) {}

    private final Long2ObjectOpenHashMap<Ready> ready = new Long2ObjectOpenHashMap<>();

    LongOpenHashSet keys() {
        return new LongOpenHashSet(ready.keySet());
    }

    void stage(long key, @Nullable VertexBuffer buffer,
               @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry) {
        Ready old = ready.put(key, new Ready(buffer, geometry));
        if (old != null && old.buffer() != null) old.buffer().close();
    }

    void invalidate(long key) {
        Ready old = ready.remove(key);
        if (old != null && old.buffer() != null) old.buffer().close();
    }

    void discardChunk(int minGX, int maxGX, int minGZ, int maxGZ) {
        LongOpenHashSet discard = new LongOpenHashSet();
        for (long key : ready.keySet()) {
            if (SectionPos.x(key) >= minGX && SectionPos.x(key) <= maxGX
                    && SectionPos.z(key) >= minGZ && SectionPos.z(key) <= maxGZ) discard.add(key);
        }
        for (long key : discard) invalidate(key);
    }

    void commit(Long2ObjectOpenHashMap<VertexBuffer> buffers, Long2ObjectOpenHashMap<AABB> bounds, LightMaskMeshParts parts) {
        for (var entry : ready.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            Ready replacement = entry.getValue();
            VertexBuffer old = buffers.get(key);
            if (replacement.buffer() == null) {
                if (old != null) old.close();
                buffers.remove(key);
                bounds.remove(key);
                parts.remove(key);
            } else {
                buffers.put(key, replacement.buffer());
                if (old != null) old.close();
                if (replacement.geometry() != null) {
                    parts.replace(key, replacement.geometry());
                }
            }
        }
        ready.clear();
    }

    @Override
    public void close() {
        for (Ready replacement : ready.values()) {
            if (replacement.buffer() != null) replacement.buffer().close();
        }
        ready.clear();
    }
}
