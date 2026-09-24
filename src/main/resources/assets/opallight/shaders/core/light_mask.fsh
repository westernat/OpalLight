#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float TransitionWeight;

in vec4 vertexColor;
in vec2 texCoord0;
in float vertexDistance;

out vec4 fragColor;

void main() {
    float fogFade = mix(1.0, linear_fog_fade(vertexDistance, FogStart, FogEnd), FogColor.a);
    if (fogFade <= 0.0) discard;
    vec4 texColor = texture(Sampler0, texCoord0);
    if (texColor.a < 0.1) discard;
fragColor = vec4(texColor.rgb * vertexColor.rgb * vertexColor.a * texColor.a * fogFade * TransitionWeight, 1.0);
}
