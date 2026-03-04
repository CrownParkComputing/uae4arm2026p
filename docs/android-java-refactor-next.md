# Android Java Refactor Plan (Next Iteration)

## Scope
Target incremental refactor with no behavior change, focused on maintainability of launcher, media import, and IGDB flows.

## Priority 1: Bootstrap orchestration boundaries
- Keep `BootstrapActivity` as UI orchestration only.
- Move remaining mixed concerns into dedicated collaborators:
  - boot launch decision/state (`BootLaunchPolicy`)
  - drive/media persistence (`DriveStateStore`)
  - restart/cold-start reset semantics (`BootstrapResetService`)
- Standardize one entry path for start/launch so AGS-vs-explicit-media precedence is enforced in one place.

## Priority 2: Activity result handling consistency
- Consolidate all requestCode routing behind `BootstrapActivityResultRouter` with typed handlers.
- Normalize error/reporting contract (toast + log + return status object).
- Add small integration tests around import result mapping edge cases (ADF multi-disk, CUE companions, cancelled picks).

## Priority 3: IGDB search/candidate strategy
- Extract LHA IGDB logic from `LhaLibraryActivity` to a service class:
  - query expansion
  - candidate scoring
  - category-aware filtering (arcade/amiga)
- Add deterministic unit tests for scoring and fallback query generation.
- Keep UI layer limited to rendering candidates and applying selected result.

## Priority 4: Path/SAF health model
- Replace scattered checks with a single reusable `PathHealthSnapshot` model.
- Make startup decisions consume snapshot object (prompt / continue / auto-repair).
- Reduce duplicate SAF normalization code (`content://`, joined path, rel path).

## Priority 5: Shared preference key ownership
- Introduce per-domain pref wrappers:
  - launcher prefs
  - drive prefs
  - IGDB cache prefs
- Ensure each wrapper has read/write helpers and migration points for future schema changes.

## Priority 6: Logging and diagnostics
- Use structured tags for bootstrap/import/IGDB logs with event IDs.
- Keep verbose diagnostics behind debug-build gate.
- Add one command/documented script for artifact collection to avoid ad-hoc local files in repo root.

## Suggested execution order
1. Bootstrap launch policy extraction + tests.
2. Activity result router hardening.
3. LHA IGDB service extraction + tests.
4. Path health snapshot unification.
5. Pref wrappers and cleanup.
6. Logging standardization.

## Acceptance criteria
- No user-visible behavior regressions in startup, AGS launch, DH0 boot, CD import, and LHA IGDB correction.
- `BootstrapActivity` line count reduced materially.
- New logic covered by focused unit tests for:
  - launch precedence,
  - query expansion/scoring,
  - path health decisions.
