#version 150

// The high deck's see-through clouds (2026-10-07 evening, Bright): cirrostratus as strips along the wind with the sky
// between nearly every one (in some places the real thin milky veil instead), cirrus as sparse, hooked fibres, the
// wispiest of all. Made here from noise in the deck's frame (pattern, blocks; WindDir the wind's direction), faded in
// by the cover at each point (cover.r cirrostratus, cover.g cirrus) with ragged edges. CloudVeilPattern is the same in
// Java, for the tests' pictures: keep them in step.
//
// Light: thin ice lets the light through, so the sky's light and the sun's both colour it, and it glows toward the
// sun. High clouds stay lit a little longer after sunset (CloudVeilRenderer gives them the sun of a little higher).

uniform vec3 SkyColor;
uniform vec3 SunColor;
uniform vec3 LightDir;
uniform float SunStrength;
uniform vec2 WindDir;
uniform float FogStart;
uniform float FogEnd;
uniform float Opacity;

in vec4 cover;
in vec2 pattern;
in vec3 viewVector;
in float horizontal;

out vec4 fragColor;

float hash(vec2 c) {
    uvec2 q = uvec2(ivec2(c));
    uint h = (q.x * 1597334677u) ^ (q.y * 3812015801u);
    h ^= h >> 16;
    h *= 2246822519u;
    h ^= h >> 13;
    return float(h) * (1.0 / 4294967295.0);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Four octaves, 0-1.
float fbm(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int k = 0; k < 4; k++) {
        s += a * noise(p);
        p = p * 2.03 + vec2(17.1, 9.3);
        a *= 0.5;
    }
    return s / 0.9375;
}

void main() {
    vec2 w = normalize(WindDir);
    // Along the wind (u) and across it (v).
    vec2 q = vec2(dot(pattern, w), dot(pattern, vec2(-w.y, w.x)));

    float edge = fbm(pattern / 700.0 + 3.7) - 0.5;
    float cs = smoothstep(0.1, 0.8, cover.r + 0.45 * edge);
    float ci = smoothstep(0.1, 0.8, cover.g + 0.45 * edge);
    if (cs <= 0.0 && ci <= 0.0) {
        discard;
    }

    // Cirrostratus: strips along the wind about 210 blocks apart, bent and wobbling, of varying width, broken here
    // and there, fibrous.
    float bend = fbm(q / vec2(1800.0, 1000.0) + 5.1);
    float wobble = fbm(q / vec2(600.0, 400.0) + 15.0);
    float f = fract(q.y / 210.0 + 1.6 * bend + 0.35 * wobble);
    float width = 0.3 + 0.35 * fbm(q / vec2(800.0, 300.0) + 19.0);
    float strip = smoothstep(0.0, 0.15, f) * (1.0 - smoothstep(width, width + 0.22, f));
    float fib = fbm(q / vec2(450.0, 24.0));
    float breaks = smoothstep(0.3, 0.55, fbm(q / vec2(900.0, 260.0) + 7.3));
    float stripA = 0.8 * strip * breaks * (0.45 + 0.55 * fib);
    // In some places the plain milky veil.
    float veil = smoothstep(0.52, 0.7, fbm(pattern / 6000.0 + 2.2));
    float veilA = 0.3 * (0.7 + 0.3 * fbm(q / vec2(300.0, 60.0) + 13.0));
    float aCs = mix(stripA, veilA, veil);

    // Cirrus: fibres along the wind, curled by a warp into hooks, in sparse tufts.
    vec2 warp = vec2(fbm(q / vec2(600.0, 300.0) + 11.0), fbm(q / vec2(600.0, 300.0) + 23.0)) - 0.5;
    float fibres = fbm((q + warp * vec2(500.0, 160.0)) / vec2(340.0, 30.0) + 31.0);
    float tufts = smoothstep(0.35, 0.65, fbm(pattern / 1400.0 + 41.0));
    float aCi = 0.8 * smoothstep(0.44, 0.7, fibres) * tufts;

    float alpha = 1.0 - (1.0 - aCs * cs) * (1.0 - aCi * ci);
    alpha = min(1.0, alpha * (1.0 - smoothstep(FogStart, FogEnd, horizontal)) * Opacity);
    if (alpha <= 0.003) {
        discard;
    }
    float toward = max(dot(normalize(viewVector), LightDir), 0.0);
    float glow = 0.6 * pow(toward, 8.0) + 0.2 * pow(toward, 2.0);
    vec3 col = SkyColor * 0.75 + SunColor * SunStrength * (0.45 + glow);
    fragColor = vec4(min(col, vec3(1.0)), alpha);
}
