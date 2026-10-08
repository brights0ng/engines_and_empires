#version 150

// Engines and Empires voxel clouds. Vertices are local to the cloud's centre in x and z and in world height in y;
// CloudOffset moves them to where the cloud is now, relative to the camera.

in vec3 Position;
in vec4 Color;
// The surface's direction, for the live lighting (clouds.fsh).
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 CloudOffset;
// 1 inside a cloud: fog by full 3D distance, so the cloud above and below fades too.
uniform float CloudFogSphere;
// Per formation, a tiny pull toward the camera in depth, so overlapping clouds' faces at the same height don't
// z-fight (one of a few layers per formation; CloudMeshes.Drawable.depthLayer).
uniform float DepthBias;

out vec4 vertexColor;
out vec3 vertexNormal;
out float vertexDistance;
// From the camera to this vertex (world directions), for the silver lining.
out vec3 viewVector;

void main() {
    vec3 pos = Position + CloudOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    gl_Position.z -= DepthBias * gl_Position.w;
    // Horizontal distance: a storm's top kilometres overhead shouldn't fade, only clouds far across the land.
    vertexDistance = mix(length(pos.xz), length(pos), CloudFogSphere);
    vertexColor = Color;
    vertexNormal = Normal;
    viewVector = pos;
}