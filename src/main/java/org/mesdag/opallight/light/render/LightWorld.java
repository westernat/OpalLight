package org.mesdag.opallight.light.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;
import org.mesdag.opallight.light.engine.DynamicLights;
import org.mesdag.opallight.light.engine.LightColorCache;
import org.mesdag.opallight.light.engine.LightPropagator;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// 同名维度重新连接后也不能复用旧世界的任务和网格。
public final class LightWorld {
    private static final Map<ClientLevel, LightWorld> worlds = new IdentityHashMap<>();

    final ClientLevel level;
    final LongOpenHashSet loadedChunks = new LongOpenHashSet();
    private final LightColorCache colors;
    private final LightPropagator propagator;
    private final LightMaskMeshCache meshes;
    boolean reloadInProgress;
    Matrix4f viewMatrix, projectionMatrix;

    private LightWorld(ClientLevel level) {
        this.level = level;
        colors = new LightColorCache(level);
        propagator = new LightPropagator(colors);
        meshes = new LightMaskMeshCache(level, colors);
    }

    public static LightWorld get(ClientLevel level) {
        return worlds.computeIfAbsent(level, LightWorld::new);
    }

    static List<LightWorld> all() {
        return List.copyOf(worlds.values());
    }

    static void unload(ClientLevel level) {
        LightWorld world = worlds.remove(level);
        if (world != null) world.reset();
        DynamicLights.clear(level);
    }

    static void clear() {
        for (var world : all()) unload(world.level);
    }

    void reset() {
        propagator.clearAllSources();
        meshes.invalidate();
        colors.clearAll();
        DynamicLights.clear(level);
        reloadInProgress = false;
        viewMatrix = null;
        projectionMatrix = null;
    }

    void reindex() {
        for (long key : loadedChunks) {
            ChunkPos pos = new ChunkPos(key);
            propagator.indexChunk(level, pos);
            propagator.scheduleChunkAndNeighbors(pos.x, pos.z);
        }
    }

    public LightColorCache colors() {
        return colors;
    }

    public LightPropagator propagator() {
        return propagator;
    }

    public LightMaskMeshCache meshes() {
        return meshes;
    }
}
