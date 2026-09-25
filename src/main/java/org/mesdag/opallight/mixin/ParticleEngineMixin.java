package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.mesdag.opallight.light.ColoredLightBufferSource;
import org.mesdag.opallight.light.LightColorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    /// 1.20.1 的 {@code render} 直接向 {@code BufferBuilder} 写入每个粒子，参数表与 1.21 不同。
    @ModifyArg(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"), index = 0)
    private VertexConsumer colorParticle(VertexConsumer output, @Local Particle particle) {
        if (LightColorCache.INSTANCE.isEmpty()) return output;
        var position = particle.getPos();
        long color = LightColorCache.INSTANCE.sample(position.x, position.y, position.z);
        return ColoredLightBufferSource.wrapFixed(output, color);
    }
}
