# OpenGL → Vulkan Migration Assessment (Android fork)

## Current state (this repo)

- Android build runs in **minimal mode** (`AMIBERRY_ANDROID_MINIMAL=ON`), and this mode currently forces `USE_OPENGL=OFF`.
- SDL on Android includes Vulkan platform plumbing, but the emulator rendering code is still built around OpenGL-specific paths when `USE_OPENGL` is enabled.
- Upstream Amiberry Android build enables OpenGL (`-DUSE_OPENGL=ON`), so this fork and upstream currently differ in rendering defaults.

## Evidence in code

- `android/build.gradle`
  - passes `-DAMIBERRY_ANDROID_MINIMAL=ON` to CMake.
- `cmake/SourceFiles.cmake`
  - in Android minimal branch, forces `USE_OPENGL OFF`.
- `CMakeLists.txt`
  - defines `USE_OPENGL` build option.
- `cmake/Dependencies.cmake`
  - wires OpenGL/GLEW dependencies and compile definitions when `USE_OPENGL` is enabled.
- `amiberry-src/osdep/amiberry_gfx.cpp`
  - large renderer code path with many `#ifdef USE_OPENGL` branches.
- `external/SDL/src/video/android/SDL_androidvulkan.*`
  - Vulkan integration points available at SDL layer.

## Feasibility

### Short answer

A direct “flip OpenGL to Vulkan” switch is **not** realistic in this fork today.

### Why

- Current Android minimal build disables OpenGL already, so there is no single active GL backend to swap out in this configuration.
- The core rendering architecture is not currently expressed as a clean backend interface (GL/Vulkan); OpenGL concerns are spread through large rendering modules.

## Recommended migration path

### Phase 0: Baseline and target definition

1. Decide target behavior for Android first:
   - Keep minimal mode and add Vulkan only there, or
   - Align closer to upstream Android path first.
2. Define measurable goals:
   - startup success rate,
   - frame pacing stability,
   - RTG compatibility,
   - input/UI overlay correctness.

### Phase 1: Renderer abstraction

1. Introduce a renderer backend interface in `osdep` (device/context/swapchain-like API, texture upload, blit/composite, shader/pipeline hooks).
2. Move non-renderer policy (aspect mode, overlays, LED/status composition decisions) out of OpenGL blocks where possible.
3. Keep current behavior as reference backend during extraction to avoid regressions.

### Phase 2: Vulkan backend (Android-first)

1. Implement Vulkan backend behind the new interface.
2. Use SDL Vulkan window/surface hooks for platform integration.
3. Preserve feature parity with existing path before optimization.

### Phase 3: Bring-up and hardening

1. Add runtime/backend selection and safe fallback.
2. Validate on representative GPU vendors (Adreno/Mali/PowerVR if available).
3. Compare against baseline performance and correctness metrics.

## Risk and effort

- Scope: **medium-high to high** (multi-file renderer architecture work, not a flag tweak).
- Primary risk: regressions in RTG, overlays/input composition, and timing-sensitive rendering behavior.
- Suggested execution: incremental PRs with compile + runtime checks at each step.

## Suggested immediate next steps

1. Perform one additional safe refactor in `amiberry_gfx.cpp` to isolate backend-neutral logic.
2. Add a `renderer_backend` config key (`auto|legacy|vulkan` placeholder), wired but defaulting to current behavior.
3. Create a small Android validation checklist (boot model matrix + RTG test set) to gate backend milestones.
