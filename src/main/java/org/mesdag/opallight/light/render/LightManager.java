package org.mesdag.opallight.light.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.OpalLight;
import org.mesdag.opallight.OpalLightConfig;
import org.mesdag.opallight.light.data.LightDataLoader;
import org.mesdag.opallight.light.engine.LightPropagator;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.function.Supplier;

@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    static LightMaskShader lightMaskShader;
    private static boolean enabled = true;
    private static @Nullable Boolean previousShaderPackMode;

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        Minecraft.getInstance().execute(LightDataLoader.INSTANCE::invalidateTags);
    }

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

    @SubscribeEvent
    public static void clientTick$Post(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) return;
        boolean current = OpalLightConfig.isEnabled();
        if (current != enabled) {
            enabled = current;
            for (var world : LightWorld.all()) {
                world.reset();
                if (enabled) world.reindex();
            }
        }
        if (!enabled) return;
        updateShaderPackMode();
        for (var world : LightWorld.all()) {
            updateLighting(world);
            world.propagator().updateDynamicSources(world.level);
            world.propagator().scheduleCyclingSources(world.level);
            updateLighting(world);
        }
    }

    @SubscribeEvent
    public static void chunk$Load(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        var world = LightWorld.get(level);
        ChunkPos cp = event.getChunk().getPos();
        world.loadedChunks.add(cp.toLong());
        if (!OpalLightConfig.isEnabled()) return;
        world.propagator().indexChunk(level, cp);
        world.meshes().refreshLoadedChunk(cp);
        world.propagator().scheduleChunkAndNeighbors(cp.x, cp.z);
    }

    @SubscribeEvent
    public static void chunk$Unload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        var world = LightWorld.get(level);
        ChunkPos cp = event.getChunk().getPos();
        world.loadedChunks.remove(cp.toLong());
        if (!OpalLightConfig.isEnabled()) return;
        int minSY = SectionPos.blockToSectionCoord(level.getMinBuildHeight());
        int maxSY = SectionPos.blockToSectionCoord(level.getMaxBuildHeight() - 1);
        for (int sy = minSY; sy <= maxSY; sy++) {
            SectionPos section = SectionPos.of(cp.x, sy, cp.z);
            if (world.colors().clearSection(section)) world.meshes().markDirtyAroundSection(section.asLong());
        }
        world.meshes().discardChunk(cp);
        world.propagator().forgetChunk(cp);
        world.propagator().scheduleChunkAndNeighbors(cp.x, cp.z);
    }

    @SubscribeEvent
    public static void level$Unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) LightWorld.unload(level);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LightWorld.clear();
        previousShaderPackMode = null;
    }

    // 在光影包合成后叠加，避免彩光被当作材质颜色再次参与光照。
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || lightMaskShader == null || !OpalLightConfig.isEnabled()) return;
        var world = LightWorld.get(level);
        updateLighting(world);
        world.meshes().draw(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera(), lightMaskShader,
                world.reloadInProgress, world.propagator()::isGroupPropagationPending);
    }

    public static void captureTerrainFog() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && OpalLightConfig.isEnabled()) LightWorld.get(level).meshes().captureTerrainFog();
    }

    private static void beginResourceReload() {
        LightPropagator.clearDefinitions();
        if (!OpalLightConfig.isEnabled()) return;
        for (var world : LightWorld.all()) {
            int changedSources = world.propagator().refreshDefinitions(world.level)
                    + world.propagator().updateDynamicSources(world.level);
            if (changedSources == 0) continue;
            world.meshes().beginDefinitionReload();
            world.reloadInProgress = world.propagator().hasPendingUpdates();
        }
    }

    private static void updateShaderPackMode() {
        boolean current = LightShaderCompatibility.isShaderPackInUse();
        if (previousShaderPackMode != null && previousShaderPackMode != current) {
            for (var world : LightWorld.all()) {
                world.meshes().invalidate();
                world.colors().forEachSection(world.meshes()::markDirtyAroundSection);
            }
        }
        previousShaderPackMode = current;
    }

    private static void updateLighting(LightWorld world) {
        ClientLevel level = world.level;
        if (world.propagator().consumeDynamicInvalidation()) world.meshes().invalidateDynamicBatch();
        world.meshes().finishCompletedMeshes(level);
        boolean firstMeshPending = !world.meshes().hasMeshes();
        Supplier<OptionalLong> preferred = () ->
                level != Minecraft.getInstance().level ? OptionalLong.empty() : world.meshes().firstVisiblePropagationGroup(level, world.propagator()::isGroupPropagationPending);
        // 网格构建期间允许下一批传播计算，结果留待当前网格整批发布后提交。
        if (world.meshes().hasPendingDynamicBatch()) {
            world.propagator().flushPending(level, firstMeshPending, preferred, false);
            return;
        }
        var commit = world.propagator().flushPending(level, firstMeshPending, preferred);
        if (commit != null) {
            world.meshes().beginDirtyBatch(commit.dynamic());
            try {
                for (var update : commit.updates()) {
                    world.colors().apply(update);
                    world.meshes().markColorChanged(update.minX(), update.minY(), update.minZ(),
                            update.maxX(), update.maxY(), update.maxZ(), commit.dynamic());
                }
            } finally {
                world.meshes().endDirtyBatch();
            }
            // 发布完成后才允许捕获下一份传播快照。
            world.propagator().flushPending(level, firstMeshPending, preferred, false);
        }
        if (!world.propagator().hasPendingUpdates()) world.meshes().resumeDynamicBatch();
        if (world.reloadInProgress) world.reloadInProgress = world.propagator().hasPendingUpdates();
    }
}
