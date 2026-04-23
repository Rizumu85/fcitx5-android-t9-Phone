# T9 Analysis - fcitx5-android-t9-phone

Scope: this fork adds phone-style T9 input to fcitx5-android, mainly for Chinese input through Rime. This document is written for a follow-up coding agent and should be read together with `plan.md`.

## 1. Confirmed Product Target

1. Primary goal: Chinese T9 input. English multi-tap and number mode should keep working, but the main new UX work is Chinese T9.
2. Keep T9 state in Kotlin for now. The current `T9CompositionTracker` / `T9PinyinUtils` design may be simplified, but do not move the logic into a new C++ Rime processor.
3. Use yuyansdk only as a behavioural reference for a reliable candidate/pinyin row. Do not port its full keyboard, symbol database, haptics, mode switcher, or visual style.
4. Touch is a first-class secondary input path. Physical T9 keys are primary, but every visible T9 action should also be doable by touch.
5. Touch and hardware paths must share the same action functions. For example, tapping a pinyin chip and pressing OK on a highlighted pinyin chip should both call the same pinyin-selection function.

## 2. Desired Candidate UX

Both rows should be visible during Chinese T9 composition:

```text
+------------------------------------------+
| Pinyin selector row                       |  top row
+------------------------------------------+
| Rime Chinese candidate row                |  bottom row, default focus
+------------------------------------------+
| T9 keyboard                               |
+------------------------------------------+
```

Expected behaviour:

- Default focus is the bottom row, so the user can directly choose Chinese candidates.
- Physical UP moves focus to the top pinyin row.
- Physical DOWN moves focus back to the bottom candidate row.
- OK/Enter while the top row is focused selects the highlighted pinyin through the same path as a chip tap.
- Selecting a top-row pinyin filters the bottom row by replacing the current digit segment with the selected pinyin letters in Rime.
- Tapping a row moves focus to that row. Tapping a pinyin chip selects it and returns focus to the bottom row.
- Hit targets should stay at least 40 dp tall.

## 3. Current Implementation Snapshot

Current source was checked before this rewrite. Several older plan assumptions were stale.

### T9 Routing And State

- Main routing lives in `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`.
- Important methods:
  - `handleT9SpecialKey`
  - `handleDigitKey`
  - `handleT9SpecialKeyUp`
  - `forwardKeyEvent`
  - `getT9PinyinCandidates`
  - `getT9PreeditDisplay`
  - `selectT9Pinyin`
- State currently includes `currentT9Mode`, `t9CapsLock`, `t9ShiftActive`, `digitLongPressFlags`, English multi-tap fields, and `t9CompositionTracker`.
- `digitLongPressFlags` is already a `MutableMap<Int, Boolean>`, not the older fixed-size BooleanArray mentioned in the previous plan. The old array-index crash fix is no longer the right step.
- `T9CompositionTracker.clear()` already exists.
- `syncT9CompositionWithInputPanel(...)` already exists in the service, but the active candidate UI path must call it before reading pinyin candidates.

### Active Candidate UI

There are two pinyin-row implementations in the tree:

- `app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt` has an embedded T9 pinyin row (`pinyinBarScroll` / `pinyinBarContainer`) above `candidatesUi`.
- `app/src/main/java/org/fcitx/fcitx5/android/input/t9/PinyinSelectionBarComponent.kt` also renders pinyin chips, but `rg` found no active references to that component from `InputView` or other input UI setup.

Conclusion: fix the active pinyin row in `CandidatesView.kt` first. Do not spend implementation time on `PinyinSelectionBarComponent.kt` unless a fresh source check proves it is actually registered.

### T9 Enabled Preference

`useT9KeyboardLayout` currently controls whether the virtual keyboard starts as T9 in `KeyboardWindow.kt`. Some T9 routing code in `FcitxInputMethodService.kt` can still run before checking that preference.

Plain-English meaning of the edge case:

- Turning off the virtual T9 keyboard preference may hide the on-screen T9 keyboard.
- It does not necessarily mean physical number keys stop being interpreted as T9.
- A user with a physical keypad could therefore get T9 behaviour without seeing the T9 on-screen layout.

User preference clarified after the first rewrite: the setting can reasonably become "T9 mode enabled" rather than only "use T9 keyboard layout". When enabled, both the virtual T9 UI and T9 key routing should be enabled. When disabled, both should be disabled.

Implementation note: do not casually rename the persisted preference key from `use_t9_keyboard_layout` to `use_t9_mode` unless a migration is added. Safer first pass:

- Keep the stored key for compatibility.
- Rename local code aliases to something like `t9ModeEnabled`.
- Change the user-facing preference label/description to "Enable T9 mode" if needed.
- Gate routing, tracker updates, candidate-row special UI, keyboard layout, and T9 height off this one semantic value.

## 4. Defect List

| ID | Severity | Current location | Problem |
|---|---:|---|---|
| B1 | High | `CandidatesView.kt` `updatePinyinBar` | Active pinyin row uses `removeAllViews` + manual chip creation inside `HorizontalScrollView`. This matches the reported intermittent refresh issue. |
| B2 | Medium | `FcitxInputMethodService.kt` `getT9PreeditDisplay` and English multi-tap path | English pending multi-tap text is not exposed through the shared T9 preedit display. Confirm exact visible symptom before coding because `handleMultiTapKey` also calls `setComposingText` directly. |
| B3 | Resolved / obsolete | Older plan only | The older BooleanArray raw-keycode crash no longer matches current code. Current code uses a map. Do not implement the old Step 3. |
| B4 | Medium | `T9PinyinUtils.kt` | `t9KeyToPinyin` still truncates input to six digits with `take(6)`, silently ignoring later keys. |
| B5 | Medium | `handleT9SpecialKey`, STAR in Chinese mode | STAR currently falls through in Chinese mode. Desired feature: toggle full-width versus half-width punctuation using existing fcitx/Rime punctuation support. |
| B6 | Low / conditional | POUND short press | POUND short press confirm behaviour is ambiguous. Skip unless the user can reproduce a real problem. |
| B8 | Medium | `handleDigitKey`, number mode | Number-mode long-press behaviour for 1 and 0 does not implement the documented symbol/space behaviour. |
| B9 | High | STAR in English mode plus `t9CapsLock` / `t9ShiftActive` | Rapid STAR presses or STAR short-then-long can leave English mode stuck in caps. Root cause is likely split shift/caps flags plus KEY_DOWN/KEY_UP double handling. |
| B10 | Medium | Tracker/Rime sync | Kotlin tracker and Rime composing state can desync. `clear()` and `syncT9CompositionWithInputPanel` exist, but the active candidate path must call sync reliably. |
| B11 | High | Candidate focus | Top pinyin row and bottom Rime candidate row have no shared focus state or UP/DOWN/OK navigation. |
| B12 | High | `selectT9Pinyin` and active pinyin chip click | Tapping a pinyin chip can fail to commit/filter in Rime. Likely causes: stale view hierarchy, click not reaching service, wrong backspace count, or send-order desync. |
| B13 | Medium | `useT9KeyboardLayout` preference scope | User now leans toward one semantic T9 mode: the preference should gate both UI and routing. Keep the stored key or add migration if renaming. |
| B14 | Medium | Duplicate pinyin-row implementations | `PinyinSelectionBarComponent.kt` appears unused while `CandidatesView.kt` has the active row. This can mislead future fixes and should be removed or documented after B1/B12 are fixed. |
| B15 | High | Virtual/on-screen delete path plus `t9CompositionTracker` | User repro: entering T9, then deleting with the on-screen delete button removes visible pinyin, but after the last pinyin is deleted an older full pinyin reappears without Hanzi candidates. After typing new input, pinyin/Hanzi update but the pinyin-candidate row does not. Likely cause: virtual delete bypasses the same tracker/Rime sync used by hardware `forwardKeyEvent`, leaving stale Kotlin tracker state or stale adapter contents. |
| B16 | High | Focus-out / touch-away handling and candidate surfaces | User repro: tapping away hides the input UI as desired, but another Hanzi candidate bar appears at the bottom/top of keyboard area, seemingly the default fcitx5 candidate UI; continuing to type keeps the old pre-touch composition. Likely cause: touch-away hides UI but does not reset/focus-out Rime composition and all candidate surfaces consistently. |

## 5. Reference From yuyansdk

Use only the following ideas:

- Candidate/pinyin row backed by `RecyclerView` and an adapter.
- Stable list update pattern: replace list, notify/diff, reset row state when input changes.
- Touch selection calls the same commit function as hardware selection.

Do not port:

- Full keyboard UI.
- Haptics or sound.
- Symbol database.
- Input-mode switcher.
- Cloud input or frequency reranking.

## 6. Implementation Dependencies

Recommended order:

1. Fix T9 enabled gating and state reset/sync first: B13, B10, B15, B16.
2. Fix the active row and pinyin selection path: B1, B12.
3. Fix the English STAR state machine: B9.
4. Add the two-row focus model: B11.
5. Complete smaller documented behaviours: B8, B4, B5.
6. Remove stale duplicate UI and refactor only after behaviour is stable: B14 and controller extraction.

The old plan order put tracker sync first, but part of that work already exists. The revised plan keeps the intent while changing Step 1 to wire the existing sync method into the active UI path.

## 7. Verification Notes

- Use correct T9 examples. For example, `ai` maps to digits `2 4`, not `2 3`.
- Every interaction step needs both a physical-key verification and a touch verification when a touch surface exists.
- Any step touching candidate rows should verify scroll, refresh, chip click, and row visibility.
- Any step touching physical navigation should log the real hardware keycodes first. Some phone keypads do not send standard Android DPAD keycodes.
