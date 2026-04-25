# Design

## Project Goal

Provide a comfortable Android input method for physical T9-key phones, centered
on Chinese T9 through Rime, English multi-tap, numeric entry, compact on-screen
controls, and a readable themeable keyboard surface.

## Current Task Design

Create a repo-level `AGENTS.md` that merges Rizum planning/documentation rules
with Karpathy-style simplicity, assumptions, and surgical implementation rules.
The file should be concise enough for future agents to follow without rereading
the full skill files.

## T9 Punctuation Design

Handle Chinese `1` punctuation locally when the user is not already composing
Chinese text. Use a pending punctuation character, similar to English multi-tap,
so repeated `1` cycles punctuation and timeout commits it. While a Chinese
punctuation character is pending, `*` toggles the pending character between the
Chinese and English punctuation sets.

Keep `*` as a literal star in Chinese mode when there is no pending punctuation,
preserving existing behavior outside the new `1` punctuation workflow.

Clear transient inline suggestions when the local punctuation flow starts. This
keeps Android autofill suggestions such as password-manager chips out of the
punctuation interaction without turning inline suggestions off globally.

Represent pending T9 punctuation as a local candidate page in `CandidatesView`.
The candidate page should use the active Chinese or English punctuation list,
highlight the current multi-tap punctuation index, and commit the selected
punctuation through the service without calling Rime candidate selection.
Moving the Hanzi focus across this local page should preview the focused
punctuation in the input method's top preedit row so later confirmation commits
the visible choice.
Do not auto-commit local Chinese punctuation on the multi-tap timeout while the
candidate page is visible; consume the matching key-up locally so the composing
punctuation is not mistaken for regular Chinese composition. Pending punctuation
is a higher-priority transient state than Chinese composition for `1`, `*`, `0`,
and `#` handling.

Do not use editor-side composing text for local punctuation preview. While
punctuation is pending, `getT9PresentationState()` should return the focused
symbol as `topReading` and an empty pinyin option list.

Treat DPAD arrows and OK as candidate-control keys while local punctuation is
pending. They should bypass the generic "commit pending punctuation before other
input" guard and be handled by normal candidate focus navigation.

Keep the full Chinese/English punctuation pools in the service, but paginate
them in `CandidatesView` with the same `T9CandidateBudget` logic as Hanzi
candidates. Map shown symbol indices back to their global punctuation indices so
preview and commit work correctly across pages.

When local punctuation is pending, consume DPAD arrows and OK even if the
candidate page cannot move further. Boundary navigation should be a no-op inside
the input method, not a cursor movement in the target editor.

## T9 Pinyin Design

The current static T9 pinyin map is incomplete for longer syllables. The audit
shows 71 Rime dictionary syllables that cannot appear in the pinyin candidate
row. The next pinyin fix should complete the map against the Rime dictionary
syllable set rather than adding only individually reported examples.

Prefer a surgical completion of the existing map for now: merge missing strings
into existing group keys where keys already exist, and add missing group keys
where needed. This keeps UI behavior and ordering close to the current design
while removing the known coverage gaps.

After the map change, rerun the same static coverage comparison against the
bundled Rime dictionary. Success means zero missing Rime syllables in the local
T9 pinyin map.

When a Hanzi candidate is selected without an explicit pinyin filter, update the
local T9 model from the selected candidate's pinyin comment when possible. This
keeps the displayed pinyin composition aligned with consumed Hanzi segments.
Helpers outside `update()` should use `service.isChineseT9InputModeActive()` for
T9 state instead of relying on local variables scoped to `update()`.

When no explicit pinyin filter has been selected, the top pinyin preview should
prefer the currently highlighted Hanzi candidate's comment reading over the
default digit-to-pinyin guess. Moving the Hanzi focus should refresh the preview
row so ambiguous digit sequences show the selected candidate's reading.
Because the same row also indicates how many T9 digit keys have been entered,
candidate comment readings should be cropped by the current T9 key count before
display. This keeps prefix matches intuitive: after one `2`, a highlighted `ai`
candidate previews `a`, and a highlighted `ba` candidate previews `b`.

## T9 Candidate Refresh Design

When a new bulk-filter request is needed, keep the last stable filtered page
visible while the async request is pending. Replace it only when the matching
request returns. This avoids a transient empty candidate render while preserving
the existing fallback path for the first request or when no previous page exists.

T9 Hanzi rendering should prioritize the user's character budget over the raw
Rime page size. Request a bulk candidate pool for both no-filter and
prefix-filtered T9 states, then slice that pool with `T9CandidateBudget`. Keep
the last stable bulk page visible while a replacement request is pending.

Also ship Rime's default `menu/page_size` as 24 instead of 5, matching the app
preference's upper bound. This improves the normal paged callback and reduces
the chance that the first visible state is starved, but Android should still use
the bulk pool as the authoritative T9 budget source.

Reset the Hanzi candidate cursor from both the visible candidate-list signature
and the T9 context signature. The context signature should cover the current
preedit text and resolved pinyin filter prefixes so deletion and filter toggles
do not temporarily reuse an old highlighted candidate index.

## Theme Design

Do not add themes speculatively. Theme/skin work should wait for the user's
visual direction, then proceed in small testable steps.

## Non-Goals

- Do not redesign the T9 engine or Rime bridge.
- Do not add user-configurable punctuation maps.
- Do not remove Android inline suggestions globally.
- Do not run full Android builds or device tests in this task.

## Previous Completed Design

- The abandoned remote APK build workflow was deleted instead of disabled.
- The T9 Hanzi candidate character budget remains an integer managed preference
  with default `10` and range `4..24`.
- The Rime plugin remains on inherited shared versioning.
