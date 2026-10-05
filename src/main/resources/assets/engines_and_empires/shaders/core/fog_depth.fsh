#version 150

// Pushes terrain the fog has fully hidden to the far plane, so the clouds draw over it: it is drawn in the fog colour,
// but its depth would still hide whatever is behind it. Writes depth only.
//
// DepthSampler is a copy of the depth buffer after solid terrain. InvViewProj takes a pixel back to its position
// relative to the camera, and its fog distance is worked out as the terrain shader does (FogShape 0 sphere,
// 1 cylinder). Past TerrainFogEnd the terrain is pure fog colour: move it back.

uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform float TerrainFogEnd;
uniform int TerrainFogShape;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (depth >= 1.0) {
        discard;
    }
    vec4 p = InvViewProj * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 pos = p.xyz / p.w;
    float distance = TerrainFogShape == 0 ? length(pos) : max(length(pos.xz), abs(pos.y));
    if (distance < TerrainFogEnd) {
        discard;
    }
    gl_FragDepth = 1.0;
    fragColor = vec4(0.0);
}
