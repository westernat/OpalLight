package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.function.LongToIntFunction;

/**
 * 彩光遮罩网格构建前的纯数据预筛。
 *
 * <p>该类故意不依赖 Minecraft 客户端、模型或渲染类型：一方面可在普通 JVM 测试中
 * 穷举验证，另一方面也明确限定了预筛只能依据已经收敛的 RGB 数据，不能偷读可变世界
 * 或用近似规则代替原有遮挡与模型流程。</p>
 */
final class LightMaskMeshPrefilter {
    private LightMaskMeshPrefilter() {
    }

    /**
     * 判断目标 section 是否可能包含被彩光照到的方块表面。
     *
     * <p>表面采样只涉及方块的六个面邻居，因此除了目标 section 本身，只需要检查
     * 六个共享面的相邻 section。棱邻接和角邻接不会被方块的单步六邻采样读取，不能
     * 因为它们有光而无谓唤醒目标 section 的模型构建。</p>
     */
    static boolean sectionCanContainLitSurface(LongSet lightSections, long sectionKey) {
        if (lightSections.contains(sectionKey)) {
            return true;
        }
        int sectionX = PackedPosition.sectionX(sectionKey);
        int sectionY = PackedPosition.sectionY(sectionKey);
        int sectionZ = PackedPosition.sectionZ(sectionKey);
        return lightSections.contains(PackedPosition.sectionKey(sectionX - 1, sectionY, sectionZ))
                || lightSections.contains(PackedPosition.sectionKey(sectionX + 1, sectionY, sectionZ))
                || lightSections.contains(PackedPosition.sectionKey(sectionX, sectionY - 1, sectionZ))
                || lightSections.contains(PackedPosition.sectionKey(sectionX, sectionY + 1, sectionZ))
                || lightSections.contains(PackedPosition.sectionKey(sectionX, sectionY, sectionZ - 1))
                || lightSections.contains(PackedPosition.sectionKey(sectionX, sectionY, sectionZ + 1));
    }

    /**
     * 一次采样方块六个面邻居的 RGB，并返回逐通道最大值。
     *
     * <p>调用方只有在返回值非零时才允许查询 BakedModel 与 ModelData。输出数组保留
     * 每个方向的原始值，后续仍逐面执行原有的 shouldRenderFace 与 getQuads 流程，
     * 因而不会把面遮挡、无裁剪 quad 或动态模型近似掉。</p>
     */
    static int sampleNeighborLights(LongToIntFunction lightLookup, long packedPos, int[] output) {
        if (output.length < PackedPosition.DIRECTION_COUNT) {
            throw new IllegalArgumentException("六邻光照输出数组长度不足");
        }
        int maximum = 0;
        for (int direction = 0; direction < PackedPosition.DIRECTION_COUNT; direction++) {
            int light = lightLookup.applyAsInt(PackedPosition.offset(packedPos, direction));
            output[direction] = light;
            maximum = PackedLight.max(maximum, light);
        }
        return maximum;
    }
}
