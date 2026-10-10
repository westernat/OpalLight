#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 GroupOffset;
uniform samplerBuffer PreviousColors;
uniform int BlendVertexColors;
uniform float TransitionWeight;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 pos = Position + GroupOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    texCoord0 = UV0;
vertexColor = Color;
if (BlendVertexColors != 0) {
// 每个顶点占六个 RGBA8 纹素，最后一个保存颜色。
vertexColor = mix(texelFetch(PreviousColors, gl_VertexID * 6 + 5), Color, TransitionWeight);
}
}
