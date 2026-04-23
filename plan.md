# Implementation Plan - T9 Fixes And Features

Audience: another coding agent. Read `analyse.md` first. The current source already contains partial T9 fixes, so verify the source before each step instead of blindly following older line numbers.

## Ground Rules

1. Work on the active pinyin row in `CandidatesView.kt` unless a fresh source check proves `PinyinSelectionBarComponent.kt` is registered.
2. Keep changes surgical. Do not reformat nearby code or refactor while fixing behaviour.
3. One step should be reviewable on its own. If a step grows, split it.
4. Every step must define manual on-device verification before coding.
5. Touch and hardware paths must call the same underlying action functions.
6. Do not add a new Rime processor or move T9 logic to C++.
7. Do not implement the old BooleanArray long-press indexing step; current code already uses a map.
8. Use correct T9 examples in tests. `ai` is `2 4`, not `2 3`.
9. Treat the current `use_t9_keyboard_layout` preference as the semantic "T9 mode enabled" flag unless a proper preference migration is added.

## Phase 0 - Baseline

### Step 0 - Confirm Active Paths And Build

Purpose: avoid fixing stale or unused code.

Change:

- No behaviour change.
- Run a source check for `PinyinSelectionBarComponent`, `CandidatesView.updatePinyinBar`, `selectT9Pinyin`, and `syncT9CompositionWithInputPanel`.
- Build the app or run the closest available compile check.
- If `PinyinSelectionBarComponent` is still unused, mark it as stale in the PR notes and do not edit it yet.

Files:

- No required edits.

Verify:

- Build passes or the exact existing build failure is documented.
- The active pinyin row is confirmed before Step 1 starts.

## Phase A - T9 State And Candidate Correctness

### Step 1 - Unify T9 Enabled Gating (B13)

Purpose: make the setting mean one thing: T9 mode is either on for both UI and routing, or off for both.

Decision:

- Do this, but do not rename the persisted preference key in the first pass.
- Keep `use_t9_keyboard_layout` as the storage key to avoid migration risk.
- In code, introduce clearer local naming such as `t9ModeEnabled` or `isT9ModeEnabled`.
- Optionally change the user-facing preference label to "Enable T9 mode" later.

Change:

- Gate T9 routing entry points with the same value used for the T9 UI:
  - `handleT9SpecialKey`
  - `handleDigitKey`
  - `handleT9SpecialKeyUp`
  - T9 tracker updates in `forwardKeyEvent`
  - `mapKeyEvent` if a mapping is T9-only
- Gate T9 candidate/preedit customizations with the same helper.
- Keep normal fcitx5 behaviour untouched when T9 mode is disabled.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/InputDeviceManager.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt`
- Candidate UI files that read `useT9KeyboardLayout`

Verify:

- With T9 enabled, the on-screen T9 keyboard appears and physical number keys route through T9.
- With T9 disabled, the normal keyboard appears and physical number keys do not use T9 routing.
- Existing `use_t9_keyboard_layout` user preference value is preserved after app update.

### Step 2 - Centralize T9 Reset And Delete Sync (B10, B15, B16)

Purpose: fix the reported stale pinyin state after on-screen delete and after tapping away from the input field.

New user repros:

- On-screen delete removes visible pinyin, but after deleting the last pinyin an older full pinyin reappears without Hanzi candidates. New typing updates pinyin/Hanzi but not the pinyin-candidate row.
- Tapping away hides the input UI as desired, but another default-looking Hanzi candidate bar appears, and continuing to type keeps the old pre-touch composition.

Likely root causes:

- Hardware key events update `t9CompositionTracker` in `forwardKeyEvent`, but virtual/on-screen delete can travel through `FcitxEvent.KeyEvent` / `handleBackspaceKey` and bypass the same tracker update.
- Focus-out or touch-away hides a UI surface but does not clear or focus-out Rime composition and all local T9 state.
- The active candidate view may read candidates before calling the existing sync method.

Change:

- Add one helper for clearing all local T9 composition UI state, for example `clearT9CompositionState(reason: String)`, wrapping `t9CompositionTracker.clear()` and any pinyin-row/focus reset needed later.
- Ensure every delete path updates the tracker consistently:
  - Hardware Backspace/DEL.
  - Virtual/on-screen delete from the T9 keyboard.
  - Any mapped BACK to DEL path.
- When Rime reports empty preedit/composition, clear the tracker and submit an empty pinyin list to the active pinyin row.
- On start input, restart input, touch-away/focus-out, and cursor updates that trigger `reset()`, clear local T9 state before or together with the Rime reset.
- Do not make the pinyin row `GONE` without also clearing its adapter/list state.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt`
- Possibly `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt` if its delete action bypasses service helpers.

Verify:

- Type a T9 sequence, delete it fully using the on-screen delete button, then type a new sequence. Old pinyin never reappears, Hanzi candidates correspond to the new sequence, and the pinyin-candidate row refreshes.
- Repeat with the physical delete/back key.
- Type a T9 sequence, tap away so the input UI disappears, then return and type again. Previous composition does not remain in Rime, custom candidates, or any default candidate bar.
- If a default-looking candidate bar still appears, capture which view owns it before moving on.

### Step 3 - Wire Tracker Sync Into The Active Candidate Path (B10)

Purpose: prevent stale Kotlin T9 digits from surviving after Rime clears composition.

Current state:

- `T9CompositionTracker.clear()` already exists.
- `FcitxInputMethodService.syncT9CompositionWithInputPanel(...)` already exists.
- The active `CandidatesView` path must call it before reading T9 pinyin candidates.

Change:

- In `CandidatesView.handleFcitxEvent` or `updateUi`, call `service.syncT9CompositionWithInputPanel(inputPanel)` before `service.getT9PinyinCandidates()` or `service.getT9PreeditDisplay()` is used.
- Keep the method idempotent. Calling it on every input panel update is fine.
- Do not change pinyin selection behaviour in this step.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt`
- Possibly `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt` only if the existing sync method needs a tiny guard.

Verify:

- Type `2 4`; pinyin chips such as `ai` appear.
- Clear composition by committing, cancelling, or leaving the field.
- Type a new digit; old chips do not reappear.
- Touch: tap a pinyin chip after the clear/retype cycle and confirm it acts on only the new segment.

### Step 4 - Replace The Active Pinyin Row With RecyclerView (B1, B15)

Purpose: fix intermittent chip refresh and make later focus/highlight work reliable.

Change:

- Replace `CandidatesView`'s `HorizontalScrollView` + `LinearLayout` pinyin chip row with a horizontal `RecyclerView`.
- Add a small adapter, preferably in the T9 package, with:
  - `submitList(newCandidates: List<String>)`
  - stable item IDs if simple
  - `highlightedIndex` placeholder for the later focus-navigation step
  - one click callback that calls the shared pinyin selection function
- Preserve current visual sizing, especially the row height and at least 40 dp touch height.
- Reset scroll to the left when the candidate list changes.
- Submit an empty list when there are no pinyin candidates; do not rely only on `visibility = GONE`.
- Do not change `selectT9Pinyin` yet unless the click cannot be wired without a tiny callback signature change.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt`
- New adapter file, for example `app/src/main/java/org/fcitx/fcitx5/android/input/t9/T9PinyinChipAdapter.kt`

Verify:

- Type `2`, `2 4`, `2 4 6` rapidly ten times. Chips update every time with no stale entries.
- Fully delete using the on-screen delete button, then type `2 4`; the row shows only the new `2 4` candidates.
- Scroll the pinyin row horizontally if enough chips exist.
- Rotate the device or reopen the keyboard during composition; chips re-render.
- Touch: chip hit targets remain comfortable and tapping still reaches the click callback.

### Step 5 - Fix Pinyin Selection Transaction (B12)

Purpose: make chip tap and future OK-key selection reliably replace the active digit segment in Rime.

Investigate first:

- Add temporary logs at adapter click and `selectT9Pinyin` entry.
- Reproduce with `2 4` then tap `ai`.
- Confirm whether the click reaches the service and whether Rime receives the expected backspaces and letters.

Likely change:

- Use the current segment length from `t9CompositionTracker.getCurrentSegmentKeyLength()` for the backspace count, not `T9PinyinUtils.pinyinToT9Keys(pinyin).length`.
- Keep `selectT9Pinyin(pinyin)` as the single shared commit function for both touch and hardware.
- If Rime needs the backspaces flushed before letters, post the letter-send phase one UI/frame tick later. Do this only if logs prove send ordering is the issue.
- After successful selection, update tracker state consistently with the segment replacement.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- Adapter from Step 4 if the click callback needs adjustment.

Verify:

- Type `2 4`; tap `ai`; Rime composition becomes `ai` and bottom candidates filter accordingly.
- Type `2 4 6`; select a shown pinyin; the whole active segment is replaced cleanly, with no leftover digit.
- Touch: tap two different chips across two compositions; each commit affects only the active segment.
- Hardware readiness: there is still exactly one service function that sends backspaces plus pinyin letters.

### Step 6 - Remove Or Quarantine The Stale Pinyin Component (B14)

Purpose: avoid future fixes landing in an unused component.

Change:

- If `PinyinSelectionBarComponent.kt` is confirmed unused, remove it or add a clear comment that it is legacy and not registered.
- Prefer removal if the build proves no dependency references it.
- Do not do this before Steps 2-5, because active behaviour matters more than cleanup.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/t9/PinyinSelectionBarComponent.kt`

Verify:

- Build passes.
- Pinyin row still appears through `CandidatesView`.

## Phase B - English Mode Bug Fixes

### Step 7 - Replace Split Shift/Caps Flags With One State Machine (B9)

Purpose: prevent English mode from getting stuck in caps.

Change:

- Replace `t9CapsLock` and `t9ShiftActive` with one enum: `OFF`, `SHIFT_ONCE`, `CAPS`.
- Required transitions:

| From | STAR short press | STAR long press |
|---|---|---|
| OFF | SHIFT_ONCE | CAPS |
| SHIFT_ONCE | OFF | CAPS |
| CAPS | OFF | OFF |

- Ensure long press suppresses the same key event's short-press action.
- Keep KEY_DOWN/KEY_UP handling simple and centralized.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`

Verify:

- Rapid STAR ten times never leaves state stuck.
- STAR short press, then a letter: one uppercase letter commits and state returns to OFF.
- STAR long press enters CAPS. STAR long press again exits CAPS.
- STAR short press while in CAPS exits CAPS.
- Touch: if the virtual T9 keyboard exposes the same action, it must call the same state transition function.

### Step 8 - Confirm Or Fix English Multi-Tap Preedit (B2)

Purpose: make pending English multi-tap text visible in the same user-facing surface as other T9 preedit.

Change:

- First verify the actual symptom after Step 7.
- If pending multi-tap text is not visible where the user expects it, update `getT9PreeditDisplay` to return the pending English character when `currentT9Mode` is English.
- Apply the enum state from Step 7 for case.
- Do not duplicate commit logic.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- Possibly `app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt` if it filters out English-mode display.

Verify:

- In English mode, press `2` once; pending `a` is visible.
- Press `2` again within timeout; pending `b` replaces it.
- Let timeout expire; `b` commits and preedit clears.
- SHIFT_ONCE and CAPS display the correct uppercase pending character.

## Phase C - Desired Two-Row Candidate UX

### Step 9 - Add Candidate Focus State And Touch-To-Focus (B11)

Purpose: establish one shared focus model before wiring physical navigation.

Change:

- Add a focus state with two values: top pinyin row and bottom candidate row. Default is bottom.
- Put the state near the other T9 state for now; extraction comes later.
- Add a small visible indicator for the focused row.
- Tapping empty space on the top row moves focus to top.
- Tapping a pinyin chip selects it through `selectT9Pinyin` and returns focus to bottom.
- Tapping the bottom row or a bottom candidate moves focus to bottom and keeps existing candidate selection behaviour.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt`
- Pinyin adapter from Step 4

Verify:

- Type `2 4`; both rows are visible.
- Touch: tap top-row empty space; focus indicator moves to top and nothing commits.
- Touch: tap bottom-row area; focus indicator moves to bottom.
- Touch: tap `ai`; pinyin commits, bottom candidates update, focus returns to bottom.

### Step 10 - Wire Physical UP, DOWN, And OK (B11)

Purpose: make the two-row UX usable from a physical T9 keypad.

Required investigation:

- Log one press each for the actual hardware UP, DOWN, OK/Enter, and optionally LEFT/RIGHT keys.
- Store supported keycodes in one private helper or value set. Do not scatter keycode literals.

Change:

- UP moves focus to the top pinyin row and consumes the event.
- DOWN moves focus to the bottom candidate row and consumes the event.
- OK/Enter while top is focused commits the highlighted pinyin through the same `selectT9Pinyin` path and returns focus to bottom.
- OK/Enter while bottom is focused falls through to current candidate behaviour.
- Add `highlightedIndex` to the pinyin adapter. If LEFT/RIGHT keycodes are known, use them to move the highlight while top is focused; otherwise keep highlight at the first chip for v1.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt`
- Pinyin adapter from Step 4

Verify:

- Type `2 4`; focus starts on bottom.
- Press UP; indicator moves to top.
- Press OK; highlighted pinyin commits and focus returns to bottom.
- Press DOWN while top is focused; no pinyin commits and focus returns to bottom.
- Type the next digit after selecting pinyin; Rime accepts it as the next composition segment.
- Touch: alternate physical UP/DOWN with row taps; the last input always wins focus.

## Phase D - Documented Smaller Features

### Step 11 - Number-Mode Long-Press Behaviour (B8)

Purpose: finish documented number-mode shortcuts.

Change:

- In number mode, short-press digits still commit digits.
- Long-press `0` commits a space.
- Long-press `1` commits the agreed simple symbol. If no picker exists, commit a single default punctuation symbol and document that v1 limitation.
- Do not build a full symbol picker in this step.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`

Verify:

- Number mode short-press `0` and `1` still commits digits.
- Long-press `0` commits space.
- Long-press `1` commits the chosen symbol.

### Step 12 - Remove Six-Digit Pinyin Lookup Truncation (B4)

Purpose: stop silently ignoring long T9 sequences.

Change:

- Remove `take(6)` from `T9PinyinUtils.t9KeyToPinyin`.
- If the result list gets too large, cap the returned list, not the input string.
- Prefer a conservative returned-list cap such as 20.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/t9/T9PinyinUtils.kt`

Verify:

- Type a seven-digit sequence.
- Chips still update and no earlier digit silently disappears from lookup.
- Rendering remains smooth.

### Step 13 - Chinese-Mode STAR Punctuation Toggle (B5)

Purpose: implement the requested full-width versus half-width punctuation toggle.

Change:

- In Chinese mode, STAR short press toggles punctuation shape using the existing fcitx/Rime option or punctuation action. Do not create a parallel punctuation map unless no existing option is available.
- Show a small mode indicator using existing UI patterns.
- Long-press STAR should remain unchanged unless current behaviour is explicitly broken.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- Possibly the T9 keyboard mode-label helper if an indicator is needed there.

Verify:

- In Chinese mode, STAR toggles punctuation shape.
- The punctuation key commits different shape punctuation after each toggle.
- English STAR shift/caps from Step 7 still works.

### Step 14 - POUND Confirm Behaviour (B6, Conditional)

Purpose: only fix this if a real reproduction appears.

Change:

- Skip by default.
- If the user reports POUND not confirming or switching correctly, write a dedicated repro and fix only that path.

Files:

- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`

Verify:

- Define after the user provides a reproduction.

## Phase E - Preference Cleanup

### Step 15 - Optional Stored Preference Rename Or Migration (B13 follow-up)

Purpose: only rename the persisted preference key if we want cleaner settings storage after Step 1.

Default:

- Skip. Step 1 should already make the existing preference behave like T9 mode enabled.
- Keep the existing stored key `use_t9_keyboard_layout` for compatibility.

Only do this if a settings cleanup is explicitly requested:

- Add migration from `use_t9_keyboard_layout` to a new key such as `use_t9_mode`.
- Update any backup/export settings behaviour if this app has one.
- Update strings to explain that the toggle controls both UI and routing.

Verify if implemented:

- Existing users keep their previous enabled/disabled value after upgrade.
- With T9 disabled, both virtual T9 UI and physical T9 routing are disabled.
- With T9 enabled, all T9 behaviour from Steps 1-13 still works.

## Phase F - Refactor Last

### Step 16 - Extract `T9InputController`

Purpose: reduce `FcitxInputMethodService.kt` only after behaviour is stable.

Change:

- Move T9 mode/state/routing helpers into `app/src/main/java/org/fcitx/fcitx5/android/input/t9/T9InputController.kt`.
- Service should keep a single controller field and delegate.
- Move only after Steps 1-13 are stable.
- Do not change behaviour in this step.

Suggested controller-owned state:

- Current T9 mode.
- Shift/caps enum.
- English multi-tap state.
- Long-press flags.
- T9 composition tracker.
- Candidate focus.
- Punctuation shape state if Step 13 adds it.

Verify:

- All manual verification from Steps 1-13 still passes.
- `FcitxInputMethodService.kt` shrinks substantially.
- The diff reads mostly like move-and-delegate, not a rewrite.

## Intentionally Out Of Scope

- No new haptic or sound feedback layer.
- No visual redesign to match yuyansdk.
- No cloud input.
- No frequency-learning beyond Rime.
- No new C++ Rime processor.
- No swipe gestures for candidate rows.
