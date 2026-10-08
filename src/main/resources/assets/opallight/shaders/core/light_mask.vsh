#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in ivec2 UV2;
in vec4 Color;
in vec3 LightColor;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 GroupOffset;

out float vertexDistance;
out vec2 texCoord0;
out vec4 modelColor;
out vec3 lightColor;
out vec2 lightUv;

void main() {
    vec3 pos = Position + GroupOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    texCoord0 = UV0;
    // Interpolate contributions before hue normalization or nonlinear display transforms.
    modelColor = Color;
    lightColor = LightColor;
    lightUv = vec2(UV2);
}
