package org.mesdag.opallight.light.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jetbrains.annotations.Nullable;

final class LightMeshBatch<T extends AutoCloseable> implements AutoCloseable {
    private final LongOpenHashSet keys;
    private final Long2ObjectOpenHashMap<T> ready = new Long2ObjectOpenHashMap<>();

    LightMeshBatch(LongSet keys) {
        this.keys = new LongOpenHashSet(keys);
    }

    LongSet keys() {
        return keys;
    }

    boolean contains(long key) {
        return keys.contains(key);
    }

    boolean hasResult(long key) {
        return ready.containsKey(key);
    }

    boolean isReady() {
        return ready.size() == keys.size();
    }

    void stage(long key, @Nullable T result) {
        if (!keys.contains(key)) throw new IllegalArgumentException("Unknown mesh batch member");
        invalidate(key);
        ready.put(key, result);
    }

    void invalidate(long key) {
        release(ready.remove(key));
    }

    @Nullable T take(long key) {
        return ready.remove(key);
    }

    private static void release(@Nullable AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to release staged mesh", error);
        }
    }

    @Override
    public void close() {
        for (T value : ready.values()) release(value);
        ready.clear();
    }
}
