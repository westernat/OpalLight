package org.mesdag.opallight.light;

/// 彩光网格的分组尺寸，供调度、几何和渲染共用。
final class LightMeshLayout {
    static final int GROUP_XZ_SECTION_SHIFT = 1;
    static final int GROUP_Y_SECTION_SHIFT = 0;
    static final int GROUP_XZ_BLOCK_SHIFT = 4 + GROUP_XZ_SECTION_SHIFT;
    static final int GROUP_Y_BLOCK_SHIFT = 4 + GROUP_Y_SECTION_SHIFT;

    private LightMeshLayout() {}
}
