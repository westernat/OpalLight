package org.mesdag.opallight.light;

import org.mesdag.opallight.OpalLight;

import java.lang.reflect.Method;

/// Iris 是可选依赖；只查询是否启用光影，不依赖某个光影包的顶点动画公式。
final class LightShaderCompatibility {
    private static final Object irisApi;
    private static final Method shaderPackInUse;
    private static boolean failed;

    /// Iris/Oculus 的深度与颜色写入锁，详见 {@link #reclaimDepthColorState()}。
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

    /// 光影包启用时，Iris/Oculus 会在非自身着色器的 {@code apply()} 结束时锁死深度与颜色写入，
    /// 并在锁存期间取消 {@code GlStateManager} 的深度/颜色/混合状态调用。彩光遮罩依赖这些状态，
    /// 因此在绘制前临时解锁，绘制结束后重新上锁以保持光影模组自身的状态不变量。
    ///
    /// @return 是否真的抢回了状态；未安装光影模组或未上锁时返回 {@code false}
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
