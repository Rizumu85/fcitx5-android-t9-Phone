# Plan

## Working Agreement

Rizum Guidelines are active for this project/thread until the user says otherwise.

## Current Checklist

- [x] Document the two requested mockup-based built-in themes.
- [x] Add the two theme presets in `ThemePreset.kt`.
- [x] Register the new presets in `ThemeManager.BuiltinThemes`.
- [x] Keep the black space bar label readable with a narrow contrast guard.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Mute the inactive T9 candidate row based on whether focus is on pinyin or Hanzi candidates.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Make `InkBlack` black-only, keep alternate control icons black, and set keyboard body backgrounds to white.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Tint KawaiiBar toolbar icons gray without changing keyboard key icon tint.
- [x] Make pinyin/Hanzi candidate bubble backgrounds follow the keyboard body color.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Match the mode/Caps indicator badge corner radius to the space bar.
- [x] Run a narrow static/syntax check for the touched Kotlin file only.
- [x] Resize the mode/Caps indicator as a compact space-bar-like strip.
- [x] Add a subtle low-opacity bottom shadow to the pinyin/Hanzi candidate bubble.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Narrow the mode/Caps indicator and strengthen the candidate bubble shadow range.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Rename the new light themes to `InkBlack` and `InkPink`.
- [x] Add dark built-in variants named `InkBlackDark` and `InkPinkDark`.
- [x] Register the renamed light themes and new dark variants in the built-in theme list.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Fix dark Ink space-bar background/text contrast.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Reduce the global return key icon/background circle size.
- [x] Record that compile checks should be skipped for routine visual tuning unless debugging is needed.
- [x] Document the pending Delete and mode-switch animation requests without implementing them.
- [x] Decide that physical Delete on an empty editor should use the same exit-IME logic as the on-screen exit button.
- [x] Design the empty-editor Delete guard around composition, pending punctuation, candidates, and normal text deletion.
- [x] Implement physical Delete on an empty editor using the existing exit-IME logic.
- [x] Run a narrow static/syntax check for the touched Kotlin file only.
- [x] Design a clearer Chinese/English/numeric mode-switch animation that is independent from text commit paths.
- [x] Implement an input-method-owned T9 mode-switch badge without changing Caps/Shift feedback.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Accept the mode-switch badge as the feedback style for English Caps/Shift.
- [x] Migrate English Caps/Shift no-pending-character feedback to the input-method-owned badge.
- [x] Remove the old composing-text mode indicator path.
- [x] Run a narrow static/syntax check for the touched Kotlin files only.
- [x] Keep the space key's Chinese/English mode label while changing mode-switch feedback.
- [x] Shorten the mode badge animation timing for faster feedback.
- [x] Run a narrow static/syntax check for the touched Kotlin file only.
- [x] Read the README and existing planning docs to understand the app and current context.
- [x] Create a repo-level `AGENTS.md` merging Rizum and Karpathy guidelines.
- [x] Refresh `analysis.md`, `design.md`, and `plan.md` for the reported T9 work.
- [x] Implement local Chinese `1` punctuation cycling and `*` punctuation-set switching.
- [x] Add the missing T9 pinyin readings for `jiang`, `liang`, `kuan`, and `kuang`.
- [x] Update the local T9 pinyin display after Hanzi candidate selection when the candidate comment identifies the consumed reading.
- [x] Audit the T9 pinyin map against the bundled Rime Luna Pinyin dictionary.
- [x] Complete the T9 pinyin map for the audited missing syllables.
- [x] Run limited syntax/static checks only, then hand functional testing to the user.
- [x] Update this plan with completed steps and report the change summary in Chinese.
- [x] Fix the `CandidatesView.kt` Kotlin compile error reported by the user's `:app:assembleDebug` run.
- [x] Reduce T9 pinyin/Hanzi flicker by keeping the last stable bulk-filtered page while a new request is pending.
- [x] Restore T9 candidate display so both filtered and unfiltered Hanzi rows honor the user-configured character budget.
- [x] Raise Rime `menu/page_size` to the maximum T9 Hanzi character budget as a supporting first-page improvement.
- [x] Reset Hanzi focus on T9 input/filter context changes so the focus bubble does not briefly jump to a stale candidate index.
- [x] Show local punctuation candidates for Chinese `1` so the candidate window remains visible without reintroducing `1Password`.
- [x] Keep local punctuation candidates pending instead of auto-committing the first symbol.
- [x] Prioritize pending punctuation before Chinese composition checks for repeated `1` and `*`.
- [x] Move local punctuation preview from editor composing text to the input method preedit row.
- [x] Let DPAD arrows/OK control local punctuation candidates before generic pending-punctuation commit.
- [x] Expand local punctuation pools and paginate them with the T9 candidate budget.
- [x] Consume punctuation candidate navigation keys at page boundaries.
- [x] Update README with user-visible T9 behavior changes.
- [x] Make the top pinyin preview follow the highlighted Hanzi candidate reading.
- [x] Truncate candidate-based pinyin preview to the current T9 key count.

## Previous Completed Work

- [x] Removed the abandoned GitHub remote APK build workflow.
- [x] Changed the T9 Hanzi candidate budget default from 12 to 10.
- [x] Kept the Rime plugin on inherited shared versioning with no local override.
