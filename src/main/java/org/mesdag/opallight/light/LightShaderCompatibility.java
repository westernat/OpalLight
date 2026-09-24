package org.mesdag.opallight.light;

import java.lang.reflect.Method;

/// Iris 是可选依赖；只查询是否启用光影，不依赖某个光影包的顶点动画公式。
final class LightShaderCompatibility {
    private static final Object irisApi;
    private static final Method shaderPackInUse;

    static {
        Object api = null;
        Method method = null;
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = type.getMethod("getInstance").invoke(null);
            method = type.getMethod("isShaderPackInUse");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // 没有安装 Iris 时仍使用完整的彩光遮罩。
        }
        irisApi = api;
        shaderPackInUse = method;
    }

    private LightShaderCompatibility() {}

    static boolean isShaderPackInUse() {
        if (irisApi == null || shaderPackInUse == null) return false;
        try {
            return (boolean) shaderPackInUse.invoke(irisApi);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }
}
