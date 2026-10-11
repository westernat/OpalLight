package org.mesdag.opallight.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.mesdag.opallight.OpalLight;
import org.mesdag.opallight.light.engine.DynamicLights;
import top.theillusivec4.curios.api.CuriosApi;

final class CuriosLightIntegration {
    private CuriosLightIntegration() {}

    static void register() {
        DynamicLights.registerCollector(OpalLight.asResource("curios"), CuriosLightIntegration::collect);
    }

    private static void collect(ClientLevel level, DynamicLights.Sink sink) {
        var local = Minecraft.getInstance().player;
        if (local != null) collect(local, sink);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity != local && entity instanceof LivingEntity living) collect(living, sink);
        }
    }

    private static void collect(LivingEntity entity, DynamicLights.Sink sink) {
        if (entity.isRemoved() || entity.isSpectator()) return;
        CuriosApi.getCuriosInventory(entity).ifPresent(inventory -> {
            var equipped = inventory.getEquippedCurios();
            var position = new Vec3(entity.getX(), entity.getEyeY() - 0.3, entity.getZ());
            for (int slot = 0; slot < equipped.getSlots(); slot++) {
                var stack = equipped.getStackInSlot(slot);
                if (!stack.isEmpty()) sink.item((long) entity.getId() << 32 | Integer.toUnsignedLong(slot), position, stack, entity);
            }
        });
    }
}
