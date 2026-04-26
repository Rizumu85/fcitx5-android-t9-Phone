/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

private class SelectionActionGuideView(
    context: Context,
    guideColor: Int
) : View(context) {

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = guideColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val padding = dp(14f)
        canvas.drawLine(padding, cy, width - padding, cy, paint)
        canvas.drawLine(cx, padding, cx, height - padding, paint)
        canvas.drawCircle(cx, cy, (minOf(width, height) / 2f) - padding, paint)
    }
}

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (service.handlePreImeKeyEvent(event)) return true
        return super.dispatchKeyEventPreIme(event)
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }
    private val modeSwitchIndicatorHandler = Handler(Looper.getMainLooper())
    private val modeSwitchIndicatorHideRunnable = Runnable {
        hideModeSwitchIndicator()
    }
    private fun selectionActionHint(
        label: String,
        circular: Boolean = false,
        typefaceStyle: Int = Typeface.BOLD
    ) = view(::AutoScaleTextView) {
        alpha = 0f
        visibility = GONE
        isClickable = false
        isFocusable = false
        gravity = Gravity.CENTER
        minimumWidth = if (circular) dp(64) else dp(52)
        minimumHeight = if (circular) dp(64) else dp(26)
        setPadding(if (circular) 0 else dp(8), 0, if (circular) 0 else dp(8), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (circular) 24f else 18f)
        InputUiFont.applyTo(this, typefaceStyle)
        text = label
        setTextColor(theme.accentKeyTextColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (circular) dp(32f) else dp(3f)
            setColor(theme.accentKeyBackgroundColor)
        }
        elevation = dp(8).toFloat()
    }
    private fun selectionActionVerticalHint(first: String, second: String) = view(::LinearLayout) {
        alpha = 0f
        visibility = GONE
        isClickable = false
        isFocusable = false
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = dp(30)
        minimumHeight = dp(58)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3f)
            setColor(theme.accentKeyBackgroundColor)
        }
        elevation = dp(8).toFloat()
        listOf(first, second).forEach { label ->
            addView(
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
                    setTextColor(theme.accentKeyTextColor)
                    InputUiFont.applyTo(this, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }
    private val selectionActionAnchor = view(::View) {
        isClickable = false
        isFocusable = false
        visibility = INVISIBLE
    }
    private val selectionActionGuide = SelectionActionGuideView(
        context,
        (theme.accentKeyBackgroundColor and 0x00ffffff) or 0x66000000
    ).apply {
        alpha = 0f
        visibility = GONE
        isClickable = false
        isFocusable = false
    }
    private val selectionActionHintUp = selectionActionHint("复制")
    private val selectionActionHintLeft = selectionActionVerticalHint("剪", "切")
    private val selectionActionHintCenter = selectionActionHint(
        "确认",
        circular = true,
        typefaceStyle = Typeface.NORMAL
    )
    private val selectionActionHintRight = selectionActionVerticalHint("粘", "贴")
    private val selectionActionHintDown = selectionActionHint("删除")
    private val selectionActionHints
        get() = listOf(
            selectionActionGuide,
            selectionActionHintUp,
            selectionActionHintLeft,
            selectionActionHintCenter,
            selectionActionHintRight,
            selectionActionHintDown
        )
    private fun numberOperatorHintCell(primary: String, secondary: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = dp(50)
        minimumHeight = dp(42)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3f)
            setColor(theme.accentKeyBackgroundColor)
        }
        addView(
            TextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                text = primary
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                setTextColor(theme.accentKeyTextColor)
                InputUiFont.applyTo(this, Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            TextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                text = secondary
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f)
                setTextColor(theme.accentKeyTextColor)
                InputUiFont.applyTo(this, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }
    private fun numberOperatorHintRow(vararg cells: Pair<String, String>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        cells.forEachIndexed { index, cell ->
            addView(
                numberOperatorHintCell(cell.first, cell.second),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) marginStart = dp(8)
                }
            )
        }
    }
    private val numberOperatorHintPanel = LinearLayout(context).apply {
        alpha = 0f
        visibility = GONE
        isClickable = false
        isFocusable = false
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        listOf(
            arrayOf("1" to "-", "2" to "+", "3" to "="),
            arrayOf("4" to "π", "5" to "/", "6" to "≈"),
            arrayOf("7" to "(", "8" to "%", "9" to ")"),
            arrayOf("*" to "*", "0" to ".", "#" to "返回")
        ).forEachIndexed { index, row ->
            addView(
                numberOperatorHintRow(*row),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp(8)
                }
            )
        }
    }
    private fun equalsChoiceCell(primary: String, secondary: String) =
        numberOperatorHintCell(primary, secondary).apply {
            minimumWidth = dp(76)
            minimumHeight = dp(48)
        }
    private val numberEqualsChoicePanel = LinearLayout(context).apply {
        alpha = 0f
        visibility = GONE
        isClickable = false
        isFocusable = false
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val modeSwitchIndicator = view(::AutoScaleTextView) {
        alpha = 0f
        visibility = GONE
        isClickable = false
        isFocusable = false
        gravity = Gravity.CENTER
        minimumWidth = dp(52)
        minimumHeight = dp(26)
        setPadding(dp(8), 0, dp(8), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        InputUiFont.applyTo(this, Typeface.BOLD)
        setTextColor(theme.accentKeyTextColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3f)
            setColor(theme.accentKeyBackgroundColor)
        }
        elevation = dp(8).toFloat()
    }

    private val scope = DynamicScope()
    private val themedContext = context.withTheme(R.style.Theme_InputViewTheme)
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val t9KeyboardHeightPercent = keyboardPrefs.t9KeyboardHeightPercent
    private val t9KeyboardHeightPercentLandscape = keyboardPrefs.t9KeyboardHeightPercentLandscape
    private val useT9KeyboardLayout by keyboardPrefs.useT9KeyboardLayout
    private var isT9KeyboardActive = false
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        t9KeyboardHeightPercent,
        t9KeyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE ->
                    if (isT9KeyboardActive) t9KeyboardHeightPercentLandscape
                    else keyboardHeightPercentLandscape
                else ->
                    if (isT9KeyboardActive) t9KeyboardHeightPercent
                    else keyboardHeightPercent
            }.getValue()
            return resources.displayMetrics.heightPixels * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // Sync T9 mode to space bar when user switches via # long press or language key
        service.onT9ModeChanged = { broadcaster.onT9ModeUpdate(it) }

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)

        // 1. Initialize T9 state and set callbacks before attachWindow so onAttached → onLayoutChanged is ready
        isT9KeyboardActive = useT9KeyboardLayout
        keyboardWindow.onLayoutChanged = { layoutName ->
            val shouldBeT9 = useT9KeyboardLayout && layoutName == T9Keyboard.Name
            if (isT9KeyboardActive != shouldBeT9) {
                isT9KeyboardActive = shouldBeT9
                updateKeyboardSize()
            }
        }
        windowManager.onActiveWindowChanged = { oldWindow, newWindow ->
            if (oldWindow === keyboardWindow && isT9KeyboardActive) {
                // 离开键盘窗口 → 用默认高度 40/45
                isT9KeyboardActive = false
                updateKeyboardSize()
            } else if (newWindow === keyboardWindow && useT9KeyboardLayout && keyboardWindow.isCurrentLayoutT9()) {
                // 回到键盘窗口 → 恢复 T9 高度 10/15
                if (!isT9KeyboardActive) {
                    isT9KeyboardActive = true
                    updateKeyboardSize()
                }
            }
        }
        // 2. attach window (triggers onAttached → onLayoutChanged)
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        // 3. Add views to layout
        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })
        add(modeSwitchIndicator, lParams(wrapContent, wrapContent) {
            centerVertically()
            centerHorizontally()
        })
        add(selectionActionAnchor, lParams(dp(1), dp(1)) {
            centerVertically()
            centerHorizontally()
        })
        add(selectionActionGuide, lParams(dp(150), dp(150)) {
            centerVertically()
            centerHorizontally()
        })
        add(selectionActionHintUp, lParams(wrapContent, wrapContent) {
            above(selectionActionAnchor)
            centerHorizontally()
            bottomMargin = dp(46)
        })
        add(selectionActionHintLeft, lParams(wrapContent, wrapContent) {
            endToStartOf(selectionActionHintCenter)
            centerVertically()
            marginEnd = dp(14)
        })
        add(selectionActionHintCenter, lParams(dp(64), dp(64)) {
            centerVertically()
            centerHorizontally()
        })
        add(selectionActionHintRight, lParams(wrapContent, wrapContent) {
            startToEndOf(selectionActionHintCenter)
            centerVertically()
            marginStart = dp(14)
        })
        add(selectionActionHintDown, lParams(wrapContent, wrapContent) {
            below(selectionActionAnchor)
            centerHorizontally()
            topMargin = dp(46)
        })
        add(numberOperatorHintPanel, lParams(wrapContent, wrapContent) {
            centerVertically()
            centerHorizontally()
        })
        add(numberEqualsChoicePanel, lParams(wrapContent, wrapContent) {
            centerVertically()
            centerHorizontally()
        })

        // 4. updateKeyboardSize() after all add() so layoutParams exist (avoids NPE / wrong height)
        updateKeyboardSize()

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        if (windowManager.view.layoutParams == null) return
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
            // 重新进入时恢复 T9 高度（回调可能在 onDetachedFromWindow 被清空）
            if (useT9KeyboardLayout && keyboardWindow.isCurrentLayoutT9() && !isT9KeyboardActive) {
                isT9KeyboardActive = true
                updateKeyboardSize()
            }
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    fun clearTransientState() {
        kawaiiBar.clearTransientState()
    }

    fun showModeIndicatorBadge(label: String) {
        modeSwitchIndicatorHandler.removeCallbacks(modeSwitchIndicatorHideRunnable)
        modeSwitchIndicator.animate().cancel()
        modeSwitchIndicator.text = label
        modeSwitchIndicator.visibility = VISIBLE
        modeSwitchIndicator.alpha = 0f
        modeSwitchIndicator.scaleX = 0.85f
        modeSwitchIndicator.scaleY = 0.85f
        modeSwitchIndicator.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(80L)
            .withEndAction {
                modeSwitchIndicatorHandler.postDelayed(modeSwitchIndicatorHideRunnable, 420L)
            }
            .start()
    }

    fun showSelectionActionHints() {
        selectionActionHints.forEach { hint ->
            hint.animate().cancel()
            hint.visibility = VISIBLE
            hint.alpha = 0f
            hint.scaleX = 0.85f
            hint.scaleY = 0.85f
            hint.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(80L)
                .start()
        }
    }

    fun hideSelectionActionHints() {
        selectionActionHints.forEach { hint ->
            hint.animate().cancel()
            hint.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(120L)
                .withEndAction {
                    hint.visibility = GONE
                }
                .start()
        }
    }

    private fun showTransientPanel(panel: View) {
        panel.animate().cancel()
        panel.visibility = VISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.9f
        panel.scaleY = 0.9f
        panel.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(80L)
            .start()
    }

    private fun hideTransientPanel(panel: View) {
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(120L)
            .withEndAction {
                panel.visibility = GONE
            }
            .start()
    }

    fun showNumberOperatorHints() {
        showTransientPanel(numberOperatorHintPanel)
    }

    fun hideNumberOperatorHints() {
        hideTransientPanel(numberOperatorHintPanel)
    }

    fun showNumberEqualsChoice(prefix: String, result: String) {
        numberEqualsChoicePanel.removeAllViews()
        numberEqualsChoicePanel.addView(
            equalsChoiceCell("确认", "$prefix$result"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        showTransientPanel(numberEqualsChoicePanel)
    }

    fun hideNumberEqualsChoice() {
        hideTransientPanel(numberEqualsChoicePanel)
    }

    private fun hideModeSwitchIndicator() {
        modeSwitchIndicator.animate().cancel()
        modeSwitchIndicator.animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(120L)
            .withEndAction {
                modeSwitchIndicator.visibility = GONE
            }
            .start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        modeSwitchIndicatorHandler.removeCallbacks(modeSwitchIndicatorHideRunnable)
        modeSwitchIndicator.animate().cancel()
        keyboardWindow.onLayoutChanged = null
        windowManager.onActiveWindowChanged = null
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        scope.clear()
        super.onDetachedFromWindow()
    }

}
