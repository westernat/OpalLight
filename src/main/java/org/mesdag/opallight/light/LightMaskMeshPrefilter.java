package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.LongSet;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongConsumer;
import java.util.function.LongToIntFunction;

/**
 * 彩光遮罩网格构建前的纯数据预筛。
 *
 * <p>该类故意不依赖 Minecraft 客户端、模型或渲染类型：一方面可在普通 JVM 测试中
 * 穷举验证，另一方面也明确限定了预筛只能依据已经收敛的 RGB 数据，不能偷读可变世界
 * 或用近似规则代替原有遮挡与模型流程。</p>
 */
final class LightMaskMeshPrefilter {
    static final int FACE_COUNT = PackedPosition.DIRECTION_COUNT;
    static final int CORNERS_PER_FACE = 4;
    static final int FACE_SAMPLE_COUNT = FACE_COUNT * CORNERS_PER_FACE;
    private static final int CHANNEL_SUM_MASK = 0x7F;
    private static final int GREEN_SUM_SHIFT = 7;
    private static final int BLUE_SUM_SHIFT = 14;
    private static final int VERTEX_STRIDE = 8;
    private static final float FACE_EPSILON = 1.0E-4F;

    private LightMaskMeshPrefilter() {
    }

    /**
     * 判断目标 section 是否可能包含被彩光照到的方块表面。
     *
     * <p>面角采样会读取方块周围一格的完整外壳，因此光可以从共享面、棱或角相邻的
     * section 进入目标 section。这里只检查一格 3×3×3 邻域，不会扩大传播半径。</p>
     */
    static boolean sectionCanContainLitSurface(LongSet lightSections, long sectionKey) {
        int sectionX = PackedPosition.sectionX(sectionKey);
        int sectionY = PackedPosition.sectionY(sectionKey);
        int sectionZ = PackedPosition.sectionZ(sectionKey);
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    if (lightSections.contains(PackedPosition.sectionKey(
                            sectionX + offsetX, sectionY + offsetY, sectionZ + offsetZ
                    ))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 遍历一个 section 周围一格的 27 个唯一 section，供整片光场替换时反向失效。 */
    static void forEachSectionSamplingHalo(long sectionKey, LongConsumer consumer) {
        int sectionX = PackedPosition.sectionX(sectionKey);
        int sectionY = PackedPosition.sectionY(sectionKey);
        int sectionZ = PackedPosition.sectionZ(sectionKey);
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    consumer.accept(PackedPosition.sectionKey(
                            sectionX + offsetX, sectionY + offsetY, sectionZ + offsetZ
                    ));
                }
            }
        }
    }

    /**
     * 遍历一个光照体素可能影响的网格 section。
     *
     * <p>内部体素只影响自身 section；位于面、棱或角边界时，对三个轴的边界偏移取
     * 笛卡尔积，分别得到 2、4 或 8 个 section，避免普通更新无条件放大到 27 片。</p>
     */
    static void forEachBlockMeshSection(long packedPos, LongConsumer consumer) {
        long sectionKey = PackedPosition.sectionKey(packedPos);
        int sectionX = PackedPosition.sectionX(sectionKey);
        int sectionY = PackedPosition.sectionY(sectionKey);
        int sectionZ = PackedPosition.sectionZ(sectionKey);
        int localX = PackedPosition.x(packedPos) & 15;
        int localY = PackedPosition.y(packedPos) & 15;
        int localZ = PackedPosition.z(packedPos) & 15;
        int minX = localX == 0 ? -1 : 0;
        int maxX = localX == 15 ? 1 : 0;
        int minY = localY == 0 ? -1 : 0;
        int maxY = localY == 15 ? 1 : 0;
        int minZ = localZ == 0 ? -1 : 0;
        int maxZ = localZ == 15 ? 1 : 0;
        for (int offsetX = minX; offsetX <= maxX; offsetX++) {
            for (int offsetY = minY; offsetY <= maxY; offsetY++) {
                for (int offsetZ = minZ; offsetZ <= maxZ; offsetZ++) {
                    consumer.accept(PackedPosition.sectionKey(
                            sectionX + offsetX, sectionY + offsetY, sectionZ + offsetZ
                    ));
                }
            }
        }
    }

    /**
     * 一次读取方块周围的 26 个外壳体素，并把它们分发到六个面的四个角点。
     *
     * <p>每个角点只累计该面外侧切平面上的 2×2 样本，固定包含四个零或非零值。这样既与
     * 原版完整边界面的平滑采样方向一致，也不会把实体内部或墙体背面的光混入正面。</p>
     */
    static void sampleFaceCorners(LongToIntFunction lightLookup, long packedPos, int[] output) {
        if (output.length < FACE_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Face-light output must contain all 24 face corners");
        }
        for (int index = 0; index < FACE_SAMPLE_COUNT; index++) {
            output[index] = 0;
        }

        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int light = lightLookup.applyAsInt(PackedPosition.offset(packedPos, dx, dy, dz));
                    if (dy == -1) {
                        addFaceSample(output, 0, dx, dz, light);
                    } else if (dy == 1) {
                        addFaceSample(output, 1, dx, dz, light);
                    }
                    if (dz == -1) {
                        addFaceSample(output, 2, dx, dy, light);
                    } else if (dz == 1) {
                        addFaceSample(output, 3, dx, dy, light);
                    }
                    if (dx == -1) {
                        addFaceSample(output, 4, dz, dy, light);
                    } else if (dx == 1) {
                        addFaceSample(output, 5, dz, dy, light);
                    }
                }
            }
        }
    }

    static boolean faceHasLight(int[] faceSamples, int direction) {
        requireFaceSamples(faceSamples);
        requireDirection(direction);
        int start = direction * CORNERS_PER_FACE;
        return faceSamples[start] != 0
                || faceSamples[start + 1] != 0
                || faceSamples[start + 2] != 0
                || faceSamples[start + 3] != 0;
    }

    static boolean anyFaceHasLight(int[] faceSamples) {
        requireFaceSamples(faceSamples);
        for (int sample : faceSamples) {
            if (sample != 0) {
                return true;
            }
        }
        return false;
    }

    /** 仅接受位于指定方块边界平面内且数据完整有限的 quad，未知或越界几何不生成彩光遮罩。 */
    static boolean isBoundaryQuad(@Nullable int[] vertices, int direction) {
        requireDirection(direction);
        if (vertices == null || vertices.length < VERTEX_STRIDE * 4) {
            return false;
        }
        float expectedPlane = direction == 1 || direction == 3 || direction == 5 ? 1.0F : 0.0F;
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * VERTEX_STRIDE;
            float x = Float.intBitsToFloat(vertices[offset]);
            float y = Float.intBitsToFloat(vertices[offset + 1]);
            float z = Float.intBitsToFloat(vertices[offset + 2]);
            float u = Float.intBitsToFloat(vertices[offset + 4]);
            float v = Float.intBitsToFloat(vertices[offset + 5]);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(u) || !Float.isFinite(v)
                    || !insideUnitInterval(x) || !insideUnitInterval(y) || !insideUnitInterval(z)) {
                return false;
            }
            float normal = switch (direction) {
                case 0, 1 -> y;
                case 2, 3 -> z;
                case 4, 5 -> x;
                default -> throw new AssertionError("Unreachable direction");
            };
            if (Math.abs(normal - expectedPlane) > FACE_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /** 按顶点在面内的两个切轴坐标，对该面四个角点做双线性 RGB 插值。 */
    static void smoothFaceColorAtVertex(
            int[] faceSamples,
            int direction,
            float x,
            float y,
            float z,
            float[] output
    ) {
        requireFaceSamples(faceSamples);
        requireDirection(direction);
        if (output.length < 3) {
            throw new IllegalArgumentException("Smooth-color output must contain all three channels");
        }
        float u = switch (direction) {
            case 0, 1, 2, 3 -> x;
            case 4, 5 -> z;
            default -> throw new AssertionError("Unreachable direction");
        };
        float v = switch (direction) {
            case 0, 1 -> z;
            case 2, 3, 4, 5 -> y;
            default -> throw new AssertionError("Unreachable direction");
        };
        u = clampUnit(u);
        v = clampUnit(v);
        int start = direction * CORNERS_PER_FACE;
        output[0] = interpolateChannel(faceSamples, start, u, v, 0);
        output[1] = interpolateChannel(faceSamples, start, u, v, GREEN_SUM_SHIFT);
        output[2] = interpolateChannel(faceSamples, start, u, v, BLUE_SUM_SHIFT);
    }

    private static void addFaceSample(int[] output, int direction, int tangentU, int tangentV, int light) {
        int minU = tangentU > 0 ? 1 : 0;
        int maxU = tangentU < 0 ? 0 : 1;
        int minV = tangentV > 0 ? 1 : 0;
        int maxV = tangentV < 0 ? 0 : 1;
        int packedChannels = PackedLight.red(light)
                | PackedLight.green(light) << GREEN_SUM_SHIFT
                | PackedLight.blue(light) << BLUE_SUM_SHIFT;
        int start = direction * CORNERS_PER_FACE;
        for (int cornerV = minV; cornerV <= maxV; cornerV++) {
            for (int cornerU = minU; cornerU <= maxU; cornerU++) {
                output[start + cornerU + (cornerV << 1)] += packedChannels;
            }
        }
    }

    private static float interpolateChannel(int[] samples, int start, float u, float v, int shift) {
        float low = lerp(u, channel(samples[start], shift), channel(samples[start + 1], shift));
        float high = lerp(u, channel(samples[start + 2], shift), channel(samples[start + 3], shift));
        return lerp(v, low, high) / (CORNERS_PER_FACE * 15.0F);
    }

    private static int channel(int sample, int shift) {
        return sample >>> shift & CHANNEL_SUM_MASK;
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static boolean insideUnitInterval(float value) {
        return value >= -FACE_EPSILON && value <= 1.0F + FACE_EPSILON;
    }

    private static void requireFaceSamples(int[] faceSamples) {
        if (faceSamples.length < FACE_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Face-light samples must contain all 24 face corners");
        }
    }

    private static void requireDirection(int direction) {
        if (direction < 0 || direction >= FACE_COUNT) {
            throw new IllegalArgumentException("direction must be between 0 and 5");
        }
    }

}
