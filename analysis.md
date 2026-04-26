# Analysis

## Current Task

Implement the first small physical-key behavior changes: physical Delete on an
empty editor exits the IME, T9 mode switching shows an input-method-owned badge,
and English Caps/Shift adopts that badge when no multi-tap character is pending.

## Pending Physical-Key Requests

- When the target editor/input field has no text, pressing the physical Delete
  key should exit the input method using the same logic as the existing
  on-screen exit-IME button, instead of acting as a normal backspace.
- First implementation step: handle only physical Delete on key down, after T9
  pending composition/punctuation handling and before forwarding to Fcitx or the
  target editor. The action should call `requestHideSelf(0)`, matching the
  on-screen exit control.
- Switching between Chinese, English, and numeric modes needs a more obvious
  animated confirmation than the current subtle feedback.
- The mode-switch animation should not reuse the existing English Caps/Shift
  visual behavior if that behavior risks accidentally committing text.
- Next implementation step: add an input-method-owned mode badge for
  Chinese/English/numeric switches. It should be rendered inside `InputView`
  instead of using `InputConnection.setComposingText()`.
- English Caps/Shift now adopts the new animation style and removes its
  original no-pending-character composing-text feedback.
- Current implementation step: migrate English Caps/Shift's no-pending-character
  feedback from editor composing text to the same input-method-owned badge. If a
  multi-tap character is pending, keep refreshing that pending composing
  character because it represents real text the user may commit.
- Follow-up feedback: the mode badge animation should be faster so physical-key
  mode/case changes feel more responsive.
- The space key should continue showing the current Chinese/English mode label.

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

## T9 Punctuation Candidate Feedback

The local Chinese `1` punctuation flow fixed the external `1Password` inline
suggestion path by not sending a bare `1` through the normal editor/Rime route.
However, it also removed the candidate-window style punctuation choices the user
expected from the previous Chinese input behavior. The fix should keep `1`
local, but expose the active punctuation set as a local T9 candidate page so the
existing Hanzi candidate UI still appears.

The first local candidate-page attempt still auto-committed the first
punctuation because it reused the multi-tap timeout behavior, and key-up could
also see the punctuation composing text as Chinese composition. A local
candidate page should behave like a candidate window: stay pending until explicit
selection/confirmation or until another normal input commits it.

The follow-up test still showed auto-commit on repeated `1` and `*` because the
key-down path also computed Chinese composition from the local punctuation
preview before checking pending punctuation. Pending punctuation must be handled
before regular Chinese composition checks on both key-down and key-up.

The next desired behavior is closer to normal Hanzi selection: preview the
focused symbol in the input method's top preedit row, do not use the pinyin
filter row, and show symbols in the Hanzi candidate row. This means local
punctuation preview should not use `InputConnection.setComposingText()` at all,
because editor-side composition can be committed by system/Rime transitions.

DPAD navigation was still ineffective because the generic pending-punctuation
"commit before unrelated input" guard ran before candidate focus navigation and
did not exclude DPAD arrows/OK. Candidate navigation keys must be allowed through
to `handleT9CandidateFocusNavigation()` while punctuation is pending.

The user wants a larger punctuation pool split across multiple candidate pages,
with each page sized by the same T9 candidate budget used for Hanzi candidates.
The service should expose the full local punctuation pool, while `CandidatesView`
should paginate that pool locally with `T9CandidateBudget`.

At punctuation page boundaries, DPAD Up on the first page or Down on the last
page can fall through to the editor and move the text cursor. While local
punctuation is pending, candidate-control keys should be consumed even when they
cannot move focus or change pages.

## Reported T9 Issues

- Chinese `1` punctuation can surface a `1Password` suggestion.
- After pressing `1`, `*` should switch the pending punctuation to English
  symbols.
- Pinyin candidates are missing for readings such as `jiang`, `liang`, `kuan`,
  and `kuang`.
- The pinyin display should update after Hanzi candidate selection.

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

## Pinyin Preview Feedback

The user reports that the top pinyin preview still does not track Hanzi
candidate selection. For ambiguous digit sequences such as `2`, the default
digit-to-pinyin display can stay on the first option (`a`) even when the
highlighted Hanzi candidate has a different reading. Without an explicit pinyin
filter, the top preview should prefer the highlighted candidate's pinyin comment
so users can see which reading they are about to commit.

Follow-up feedback clarifies that the top pinyin preview also communicates how
many T9 digit keys have been entered. Candidate comments can contain the full
reading for prefix matches, for example `ai` or `ba` after only pressing `2`.
The preview should therefore follow the highlighted candidate's reading, but
truncate that reading to the current composition key count: `ai` becomes `a`,
and `ba` becomes `b` for a single entered key.

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

## Constraints

- Project files, code, comments, and docs must stay in English.
- Chat change summaries must be in Chinese.
- Changes should be surgical and traceable to the reported issues.
- Do not run full Gradle builds or comprehensive tests; use basic static/syntax
  checks only.
- Functional Android Studio/device testing is left to the user.
- Implement only the empty-editor physical Delete step and the T9 mode-switch
  badge for now; leave Caps/Shift feedback migration for a later small,
  testable step.

## Edge Cases

- Chinese punctuation should not discard active composition unexpectedly.
- A pending punctuation character should be committed before unrelated input,
  mode switching, or return/space actions.
- `*` should keep its existing literal behavior in Chinese mode unless there is
  a pending `1` punctuation choice to switch.
- Pinyin filtering must not hide all useful Hanzi just because a longer pinyin
  map entry is missing.
- Pinyin preview should follow the highlighted Hanzi candidate reading when no
  explicit pinyin filter has been selected.
- Candidate-based pinyin preview should be truncated to the number of current
  T9 digit keys so the preview remains a key-count indicator.
- T9 candidate refresh should avoid swapping to an empty intermediate page while
  async bulk filtering is pending.
- T9 Hanzi candidates should honor the user-configured character budget even
  when Rime's current page contains fewer candidates.
- T9 Hanzi focus should reset to the first candidate whenever the T9
  input/filter context changes, even if a pending request is still displaying
  the previous stable candidate page.
- Chinese `1` punctuation should show a local candidate page for the current
  punctuation set instead of hiding the candidate window.
- Local punctuation preview should live in the input method preedit row, not in
  editor composing text.
- DPAD arrows/OK should navigate or commit the local punctuation candidate page
  instead of triggering the generic pending-punctuation commit guard.
- Local punctuation candidates should support multiple pages using the
  user-configured T9 candidate budget.
- Local punctuation candidate navigation should consume DPAD keys at page/focus
  boundaries so the editor cursor does not move.
- Clearing transient input state should hide inline suggestions without disabling
  Android inline suggestions for normal autofill fields.
- Adding all 71 missing pinyin strings is small in APK/code size, and the user
  confirmed continuing with the complete pinyin-map coverage step.
- After completing the map, the static audit reports 424 Rime dictionary
  syllables, 451 local T9 map strings, and 0 missing Rime syllables. The local
  count is higher because the map also contains single-letter prefix candidates
  and compatibility spellings such as `lue`/`nue`.
- Delete-on-empty must not interfere with normal deletion when the editor still
  contains text, active composition, pending punctuation, or selectable
  candidates. Only the truly empty-editor case should invoke the same exit-IME
  behavior as the existing on-screen exit button.
- The empty-editor check can be narrow: no selected text, no composing range, no
  local T9 composition state, no pending English multi-tap character, no pending
  T9 punctuation, no text before the cursor, and no text after the cursor.
- Mode-switch feedback should be visible enough for physical-key use, but
  should not create a text-commit path or consume input in a way that changes
  composition unexpectedly.
- The first mode-switch feedback step should only affect T9 mode switching.
  English Caps/Shift can keep its current behavior until the mode badge is
  tested successfully.
- Caps/Shift migration should remove the old delayed composing-text indicator
  path so a stale dismiss runnable cannot clear unrelated composing text.

## Previous Completed Work

- Removed the abandoned GitHub remote APK build workflow.
- Changed the T9 Hanzi candidate budget default from 12 to 10.
- Kept the Rime plugin on inherited shared versioning with no local override.
