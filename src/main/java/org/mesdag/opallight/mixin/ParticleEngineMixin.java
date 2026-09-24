package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.mesdag.opallight.light.ColoredLightBufferSource;
import org.mesdag.opallight.light.LightColorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @ModifyArg(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"),
            index = 0)
    private VertexConsumer colorParticle(VertexConsumer output, @Local Particle particle, @Local(argsOnly = true) Camera camera) {
        if (LightColorCache.INSTANCE.isEmpty()) return output;
        var position = particle.getPos();
        if (LightColorCache.INSTANCE.sample(position.x, position.y, position.z) == 0) return output;
        return ColoredLightBufferSource.wrap(output, camera.getPosition());
    }
}
