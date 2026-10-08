package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.nio.ByteBuffer;

/// Java/GLSL 的顶点数据边界：模型 RGBA、原版受光、未经显示转换的 RGB 贡献。
final class LightMaskVertex {
    static final VertexFormatElement LIGHT_COLOR = VertexFormatElement.register(VertexFormatElement.findNextId(),
            0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 3);
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV2", VertexFormatElement.UV2)
            .add("Color", VertexFormatElement.COLOR)
            .add("LightColor", LIGHT_COLOR)
            .build();

    private LightMaskVertex() {}

    static void write(ByteBuffer output, float x, float y, float z, float u, float v,
                      int modelRgb, float modelAlpha, int blockUv, int skyUv,
                      float red, float green, float blue) {
        output.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        output.putShort((short) blockUv).putShort((short) skyUv);
        output.put((byte) (modelRgb >>> 16)).put((byte) (modelRgb >>> 8)).put((byte) modelRgb);
        output.put((byte) Math.round(modelAlpha * 255));
        output.putFloat(red).putFloat(green).putFloat(blue);
    }
}
