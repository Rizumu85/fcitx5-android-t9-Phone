/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.audioManager
import org.fcitx.fcitx5.android.utils.getSystemSettings
import org.fcitx.fcitx5.android.utils.vibrator

object InputFeedbacks {

    enum class InputFeedbackMode(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowingSystem(R.string.following_system_settings),
        Enabled(R.string.enabled),
        Disabled(R.string.disabled);
    }

    enum class KeySoundStyle(override val stringRes: Int) : ManagedPreferenceEnum {
        Muffled(R.string.key_sound_style_muffled),
        Mechanical(R.string.key_sound_style_mechanical),
        Crisp(R.string.key_sound_style_crisp);
    }

    private var systemSoundEffects = false
    private var systemHapticFeedback = false

    fun syncSystemPrefs() {
        systemSoundEffects = getSystemSettings<Int>(Settings.System.SOUND_EFFECTS_ENABLED) == 1
        preloadAppSounds()
        // it says "Replaced by using android.os.VibrationAttributes.USAGE_TOUCH"
        // but gives no clue about how to use it, and this one still works
        @Suppress("DEPRECATION")
        systemHapticFeedback = getSystemSettings<Int>(Settings.System.HAPTIC_FEEDBACK_ENABLED) == 1
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val soundOnKeyPress by keyboardPrefs.soundOnKeyPress
    private val soundOnKeyPressVolume by keyboardPrefs.soundOnKeyPressVolume
    private val keySoundStyle by keyboardPrefs.keySoundStyle
    private val hapticOnKeyPress by keyboardPrefs.hapticOnKeyPress
    private val hapticOnKeyUp by keyboardPrefs.hapticOnKeyUp
    private val buttonPressVibrationMilliseconds by keyboardPrefs.buttonPressVibrationMilliseconds
    private val buttonLongPressVibrationMilliseconds by keyboardPrefs.buttonLongPressVibrationMilliseconds
    private val buttonPressVibrationAmplitude by keyboardPrefs.buttonPressVibrationAmplitude
    private val buttonLongPressVibrationAmplitude by keyboardPrefs.buttonLongPressVibrationAmplitude

    private val vibrator = appContext.vibrator

    private val hasAmplitudeControl =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) && vibrator.hasAmplitudeControl()

    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false) {
        when (hapticOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemHapticFeedback) return
        }
        if (keyUp && !hapticOnKeyUp) return
        val duration: Long
        val amplitude: Int
        val hfc: Int
        if (longPress) {
            duration = buttonLongPressVibrationMilliseconds.toLong()
            amplitude = buttonLongPressVibrationAmplitude
            hfc = HapticFeedbackConstants.LONG_PRESS
        } else {
            duration = buttonPressVibrationMilliseconds.toLong()
            amplitude = buttonPressVibrationAmplitude
            hfc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && keyUp) {
                HapticFeedbackConstants.KEYBOARD_RELEASE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }

        // there is `VibrationEffect.DEFAULT_AMPLITUDE` but no default duration;
        // also `VibrationEffect.createOneShot()` only accepts positive duration.
        // so changing amplitude without changing duration makes no sense
        if (duration != 0L) {
            // on Android 13, if system haptic feedback was disabled, `vibrator.vibrate()` won't work
            // but `view.performHapticFeedback()` with `FLAG_IGNORE_GLOBAL_SETTING` still works
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ve = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(ve)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (hapticOnKeyPress == InputFeedbackMode.Enabled) {
                // it says "Starting TIRAMISU only privileged apps can ignore user settings for touch feedback"
                // but we still seem to be able to use `FLAG_IGNORE_GLOBAL_SETTING`
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            view.performHapticFeedback(hfc, flags)
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    private val audioManager = appContext.audioManager

    private data class AppSoundKey(
        val style: KeySoundStyle,
        val effect: SoundEffect
    )

    private val appSoundLock = Any()
    private val appSoundIds = mutableMapOf<AppSoundKey, Int>()
    private val loadedAppSoundIds = mutableSetOf<Int>()
    private val pendingAppSoundGains = mutableMapOf<Int, Float>()

    private fun sampleSoundEffect(effect: SoundEffect): SoundEffect =
        if (effect == SoundEffect.Return) SoundEffect.Delete else effect

    private val appSoundPool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { soundPool, sampleId, status ->
                    val gain = synchronized(appSoundLock) {
                        if (status == 0) {
                            loadedAppSoundIds.add(sampleId)
                            pendingAppSoundGains.remove(sampleId)
                        } else {
                            null
                        }
                    }
                    if (gain != null) {
                        soundPool.play(sampleId, gain, gain, 1, 0, 1f)
                    }
                }
            }
    }

    private fun appSoundResId(style: KeySoundStyle, effect: SoundEffect): Int {
        return when (style) {
            KeySoundStyle.Muffled -> when (effect) {
                SoundEffect.Standard -> R.raw.key_muffled_standard
                SoundEffect.SpaceBar -> R.raw.key_muffled_space
                SoundEffect.Delete, SoundEffect.Return -> R.raw.key_muffled_delete
            }
            KeySoundStyle.Mechanical -> when (effect) {
                SoundEffect.Standard -> R.raw.key_mechanical_standard
                SoundEffect.SpaceBar -> R.raw.key_mechanical_space
                SoundEffect.Delete, SoundEffect.Return -> R.raw.key_mechanical_delete
            }
            KeySoundStyle.Crisp -> when (effect) {
                SoundEffect.Standard -> R.raw.key_crisp_standard
                SoundEffect.SpaceBar -> R.raw.key_crisp_space
                SoundEffect.Delete, SoundEffect.Return -> R.raw.key_crisp_delete
            }
        }
    }

    private fun appSoundId(key: AppSoundKey): Int {
        return appSoundIds.getOrPut(key) {
            appSoundPool.load(appContext, appSoundResId(key.style, key.effect), 1)
        }
    }

    private fun preloadAppSounds() {
        synchronized(appSoundLock) {
            KeySoundStyle.entries.forEach { style ->
                appSoundId(AppSoundKey(style, SoundEffect.Standard))
                appSoundId(AppSoundKey(style, SoundEffect.SpaceBar))
                appSoundId(AppSoundKey(style, SoundEffect.Delete))
            }
        }
    }

    private fun playAppSoundEffect(effect: SoundEffect, volume: Int) {
        val gain = (volume.coerceIn(0, 100) / 100f).takeIf { it > 0f } ?: 0.5f
        val soundId = synchronized(appSoundLock) {
            appSoundId(AppSoundKey(keySoundStyle, sampleSoundEffect(effect)))
        }
        if (soundId == 0) return
        val isLoaded = synchronized(appSoundLock) {
            if (soundId in loadedAppSoundIds) {
                true
            } else {
                pendingAppSoundGains[soundId] = gain
                false
            }
        }
        if (isLoaded) {
            appSoundPool.play(soundId, gain, gain, 1, 0, 1f)
        }
    }

    fun soundEffect(effect: SoundEffect) {
        when (soundOnKeyPress) {
            InputFeedbackMode.Enabled -> {
                val volume = soundOnKeyPressVolume.takeIf { it > 0 } ?: 50
                playAppSoundEffect(effect, volume)
                return
            }
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemSoundEffects) return
        }
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        val volume = soundOnKeyPressVolume
        if (volume == 0) {
            audioManager.playSoundEffect(fx, -1f)
        } else {
            audioManager.playSoundEffect(fx, volume / 100f)
        }
    }

}
