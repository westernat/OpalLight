package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class LightPropagator {
    private static final Map<Long, Set<Long>> sectionSources = new Object2ObjectOpenHashMap<>();

    public static boolean isSectionAffected(long sectionPos) {
        Set<Long> sources = sectionSources.get(sectionPos);
        return sources != null && !sources.isEmpty();
    }

    public static void clearSectionSources(long sectionPos) {
        sectionSources.remove(sectionPos);
    }

    public static void clearAllSources() {
        sectionSources.clear();
    }

    public static void forceRepropagate(Level level, Set<ChunkPos> affectedChunks) {
        int minSY = SectionPos.blockToSectionCoord(level.getMinBuildHeight());
        int maxSY = SectionPos.blockToSectionCoord(level.getMaxBuildHeight() - 1);
        for (ChunkPos cp : affectedChunks) {
            for (int sy = minSY; sy <= maxSY; sy++) {
                LightColorCache.INSTANCE.clearSection(SectionPos.of(cp.x, sy, cp.z));
            }
        }

        for (ChunkPos cp : affectedChunks) {
            var chunk = level.getChunkSource().getChunkForLighting(cp.x, cp.z);
            if (chunk == null) continue;
            chunk.findBlockLightSources((pos, state) -> {
                ObjectIntPair<OpalColor> colorWithEmissive = LightManager.colorWithEmissive(level, pos, state);
                if (colorWithEmissive == null) return;
                propagate(level, pos, colorWithEmissive);
            });
        }
    }

    public static void propagate(Level level, BlockPos source, ObjectIntPair<OpalColor> colorWithEmissive) {
        long sourceSection = LightColorCache.sectionKey(source);
        OpalColor color = colorWithEmissive.left();
        int emissive = colorWithEmissive.rightInt();

        Deque<Node> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new Node(source, 0));
        visited.add(source.asLong());

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            BlockPos pos = node.pos;
            int dist = node.dist;

            float factor = 1f - (float) dist / emissive;
            if (factor > 0f) {
                OpalColor existing = LightColorCache.INSTANCE.get(pos);
                float r = color.r() * factor;
                float g = color.g() * factor;
                float b = color.b() * factor;
                if (existing == null) {
                    LightColorCache.INSTANCE.put(pos, new OpalColor(r, g, b));
                } else {
                    float newR = Math.min(1.0f, existing.r() + r);
                    float newG = Math.min(1.0f, existing.g() + g);
                    float newB = Math.min(1.0f, existing.b() + b);
                    LightColorCache.INSTANCE.put(pos, new OpalColor(newR, newG, newB));
                }
                long affectedKey = LightColorCache.sectionKey(pos);
                sectionSources.computeIfAbsent(affectedKey, k -> new ObjectOpenHashSet<>()).add(sourceSection);
            }

            if (dist >= emissive) continue;

            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (!visited.add(next.asLong())) continue;

                BlockState nextState = level.getBlockState(next);
                if (nextState.isSolidRender(level, next)) continue;

                queue.add(new Node(next, dist + 1));
            }
        }
    }

    record Node(BlockPos pos, int dist) {}
}
