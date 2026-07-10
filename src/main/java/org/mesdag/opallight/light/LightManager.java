package org.mesdag.opallight.light;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
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
import net.minecraft.server.packs.resources.ResourceProvider;
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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.mesdag.opallight.OpalLight;

import java.io.IOException;
import java.util.Map;

@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    private static ShaderInstance lightCompositeShader;
    private static ShaderInstance lightMaskShader;

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        ResourceProvider provider = event.getResourceProvider();
        event.registerShader(new ShaderInstance(provider, OpalLight.asResource("light_composite"), DefaultVertexFormat.POSITION), shader -> lightCompositeShader = shader);
        event.registerShader(new ShaderInstance(provider, OpalLight.asResource("light_mask"), DefaultVertexFormat.POSITION_TEX_COLOR), shader -> lightMaskShader = shader);
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
                    .createCompositeState(true)
    );
    private static RenderTarget lightMaskFbo;

    // use mixin to compatible iris or other mod
    public static void render(Matrix4f viewMatrix, Camera camera) {
        Vec3 pos = camera.getPosition();
        viewMatrix.translate((float) -pos.x, (float) -pos.y, (float) -pos.z);
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        int width = mainTarget.width;
        int height = mainTarget.height;

        if (lightMaskFbo == null || lightMaskFbo.width != width || lightMaskFbo.height != height) {
            if (lightMaskFbo != null) {
                lightMaskFbo.destroyBuffers();
            }
            lightMaskFbo = new RenderTarget(true) {};
            lightMaskFbo.resize(width, height, Minecraft.ON_OSX);
            lightMaskFbo.setClearColor(0, 0, 0, 0);
        }

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(viewMatrix);
        RenderSystem.applyModelViewMatrix();

        lightMaskFbo.bindWrite(true);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, lightMaskFbo.frameBufferId);

        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);

        LIGHT_MASK.setupRenderState();
        GlStateManager._depthMask(false);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        LightMaskMeshCache.draw();

        LIGHT_MASK.clearRenderState();
        GlStateManager._depthMask(true);
        mainTarget.bindWrite(false);

        modelViewStack.popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

        blitToScreen(lightCompositeShader, lightMaskFbo.getColorTextureId(), width, height);

        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    private static final Matrix4f idM = new Matrix4f();
    private static final Matrix4f orthoM = new Matrix4f().ortho(0, 1, 0, 1, 0, 1);
    private static VertexBuffer blitBuffer;

    private static void blitToScreen(ShaderInstance shader, int textureId, int fbWidth, int fbHeight) {
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(idM);
        RenderSystem.setProjectionMatrix(orthoM, VertexSorting.DISTANCE_TO_ORIGIN);

        shader.setSampler("Sampler0", textureId);
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(idM);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(orthoM);
        }
        shader.apply();
        GlStateManager._viewport(0, 0, fbWidth, fbHeight);

        if (blitBuffer == null || blitBuffer.isInvalid()) {
            blitBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(0, 0, 0);
            builder.addVertex(1, 0, 0);
            builder.addVertex(1, 1, 0);
            builder.addVertex(0, 1, 0);
            blitBuffer.bind();
            blitBuffer.upload(builder.buildOrThrow());
        } else {
            blitBuffer.bind();
        }
        blitBuffer.draw();
        VertexBuffer.unbind();

        shader.clear();

        modelViewStack.popMatrix();
        RenderSystem.restoreProjectionMatrix();
    }
}
