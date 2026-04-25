# Analysis

## Current Task

Continue from the reported T9 fixes, correct the speculative theme change, and
audit the T9 pinyin mapping for systemic missing readings beyond the examples
the user tested.

## Build Feedback

The user ran `:app:assembleDebug`, and Kotlin compilation failed in
`CandidatesView.kt` because `selectT9ShownHanziCandidate()` referenced the local
`t9InputModeEnabled` variable from `update()` outside its scope. The fix is to
query the service state from that helper instead of using the out-of-scope local.

## Candidate Flicker Feedback

The user reports that the earlier part of the UI is stable, but the later
pinyin/Hanzi area briefly flickers before appearing. The most likely path is
`CandidatesView.requestT9BulkFilteredCandidatesIfNeeded()`: when the signature
changes, it clears `t9BulkFilteredPaged` and related state before the async
`getCandidates()` request returns. During that pending window, `updateUi()` can
render the current-page fallback or an empty filtered page, then render the bulk
result on the next refresh. This creates a visible blink in the T9 candidate
area.

After keeping the previous page during pending requests, the flicker is gone,
but the first input still shows the later pinyin/Hanzi part being filled after
the initial UI. This is still explained by the same later-added async bulk path:
early stable builds rendered only the current Fcitx/Rime page synchronously,
while the newer path starts a bulk `getCandidates()` request even when no pinyin
filter is selected. First input has no previous stable bulk page, so it still
has a visible second render.

Skipping no-filter bulk loading was the wrong tradeoff: it avoided one delayed
render path but broke the stronger product rule that the Hanzi row should honor
the user's T9 candidate budget. With the user's current test, both unfiltered and
filtered candidate rows still show only 5 entries, because the normal paged Rime
callback is still limited to Rime's page and Android no longer asks the bulk
candidate list for enough candidates before budget slicing.

The correct behavior is: always build the shown T9 Hanzi page from a candidate
pool large enough for the user's budget. Rime `menu/page_size` should still be
raised to 24 so the normal callback is less starved, but Android must not depend
on that alone. The Android T9 view should use the bulk candidate pool for both
no-filter and filtered T9 states, then apply the local character-budget paging.

## Candidate Focus Feedback

The user reports that the active Hanzi focus bubble can briefly appear on a later
candidate while deleting input or while selecting/unselecting a pinyin filter,
then jump back to the first candidate. This is likely caused by reusing the
previous stable bulk page while a new bulk request is pending: the visible
candidate list can remain identical for one frame, so the candidate-list
signature does not reset `t9HanziCursorIndex`, even though the T9 input/filter
context changed. The cursor reset key needs to include the T9 context
(`preedit`/resolved prefixes), not only the visible candidate list.

## Reported T9 Issues

- Chinese `1` punctuation can surface a `1Password` suggestion.
- After pressing `1`, `*` should switch the pending punctuation to English
  symbols.
- Pinyin candidates are missing for readings such as `jiang`, `liang`, `kuan`,
  and `kuang`.
- The pinyin display should update after Hanzi candidate selection.
- Defer keyboard skin/theme work until the user provides the design direction
  and can test each incremental step.

## Pinyin Coverage Audit

`T9PinyinUtils` is a hand-maintained map from T9 digit groups to pinyin strings.
The source explicitly labels 5-key and 6-key coverage as a subset. Comparing the
map against `plugin/rime/src/main/assets/usr/share/rime-data/luna_pinyin.dict.yaml`
shows:

- Rime dictionary syllables found: 424.
- T9 pinyin map syllables covered: 380.
- Missing dictionary syllables: 71.

Missing syllables found in the audit:

`biang`, `cei`, `chang`, `cheng`, `chong`, `chua`, `chuai`, `chuan`, `chuang`,
`chui`, `chun`, `chuo`, `cong`, `cuan`, `din`, `duan`, `eh`, `fong`, `guai`,
`guan`, `guang`, `huai`, `huan`, `huang`, `jiong`, `juan`, `kuai`, `lvan`,
`lve`, `nia`, `niang`, `nong`, `nuan`, `nun`, `nve`, `pia`, `qiang`, `qiong`,
`quan`, `rong`, `rua`, `ruan`, `sei`, `shang`, `shei`, `sheng`, `shua`,
`shuai`, `shuan`, `shuang`, `shui`, `shun`, `shuo`, `song`, `suan`, `tuan`,
`wong`, `xiang`, `xiong`, `yai`, `zhang`, `zhei`, `zheng`, `zhong`, `zhua`,
`zhuai`, `zhuan`, `zhuang`, `zhui`, `zhun`, `zhuo`.

The examples `jiang`, `liang`, `kuan`, and `kuang` were symptoms of this
broader incomplete-map design. A robust fix should complete the map against the
Rime dictionary syllable set instead of adding only user-discovered examples.

## README Understanding

The app is a fork/customization of Fcitx5 for Android focused on physical
nine-key phones. It supports Chinese T9 mode, English multi-tap mode, numeric
mode, long-press digit entry, a compact persistent screen keyboard, Rime-based
Chinese input, pinyin prediction/filtering, and `#` mode switching.

## Current Behavior and Discoveries

- Repo-level `analysis.md`, `design.md`, and `plan.md` already exist and should
  be updated rather than replaced.
- No repo-level `AGENTS.md` existed before this task.
- T9 behavior is concentrated mostly in
  `FcitxInputMethodService.kt`, `input/t9/*`, `CandidatesView.kt`, and
  `T9Keyboard.kt`.
- Chinese `1` currently passes through to Rime when not long-pressed. The local
  tracker also treats `1` as an apostrophe separator. That can make the key act
  like composition input instead of a local punctuation key.
- The app already has Android inline suggestion UI. If `1Password` appears as
  an autofill/inline suggestion, that content is external to the input method.
  Still, making Chinese `1` a local punctuation key avoids feeding `1` into the
  Chinese engine for ordinary punctuation entry, and clearing transient inline
  suggestions when local punctuation starts prevents that external suggestion
  from occupying the T9 punctuation moment.
- `T9PinyinUtils` lacks several common pinyin entries needed by the reported
  readings, including `jiang`, `liang`, `kuan`, and `kuang`.
- Theme presets are centralized in `ThemePreset.kt`, and the visible built-in
  list is `ThemeManager.BuiltinThemes`; theme additions should not be made
  without the user's concrete direction.
- The speculative built-in themes added in the previous pass were reverted.

## Constraints

- Project files, code, comments, and docs must stay in English.
- Chat change summaries must be in Chinese.
- Changes should be surgical and traceable to the reported issues.
- Do not run full Gradle builds or comprehensive tests; use basic static/syntax
  checks only.
- Functional Android Studio/device testing is left to the user.

## Edge Cases

- Chinese punctuation should not discard active composition unexpectedly.
- A pending punctuation character should be committed before unrelated input,
  mode switching, or return/space actions.
- `*` should keep its existing literal behavior in Chinese mode unless there is
  a pending `1` punctuation choice to switch.
- Pinyin filtering must not hide all useful Hanzi just because a longer pinyin
  map entry is missing.
- T9 candidate refresh should avoid swapping to an empty intermediate page while
  async bulk filtering is pending.
- T9 Hanzi candidates should honor the user-configured character budget even
  when Rime's current page contains fewer candidates.
- T9 Hanzi focus should reset to the first candidate whenever the T9
  input/filter context changes, even if a pending request is still displaying
  the previous stable candidate page.
- Theme work should be split into small user-tested steps instead of adding
  finished themes speculatively.
- Clearing transient input state should hide inline suggestions without disabling
  Android inline suggestions for normal autofill fields.
- Adding all 71 missing pinyin strings is small in APK/code size, and the user
  confirmed continuing with the complete pinyin-map coverage step.
- After completing the map, the static audit reports 424 Rime dictionary
  syllables, 451 local T9 map strings, and 0 missing Rime syllables. The local
  count is higher because the map also contains single-letter prefix candidates
  and compatibility spellings such as `lue`/`nue`.

## Previous Completed Work

- Removed the abandoned GitHub remote APK build workflow.
- Changed the T9 Hanzi candidate budget default from 12 to 10.
- Kept the Rime plugin on inherited shared versioning with no local override.
