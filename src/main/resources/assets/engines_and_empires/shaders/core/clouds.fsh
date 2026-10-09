#version 150

#moj_import <fog.glsl>

// Colour (2026-10-07, sunrise and sunset; 2026-10-07 evening, live direction): a vertex's red is the sky's light on
// it, its green the most of the sun's (or moon's) light it can take, its blue how much of that light gets through the
// cloud toward it (CloudVoxelizer.pack), and its normal its surface's direction. Which side faces the light
// (LightDir), the sun's contrast (the side away from it ShadowSide as bright) and the light's strength are worked out
// here every frame, so the light turns smoothly through dusk, the afterglow and the night on every cloud at once,
// rebuilt or not (CloudShading is the same in Java; keep them in step). SkyColor and SunColor colour the parts by the
// time of day (CloudColours), rain's dimming included. The clouds fade into the fog colour between CloudFogStart and
// CloudFogEnd, their own distances: vanilla's terrain fog ends at the render distance, well short of the clouds.
// Flash brightens the cloud for lightning (0 = none).
//
// Silver lining (2026-10-07): looking toward the sun (LightDir, the way to the sun or moon), light scattered forward
// through thin edges (blue ~1) makes them glow: nearly white with a hint of the sun's hue by day, warmer as the sun
// gets low (2026-10-07 evening, Bright: it was a sickly yellow).
//
// Lightning (weather phase 6c, 2026-10-08, Bright: a local glow): up to four flashes, each FlashX = its position
// relative to the camera (xyz) and brightness now (w, flickering, set by ClientLightning every frame), its glow radius
// in FlashRadii. A flash lights the cloud around it: brightly within its radius, a faint halo over twice that. The
// glow is added after the distance fog and only partly fogged, so a storm on the horizon still flickers at night.

uniform vec4 ColorModulator;
uniform vec4 FogColor;
uniform vec3 SkyColor;
uniform vec3 SunColor;
uniform float CloudFogStart;
uniform float CloudFogEnd;
uniform float Flash;
uniform vec3 LightDir;
uniform float LightStrength;
uniform float SilverStrength;
uniform float ShadowSide;
uniform vec4 FlashA;
uniform vec4 FlashB;
uniform vec4 FlashC;
uniform vec4 FlashD;
uniform vec4 FlashRadii;

in vec4 vertexColor;
in vec3 vertexNormal;
in float vertexDistance;
in vec3 viewVector;

out vec4 fragColor;

const float WRAP = 0.35;
const float BELOW = 0.2;
const vec3 FLASH_COLOR = vec3(0.88, 0.92, 1.0);

float glowOf(vec4 flash, float radius) {
    if (flash.w <= 0.0) {
        return 0.0;
    }
    float d = length(viewVector - flash.xyz) / max(radius, 1.0);
    return flash.w * (exp(-2.0 * d * d) + 0.2 * exp(-0.5 * d * d));
}

void main() {
    float nl = length(vertexNormal);
    vec3 n = nl > 1e-4 ? vertexNormal / nl : vec3(0.0, 1.0, 0.0);
    vec3 l = LightDir;
    // A light from below (the afterglow) reaches the underside with nothing in its way.
    float below = clamp(-l.y / BELOW, 0.0, 1.0);
    float through = mix(vertexColor.b, 1.0, below);
    float direct = clamp(through * (dot(n, l) + WRAP) / (1.0 + WRAP), 0.0, 1.0);
    // The sun's contrast acts on what faces sideways or up; a base facing down keeps the sky light alone, unless the
    // light is from below.
    float up = clamp(l.y / BELOW, -1.0, 1.0);
    float contrast = (1.0 - ShadowSide) * clamp(1.0 + n.y * up, 0.0, 1.0);
    float skyPart = vertexColor.r * (1.0 - contrast * LightStrength);
    float sunPart = vertexColor.g * contrast * direct * LightStrength;
    vec3 lit = skyPart * SkyColor + sunPart * SunColor;
    vec4 color = vec4(lit, 1.0) * ColorModulator;
    float toward = max(dot(normalize(viewVector), LightDir), 0.0);
    // Forward scattering: strong within ~25 degrees of the sun, a soft halo further out.
    float phase = pow(toward, 12.0) + 0.25 * pow(toward, 3.0);
    float silver = SilverStrength * LightStrength * phase * vertexColor.b * vertexColor.b;
    vec3 sunHue = SunColor / max(max(SunColor.r, SunColor.g), max(SunColor.b, 0.001));
    // Mostly white: a fifth of the sun's hue with the sun high, up to three fifths as it nears the horizon.
    float warm = mix(0.6, 0.2, smoothstep(0.0, 0.35, l.y));
    vec3 liningHue = mix(vec3(1.0), sunHue, warm);
    color.rgb = mix(color.rgb, liningHue * min(1.0, max(max(SunColor.r, SunColor.g), SunColor.b)), clamp(silver, 0.0, 0.85));
    color.rgb = min(color.rgb, vec3(1.0));
    color.rgb = mix(color.rgb, vec3(1.0, 1.0, 1.05), clamp(Flash, 0.0, 1.0) * 0.8);
    fragColor = linear_fog(color, vertexDistance, CloudFogStart, CloudFogEnd, vec4(FogColor.rgb, 1.0));
    float glow = glowOf(FlashA, FlashRadii.x) + glowOf(FlashB, FlashRadii.y) + glowOf(FlashC, FlashRadii.z)
            + glowOf(FlashD, FlashRadii.w);
    if (glow > 0.0) {
        float fogT = clamp((vertexDistance - CloudFogStart) / max(CloudFogEnd - CloudFogStart, 1.0), 0.0, 1.0);
        fragColor.rgb = min(fragColor.rgb + FLASH_COLOR * clamp(glow, 0.0, 1.5) * (1.0 - 0.6 * fogT), vec3(1.0));
    }
    fragColor.a = 1.0;
}