package org.mesdag.opallight.light.render;

import org.mesdag.opallight.OpalLight;

import java.lang.reflect.Method;

// 通过反射兼容可选的 Iris。
final class LightShaderCompatibility {
    private static final Object irisApi;
    private static final Method shaderPackInUse;
    private static boolean failed;

    private static final Method depthColorLocked;
    private static final Method depthColorUnlock;
    private static final Method depthColorDisable;

    private static boolean reclaimFailed;

    static {
        Object api = null;
        Method method = null;
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = type.getMethod("getInstance").invoke(null);
            method = type.getMethod("isShaderPackInUse");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // 没有安装 Iris 时仍使用完整的彩光遮罩。
            failed = true;
        }
        irisApi = api;
        shaderPackInUse = method;

        depthColorLocked = findStatic("net.irisshaders.iris.gl.blending.DepthColorStorage", "isDepthColorLocked");
        depthColorUnlock = findStatic("net.irisshaders.iris.gl.blending.DepthColorStorage", "unlockDepthColor");
        depthColorDisable = findStatic("net.irisshaders.iris.gl.blending.DepthColorStorage", "disableDepthColor");
    }

    private static Method findStatic(String owner, String name) {
        try {
            Method method = Class.forName(owner).getMethod(name);
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {
                // 部分模组加载器模块未开放包，公开成员通常仍可直接调用。
            }
            return method;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private LightShaderCompatibility() {}

    static boolean isShaderPackInUse() {
        if (irisApi == null || shaderPackInUse == null || failed) return false;
        try {
            return (boolean) shaderPackInUse.invoke(irisApi);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            failed = true;
            return false;
        }
    }

    // Iris 会锁定深度和颜色写入；绘制遮罩前临时解锁，结束后恢复。
    static boolean reclaimDepthColorState() {
        if (depthColorLocked == null || depthColorUnlock == null || reclaimFailed) return false;
        try {
            if (!(Boolean) depthColorLocked.invoke(null)) return false;
            depthColorUnlock.invoke(null);
            return true;
        } catch (ReflectiveOperationException | LinkageError error) {
            reclaimFailed = true;
            OpalLight.LOGGER.error("Cannot reclaim depth/color state from the shader mod", error);
            return false;
        }
    }

    static void restoreDepthColorState(boolean reclaimed) {
        if (!reclaimed || depthColorDisable == null || reclaimFailed) return;
        try {
            depthColorDisable.invoke(null);
        } catch (ReflectiveOperationException | LinkageError error) {
            reclaimFailed = true;
            OpalLight.LOGGER.error("Cannot restore depth/color state for the shader mod", error);
        }
    }
}
