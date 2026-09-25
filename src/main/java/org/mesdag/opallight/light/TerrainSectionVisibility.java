package org.mesdag.opallight.light;

import net.minecraft.world.phys.AABB;
import org.mesdag.opallight.OpalLight;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/// 可选地跟随 Sodium 实际选中的地形区块，避免遮罩绘制已被剔除的地形。
final class TerrainSectionVisibility {
    private static final MethodHandle INSTANCE;
    private static final MethodHandle IS_BOX_VISIBLE;
    private static boolean failed;

    static {
        MethodHandle instance = null;
        MethodHandle isBoxVisible = null;
        try {
            Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            instance = lookup.findStatic(renderer, "instanceNullable", MethodType.methodType(renderer));
            isBoxVisible = lookup.findVirtual(renderer, "isBoxVisible", MethodType.methodType(boolean.class, double.class, double.class, double.class, double.class, double.class, double.class));
        } catch (ClassNotFoundException ignored) {
            /// 原版地形已由视锥检查可见性。
            failed = true;
        } catch (NoSuchMethodException | IllegalAccessException error) {
            failed = true;
            OpalLight.LOGGER.error("Cannot query Sodium terrain visibility", error);
        }
        INSTANCE = instance;
        IS_BOX_VISIBLE = isBoxVisible;
    }

    private TerrainSectionVisibility() {}

    static boolean isVisible(AABB box) {
        if (INSTANCE == null || failed) return true;
        try {
            Object renderer = INSTANCE.invoke();
            return renderer == null || (boolean) IS_BOX_VISIBLE.invoke(renderer,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        } catch (Throwable error) {
            failed = true;
            OpalLight.LOGGER.error("Cannot query Sodium terrain visibility", error);
            return true;
        }
    }
}
