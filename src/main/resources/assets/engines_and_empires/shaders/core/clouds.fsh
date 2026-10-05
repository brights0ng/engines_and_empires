#version 150

#moj_import <fog.glsl>

// CloudTint is the sky's cloud colour (day, night, dusk, vanilla's rain dimming). The clouds fade into the fog colour
// between CloudFogStart and CloudFogEnd, their own distances: vanilla's terrain fog ends at the render distance, well
// short of the clouds. Flash brightens the cloud for lightning (0 = none).

uniform vec4 ColorModulator;
uniform vec4 FogColor;
uniform vec3 CloudTint;
uniform float CloudFogStart;
uniform float CloudFogEnd;
uniform float Flash;

in vec4 vertexColor;
in float vertexDistance;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    color.rgb *= CloudTint;
    color.rgb = mix(color.rgb, vec3(1.0, 1.0, 1.05), clamp(Flash, 0.0, 1.0) * 0.8);
    fragColor = linear_fog(color, vertexDistance, CloudFogStart, CloudFogEnd, vec4(FogColor.rgb, 1.0));
}
