/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.resources.styledDimenPxSize
import splitties.resources.styledDrawable
import splitties.views.textAppearance

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val context = screen.context
        val previewPreference = Preference(context).apply {
            key = "key_sound_preview"
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(R.string.key_sound_preview)
            setSummary(R.string.key_sound_preview_summary)
            setOnPreferenceClickListener {
                showKeySoundPreviewDialog()
                true
            }
        }
        screen.addAfter(keyboardPrefs.keySoundStyle.key, previewPreference)
    }

    private fun showKeySoundPreviewDialog() {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(8), 0, context.dp(4))
            addView(
                context.previewRow(
                    R.string.key_sound_preview_standard,
                    R.drawable.ic_baseline_keyboard_24,
                    InputFeedbacks.SoundEffect.Standard
                )
            )
            addView(
                context.previewRow(
                    R.string.key_sound_preview_space,
                    R.drawable.ic_baseline_space_bar_24,
                    InputFeedbacks.SoundEffect.SpaceBar
                )
            )
            addView(
                context.previewRow(
                    R.string.key_sound_preview_delete,
                    R.drawable.ic_baseline_backspace_24,
                    InputFeedbacks.SoundEffect.Delete
                )
            )
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.key_sound_preview)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun Context.previewRow(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        effect: InputFeedbacks.SoundEffect
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = styledDrawable(android.R.attr.selectableItemBackground)
        minimumHeight = styledDimenPxSize(android.R.attr.listPreferredItemHeightSmall)
        setPaddingRelative(dp(24), dp(10), dp(24), dp(10))
        setOnClickListener {
            InputFeedbacks.previewSoundEffect(effect)
        }

        addView(
            ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, icon))
                imageTintList = ColorStateList.valueOf(styledColor(android.R.attr.colorControlNormal))
            },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                rightMargin = dp(24)
            }
        )
        addView(
            TextView(context).apply {
                textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
                setTextColor(styledColor(android.R.attr.textColorPrimary))
                setText(title)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun PreferenceScreen.addAfter(afterKey: String, preference: Preference) {
        val afterIndex = (0 until preferenceCount).firstOrNull {
            getPreference(it).key == afterKey
        } ?: run {
            addPreference(preference)
            return
        }

        val afterOrder = getPreference(afterIndex).order
        if (afterOrder != Int.MAX_VALUE) {
            preference.order = afterOrder + 1
            for (i in 0 until preferenceCount) {
                val existing = getPreference(i)
                if (existing.order > afterOrder) {
                    existing.order = existing.order + 1
                }
            }
        }
        addPreference(preference)
    }
}
