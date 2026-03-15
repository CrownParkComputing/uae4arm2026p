#version 450
layout(push_constant) uniform PC {
    vec4 viewport;
    vec4 uv;
    float alpha;
    int mode;
    vec4 color;
    vec2 pad;
} pc;
layout(location = 0) in vec2 a_pos;
layout(location = 0) out vec2 v_uv;
void main() {
    gl_Position = vec4(a_pos * pc.viewport.zw + pc.viewport.xy, 0.0, 1.0);
    vec2 t = (a_pos + 1.0) * 0.5;
    v_uv = mix(pc.uv.xy, pc.uv.zw, t);
}
