#version 150

// The high deck's see-through clouds (CloudVeilRenderer): a grid around the camera at the deck's height. Its colour
// carries the cirrostratus (red) and cirrus (green) cover at each point; the pattern is made in cloud_veil.fsh, in
// the deck's own frame (the world less the deck's drift), so it moves with the clouds.

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// The grid's origin relative to the camera, moved on by the deck's drift since the grid was made.
uniform vec3 GridOffset;
// The grid's origin in the deck's frame.
uniform vec2 PatternOrigin;

out vec4 cover;
out vec2 pattern;
out vec3 viewVector;
out float horizontal;

void main() {
    vec3 pos = Position + GridOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    cover = Color;
    pattern = Position.xz + PatternOrigin;
    viewVector = pos;
    horizontal = length(pos.xz);
}
