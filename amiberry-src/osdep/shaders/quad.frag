#version 450
layout(push_constant) uniform PC {
    vec4 viewport;
    vec4 uv;
    float alpha;
    int mode;
    vec4 color;
    vec2 pad;
} pc;
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 fragColor;
void main() {
    if (pc.mode == 1) {
        fragColor = pc.color;
        fragColor.a *= pc.alpha;
    } else {
        fragColor = texture(tex, v_uv);
        fragColor.a = pc.alpha;
    }
}
