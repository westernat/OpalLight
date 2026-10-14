package org.mesdag.opallight.light.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.OpalLight;
import org.mesdag.opallight.light.color.OpalColor;
import org.mesdag.opallight.light.data.LightDataLoader;
import org.mesdag.opallight.light.data.LightProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DynamicLights {
    private static volatile List<Registration> collectors = List.of();
    private static final Set<Handle> handles = ConcurrentHashMap.newKeySet();
    private static long nextKey = Long.MIN_VALUE;

    private DynamicLights() {}

    @FunctionalInterface
    public interface Collector {
        void collect(ClientLevel level, Sink sink);
    }

    public interface Sink {
        void point(long id, Vec3 position, LightProfile profile, int lightLevel);

        default void point(long id, Vec3 position, OpalColor color, int lightLevel) {
            point(id, position, new LightProfile(color, null), lightLevel);
        }

        void item(long id, Vec3 position, ItemStack stack, @Nullable Entity owner);

        void entity(long id, Vec3 position, Entity entity);

        void line(long id, Vec3 start, Vec3 end, OpalColor color, int lightLevel);
    }

    // 采集器在客户端线程执行，标识仅需在同一个采集器内保持稳定。
    public static synchronized AutoCloseable registerCollector(ResourceLocation id, Collector collector) {
        if (collector == null) throw new IllegalArgumentException("Light collector is required");
        for (var registered : collectors) {
            if (registered.id.equals(id)) throw new IllegalArgumentException("Duplicate light collector: " + id);
        }
        var registration = new Registration(id, collector);
        var updated = new ArrayList<>(collectors);
        updated.add(registration);
        collectors = List.copyOf(updated);
        return () -> {
            synchronized (DynamicLights.class) {
                var remaining = new ArrayList<>(collectors);
                remaining.remove(registration);
                collectors = List.copyOf(remaining);
            }
        };
    }

    public static Handle add(ClientLevel level, Vec3 position, OpalColor color, int lightLevel) {
        return create(level, position, color, lightLevel, Long.MAX_VALUE);
    }

    public static Handle flash(ClientLevel level, Vec3 position, OpalColor color, int lightLevel, int durationTicks) {
        if (durationTicks <= 0) throw new IllegalArgumentException("Light duration must be positive");
        return create(level, position, color, lightLevel, Math.addExact(level.getGameTime(), durationTicks));
    }

    private static Handle create(ClientLevel level, Vec3 position, OpalColor color, int lightLevel, long expiresAt) {
        if (level == null) throw new IllegalArgumentException("Client level is required");
        validate(position, new LightProfile(color, null), lightLevel);
        var handle = new Handle(new RuntimeSource(level, position, new LightProfile(color, null), lightLevel, expiresAt));
        handles.add(handle);
        return handle;
    }

    public static final class Handle implements AutoCloseable {
        private volatile @Nullable RuntimeSource source;
        private long key;
        private boolean assigned;

        private Handle(RuntimeSource source) {
            this.source = source;
        }

        public synchronized void update(Vec3 position, OpalColor color, int lightLevel) {
            var profile = new LightProfile(color, null);
            validate(position, profile, lightLevel);
            var previous = source;
            if (previous == null) throw new IllegalStateException("Light handle is closed");
            source = new RuntimeSource(previous.level, position, profile, lightLevel, previous.expiresAt);
        }

        public boolean isClosed() {
            return source == null;
        }

        @Override
        public synchronized void close() {
            source = null;
            handles.remove(this);
        }
    }

    static void collect(ClientLevel level, Long2ObjectOpenHashMap<LightSource> target) {
        for (var handle : handles) {
            var source = handle.source;
            if (source == null) continue;
            if (source.level != level) continue;
            if (level.getGameTime() >= source.expiresAt) {
                handle.close();
                continue;
            }
            if (source.lightLevel == 0) continue;
            if (!handle.assigned || target.containsKey(handle.key)) {
                handle.key = allocateKey(target);
                handle.assigned = true;
            }
            target.put(handle.key, source.light());
        }
        for (var registration : collectors) registration.worlds.computeIfAbsent(level, ignored -> new Registration(registration.id, registration.collector)).collect(level, target);
    }

    public static void clear(ClientLevel level) {
        for (var handle : handles) {
            var source = handle.source;
            if (source != null && source.level == level) handle.close();
        }
        for (var registration : collectors) registration.worlds.remove(level);
    }

    private static long allocateKey(Long2ObjectOpenHashMap<LightSource> target) {
        while (target.containsKey(nextKey)) nextKey++;
        return nextKey++;
    }

    private static void validate(Vec3 position, LightProfile profile, int lightLevel) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("Light position must be finite");
        if (lightLevel < 0 || lightLevel > 15) throw new IllegalArgumentException("Light level must be between 0 and 15");
        var color = profile.color();
        if (!Float.isFinite(color.r()) || !Float.isFinite(color.g()) || !Float.isFinite(color.b())
                || color.r() < 0 || color.r() > 1 || color.g() < 0 || color.g() > 1 || color.b() < 0 || color.b() > 1)
            throw new IllegalArgumentException("Light color must be between 0 and 1");
    }

    private record RuntimeSource(ClientLevel level, Vec3 position, LightProfile profile, int lightLevel, long expiresAt) {
        LightSource light() {
            return new LightSource(BlockPos.containing(position).asLong(), profile, lightLevel);
        }
    }

    private record Identity(long id, int sample) {}

    private static final class Tracked {
        long key;
        LightSource light;

        Tracked(long key, LightSource light) {
            this.key = key;
            this.light = light;
        }
    }

    private static final class Registration implements Sink {
        private final ResourceLocation id;
        private final Collector collector;
        private final Map<ClientLevel, Registration> worlds = new java.util.IdentityHashMap<>();
        private final Map<Identity, Tracked> tracked = new HashMap<>();
        private final Map<Identity, LightSource> pending = new HashMap<>();
        private final Map<Long, Line> lines = new HashMap<>();
        private @Nullable ClientLevel level;
        private boolean failed;

        Registration(ResourceLocation id, Collector collector) {
            this.id = id;
            this.collector = collector;
        }

        void collect(ClientLevel level, Long2ObjectOpenHashMap<LightSource> target) {
            pending.clear();
            this.level = level;
            try {
                collector.collect(level, this);
                failed = false;
            } catch (RuntimeException error) {
                if (!failed) OpalLight.LOGGER.error("Light collector failed: {}", id, error);
                failed = true;
                pending.clear();
            } finally {
                this.level = null;
            }
            tracked.keySet().retainAll(pending.keySet());
            lines.keySet().removeIf(key -> !pending.containsKey(new Identity(key, 0)));
            for (var entry : pending.entrySet()) {
                var previous = tracked.get(entry.getKey());
                if (previous == null) {
                    previous = new Tracked(allocateKey(target), entry.getValue());
                    tracked.put(entry.getKey(), previous);
                } else {
                    if (target.containsKey(previous.key)) previous.key = allocateKey(target);
                    previous.light = entry.getValue();
                }
                target.put(previous.key, previous.light);
            }
        }

        @Override
        public void point(long id, Vec3 position, LightProfile profile, int lightLevel) {
            requireCollecting();
            validate(position, profile, lightLevel);
            var identity = new Identity(id, -1);
            if (lightLevel == 0) pending.remove(identity);
            else pending.put(identity, new LightSource(BlockPos.containing(position).asLong(), profile, lightLevel));
        }

        @Override
        public void item(long id, Vec3 position, ItemStack stack, @Nullable Entity owner) {
            requireCollecting();
            pending.remove(new Identity(id, -1));
            if (owner != null && (owner.level() != level || owner.isRemoved() || owner.isSpectator())) return;
            var light = LightSourceDefinitions.itemWithEmissive(level, BlockPos.containing(position), stack, owner,
                    owner != null && owner.isOnFire());
            if (light != null) point(id, position, light.left(), light.rightInt());
        }

        @Override
        public void entity(long id, Vec3 position, Entity entity) {
            requireCollecting();
            pending.remove(new Identity(id, -1));
            if (entity.level() != level || entity.isRemoved() || entity.isSpectator()) return;
            var definition = LightDataLoader.INSTANCE.getEntityDefinition(entity);
            if (definition != null) point(id, position, LightSourceDefinitions.profile(definition), definition.lightLevel());
        }

        @Override
        public void line(long id, Vec3 start, Vec3 end, OpalColor color, int lightLevel) {
            requireCollecting();
            var profile = new LightProfile(color, null);
            validate(start, profile, lightLevel);
            validate(end, profile, lightLevel);
            var line = lines.get(id);
            if (line != null) {
                for (int i = 0; i < line.positions.size(); i++) pending.remove(new Identity(id, i));
            }
            if (lightLevel == 0) {
                lines.remove(id);
                return;
            }
            if (line == null || !line.start.equals(start) || !line.end.equals(end) || line.lightLevel != lightLevel) {
                line = new Line(start, end, lightLevel);
                lines.put(id, line);
            }
            for (int i = 0; i < line.positions.size(); i++) {
                pending.put(new Identity(id, i), new LightSource(line.positions.get(i), profile, lightLevel));
            }
        }

        private void requireCollecting() {
            if (level == null) throw new IllegalStateException("Light sink is only valid during collection");
        }
    }

    private static final class Line {
        final Vec3 start, end;
        final int lightLevel;
        final List<Long> positions = new ArrayList<>();

        Line(Vec3 start, Vec3 end, int lightLevel) {
            this.start = start;
            this.end = end;
            this.lightLevel = lightLevel;
            double length = start.distanceTo(end);
            if (!Double.isFinite(length)) throw new IllegalArgumentException("Light line length must be finite");
            long segments = Math.max(1L, (long) Math.ceil(length / Math.max(1, lightLevel * 0.5)));
            var occupied = new LongOpenHashSet();
            // 间隔随光照范围变化，固定光束缓存采样位置，相同方块只提交一次。
            for (long i = 0; i <= segments; i++) {
                long position = BlockPos.containing(start.lerp(end, (double) i / segments)).asLong();
                if (occupied.add(position)) positions.add(position);
            }
        }
    }
}
