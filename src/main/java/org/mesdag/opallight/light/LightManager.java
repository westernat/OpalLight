package org.mesdag.opallight.light;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.OpalLight;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.function.Supplier;

@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    static LightMaskShader lightMaskShader;

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(
                event.getResourceProvider(),
                OpalLight.asResource("light_mask"),
                LightMaskMeshBuilder.VERTEX_FORMAT
        ), shader -> lightMaskShader = new LightMaskShader(shader));
    }

    @SubscribeEvent
    public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((barrier, resources, preparationProfiler, reloadProfiler,
                                      backgroundExecutor, gameExecutor) ->
                LightDataLoader.INSTANCE.reload(barrier, resources, preparationProfiler, reloadProfiler,
                                backgroundExecutor, gameExecutor)
                        .thenRunAsync(LightManager::beginResourceReload, gameExecutor));
    }

    private static boolean reloadInProgress;
    private static @Nullable Boolean previousShaderPackMode;

    private static void beginResourceReload() {
        boolean previousReloadInProgress = reloadInProgress;
        reloadInProgress = false;
        LightSourceDefinitions.clear();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int changedSources = LightPropagator.refreshDefinitions(level) + DynamicLightSources.update(level);
        if (changedSources == 0) {
            reloadInProgress = previousReloadInProgress && LightPropagator.hasPendingUpdates();
            return;
        }
        LightMaskMeshCache.beginDefinitionReload();
        reloadInProgress = LightPropagator.hasPendingUpdates();
    }

    @SubscribeEvent
    public static void clientTick$Pre(ClientTickEvent.Pre event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        updateShaderPackMode();
        // 先发布已完成的快照，再采集本刻的移动，减少动态光一刻的延迟。
        updateLighting(level);
        DynamicLightSources.update(level);
        LightPropagator.scheduleCyclingSources(level);
        updateLighting(level);
    }

    private static void updateShaderPackMode() {
        boolean current = LightShaderCompatibility.isShaderPackInUse();
        if (previousShaderPackMode != null && previousShaderPackMode != current) {
            LightMaskMeshCache.invalidate();
            LightColorCache.INSTANCE.forEachSection(LightMaskMeshCache::markDirtyAroundSection);
        }
        previousShaderPackMode = current;
    }

    @SubscribeEvent
    public static void chunk$Load(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        ChunkPos cp = event.getChunk().getPos();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        LightPropagator.indexChunk(level, cp);
        LightMaskMeshCache.refreshLoadedChunk(cp);
        LightPropagator.scheduleChunkAndNeighbors(cp.x, cp.z);
    }

    @SubscribeEvent
    public static void chunk$Unload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        ChunkPos cp = event.getChunk().getPos();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int minSY = SectionPos.blockToSectionCoord(level.getMinBuildHeight());
        int maxSY = SectionPos.blockToSectionCoord(level.getMaxBuildHeight() - 1);
        for (int sy = minSY; sy <= maxSY; sy++) {
            SectionPos section = SectionPos.of(cp.x, sy, cp.z);
            if (LightColorCache.INSTANCE.clearSection(section)) LightMaskMeshCache.markDirtyAroundSection(section.asLong());
        }
        LightMaskMeshCache.discardChunk(cp);
        LightPropagator.forgetChunk(cp);
        LightPropagator.scheduleChunkAndNeighbors(cp.x, cp.z);
    }

    @SubscribeEvent
    public static void level$Unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            LightColorCache.INSTANCE.clearAll();
            LightPropagator.clearAllSources();
            LightMaskMeshCache.invalidate();
            LightMaskRenderer.clearTerrainFog();
            reloadInProgress = false;
            previousShaderPackMode = null;
        }
    }

    // 在光影包合成后叠加，避免彩光被当作材质颜色再次参与光照。
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || lightMaskShader == null) return;
        updateLighting(level);
        LightMaskMeshCache.draw(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera(), lightMaskShader,
                reloadInProgress, LightPropagator::isGroupPropagationPending);
    }

    private static void updateLighting(ClientLevel level) {
        boolean firstMeshPending = !LightMaskMeshCache.hasMeshes();
        Supplier<OptionalLong> preferred = () ->
                LightMaskMeshCache.firstVisiblePropagationGroup(level, LightPropagator::isGroupPropagationPending);
        var commit = LightPropagator.flushPending(level, firstMeshPending, preferred);
        if (commit != null) {
            LightMaskMeshCache.beginDirtyBatch();
            try {
                for (var update : commit.updates()) {
                    LightColorCache.INSTANCE.apply(update);
                    LightMaskMeshCache.markColorChanged(update.minX(), update.minY(), update.minZ(),
                            update.maxX(), update.maxY(), update.maxZ(), commit.dynamic());
                }
            } finally {
                LightMaskMeshCache.endDirtyBatch();
            }
            // 发布完成后才允许捕获下一份传播快照。
            LightPropagator.flushPending(level, firstMeshPending, preferred);
        }
        if (reloadInProgress) reloadInProgress = LightPropagator.hasPendingUpdates();
    }

    public static void captureTerrainFog() {
        LightMaskRenderer.captureTerrainFog();
    }
}
