package org.mesdag.opallight.light;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.mesdag.opallight.OpalLight;

import java.io.IOException;
import java.util.Map;

@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    private static ShaderInstance lightMaskShader;

    @SuppressWarnings("deprecation")
    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(
                event.getResourceProvider(),
                "opallight:light_mask", // vulkan can only use String instead of ResourceLocation
                DefaultVertexFormat.POSITION_TEX_COLOR
        ), shader -> lightMaskShader = shader);
    }

    private static final Map<BlockState, OpalColor> colorCache = new Reference2ObjectOpenHashMap<>();

    public static @Nullable ObjectIntPair<OpalColor> colorWithEmissive(Level level, BlockPos pos, BlockState state) {
        int emission = state.getLightEmission(level, pos);
        if (emission > 0) {
            OpalColor color = colorCache.get(state);
            if (color == null) {
                color = LightDataLoader.INSTANCE.getColor(state, true);
                if (color == null) {
                    color = OpalColor.EMPTY;
                }
                colorCache.put(state, color);
            }
            if (color != OpalColor.EMPTY) {
                return new ObjectIntImmutablePair<>(color, emission);
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((a, b, c, d, e, f) -> {
            colorCache.clear();
            return LightDataLoader.INSTANCE.reload(a, b, c, d, e, f);
        });
    }

    private static final Map<Long, ObjectIntPair<OpalColor>> pendingToAdd = new Object2ObjectOpenHashMap<>(); // block pos -> color

    public static void pendingToAdd(BlockPos pos, ObjectIntPair<OpalColor> colorWithEmissive) {
        pendingToAdd.put(pos.asLong(), colorWithEmissive);
    }

    @SubscribeEvent
    public static void clientTick$Pre(ClientTickEvent.Pre event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || pendingToAdd.isEmpty()) return;
        for (Map.Entry<Long, ObjectIntPair<OpalColor>> entry : pendingToAdd.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            LightPropagator.propagate(level, pos, entry.getValue());
            LightColorCache.INSTANCE.put(pos, entry.getValue().left());
        }
        pendingToAdd.clear();
    }

    @SubscribeEvent
    public static void level$Unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            LightColorCache.INSTANCE.clearAll();
            LightMaskMeshCache.invalidate();
            pendingToAdd.clear();
        }
    }

    private static final RenderType LIGHT_MASK = RenderType.create(
            "light_mask",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> LightManager.lightMaskShader))
                    .setTextureState(RenderType.BLOCK_SHEET)
                    .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                    .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderType.COLOR_WRITE)
                    .setLayeringState(RenderType.POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false)
    );

    // use mixin to compatible iris or other mod
    public static void render(Matrix4f viewMatrix, Camera camera) {
        Vec3 pos = camera.getPosition();
        viewMatrix.translate((float) -pos.x, (float) -pos.y, (float) -pos.z);

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(viewMatrix);
        RenderSystem.applyModelViewMatrix();

        LIGHT_MASK.setupRenderState();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        LightMaskMeshCache.draw(lightMaskShader);

        LIGHT_MASK.clearRenderState();
        RenderSystem.disableBlend();

        modelViewStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
