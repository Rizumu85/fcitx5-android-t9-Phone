# T9 键盘高度逻辑修复计划

## 现象

- 开启 T9 键盘模式，T9 高度设为 10/15。
- **首次使用键盘**：显示的是默认键盘高度 40/45（错误，应为 10/15）。
- **点符号/表情/剪贴板/设置等再回到键盘**：显示正确的 T9 高度 10/15。
- **退出输入法再重新进入**：又变回 40/45（错误）。

## 期望逻辑

- **T9 模式开启时**：默认显示键盘就应是 T9 高度（10/15）。
- **切到其他页面**（符号、表情、剪贴板、设置等）：使用“默认键盘”高度（40/45）。
- **从其他页面回到键盘**：恢复 T9 高度（10/15）。

---

## 根因分析

### 1. 首次显示为 40/45

当前 `InputView` init 顺序（约 207–289 行）：

1. `addEssentialWindow(keyboardWindow, createView = true)` → 创建 KeyboardWindow 的 view，`attachLayout(T9)`，`currentKeyboardName = "T9"`。
2. **`attachWindow(KeyboardWindow)`** → 此时尚未设置 `onLayoutChanged` / `onActiveWindowChanged`，因此 `onAttached()` 里的回调不会更新 `InputView` 的 `isT9KeyboardActive`。
3. **`isT9KeyboardActive = useT9KeyboardLayout`**（true）。
4. 构建 `keyboardView`（内含 `windowManager.view`）。
5. **`updateKeyboardSize()`**（此处应使用 T9 高度）。
6. 设置 `keyboardWindow.onLayoutChanged` 和 `windowManager.onActiveWindowChanged`。
7. `add(preedit)`, `add(keyboardView)`, `add(popup)`。

可能原因：

- 第一次 `updateKeyboardSize()` 在 **`add(keyboardView)` 之前**执行，此时 `windowManager.view` 可能尚未处于最终布局树中，或 layout 尚未完成，导致高度未按 T9 生效，视觉上像 40/45。
- 因此需要把 **唯一一次决定高度的 `updateKeyboardSize()` 挪到所有 `add(...)` 之后**，并在此之前保证 `isT9KeyboardActive` 已正确。

### 2. 退出输入法再进入变回 40/45

- `onDetachedFromWindow()` 中把 `keyboardWindow.onLayoutChanged` 和 `windowManager.onActiveWindowChanged` 置为 `null`，**回调被清空**。
- 若用户在退出前曾打开过符号/表情等，`onActiveWindowChanged` 会把 `isT9KeyboardActive` 设为 `false`，且之后没有机会再设回 `true`。
- 再次进入输入法时，若 **InputView 被复用**（未重新执行 init），只会走 `startInput()` → `attachWindow(KeyboardWindow)`，**不会**再设置回 `isT9KeyboardActive = true`，也不会再调 `updateKeyboardSize()`，因此高度保持 40/45。

结论：需要在 **每次** 把当前窗口切回 KeyboardWindow 时（无论是 init 里首次 attach，还是 `startInput()` 里 re-attach），都根据“当前是否为 T9 布局”把 `isT9KeyboardActive` 设对并刷新高度。

---

## 修复方案

### 修改文件

- **`app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt`**

### 调整 1：在 `attachWindow` 之前设置 T9 状态与回调

把下面三件事移到 **`windowManager.attachWindow(KeyboardWindow)` 之前**（约 211 行附近）：

- `isT9KeyboardActive = useT9KeyboardLayout`
- `keyboardWindow.onLayoutChanged = { ... }`
- `windowManager.onActiveWindowChanged = { ... }`（见调整 2，需包含“回到键盘”分支）

这样在 `attachWindow` 触发 `onAttached()` → `onLayoutChanged` 时，回调已就绪，状态一致。

### 调整 2：`onActiveWindowChanged` 同时处理“离开键盘”和“回到键盘”

当前只处理了离开键盘（设为默认高度），没有处理“回到键盘”时恢复 T9 高度。应改为：

```kotlin
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
```

### 调整 3：`updateKeyboardSize()` 只在所有 `add(...)` 之后调用一次

- **删除**：当前在构建 `keyboardView` 的 constraintLayout 块之后、在 `add(preedit...)` 之前的那次 `updateKeyboardSize()`（约 257 行）。
- **保留**：在所有 `add(preedit.ui.root, ...)`、`add(keyboardView, ...)`、`add(popup.root, ...)` 之后，再调用 **一次** `updateKeyboardSize()`（在 `keyboardPrefs.registerOnChangeListener(...)` 之前）。

这样保证在应用高度时，`windowManager.view` 已在布局树中且 `layoutParams` 有效，避免 NPE 或高度不生效。

### 调整 4：`startInput()` 中 attach 键盘时同步 T9 状态并刷新高度

在 `startInput()` 里，当执行 `windowManager.attachWindow(KeyboardWindow)` 时，若当前是 T9 模式且当前布局是 T9，应把 `isT9KeyboardActive` 置为 `true` 并刷新高度，以覆盖“退出输入法再进入、InputView 被复用、回调已清空”的情况：

```kotlin
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
```

### 调整 5（可选）：`updateKeyboardSize()` 的防护

若在 `attachWindow` 过程中回调会提前触发 `updateKeyboardSize()`，而此时 `windowManager.view.layoutParams` 仍为 null，可在 `updateKeyboardSize()` 开头增加：

```kotlin
if (windowManager.view.layoutParams == null) return
```

避免 NPE，真正生效的高度由“所有 add 之后”的那次调用保证。

---

## 最终 init 中的顺序（摘要）

```text
1. addEssentialWindow(keyboardWindow, ...) / symbolPicker / emojiPicker / emoticonPicker

2. 初始化 T9 状态并设置回调（在 attachWindow 之前）
   - isT9KeyboardActive = useT9KeyboardLayout
   - keyboardWindow.onLayoutChanged = { layoutName -> ... }
   - windowManager.onActiveWindowChanged = { oldWindow, newWindow -> ... }  // 含“回到键盘”分支

3. windowManager.attachWindow(KeyboardWindow)  // 会触发 onAttached → onLayoutChanged

4. broadcaster.onImeUpdate(...)
5. customBackground / keyboardView = constraintLayout { ... }  // 不再在此后调用 updateKeyboardSize()

6. add(preedit.ui.root, ...)
7. add(keyboardView, ...)
8. add(popup.root, ...)

9. updateKeyboardSize()  // 仅此一处，此时 layoutParams 已存在

10. keyboardPrefs.registerOnChangeListener(...)
```

---

## 执行检查清单

- [ ] 将 `isT9KeyboardActive` 与两个回调移到 `attachWindow(KeyboardWindow)` 之前。
- [ ] `onActiveWindowChanged` 增加 `newWindow === keyboardWindow && useT9KeyboardLayout && keyboardWindow.isCurrentLayoutT9()` 时恢复 `isT9KeyboardActive = true` 并 `updateKeyboardSize()`。
- [ ] 删除 constraintLayout 块后、add 前的那次 `updateKeyboardSize()`。
- [ ] 在所有 `add(...)` 之后、`registerOnChangeListener` 之前保留一次 `updateKeyboardSize()`。
- [ ] 在 `startInput()` 中，`attachWindow(KeyboardWindow)` 后若 `useT9KeyboardLayout && keyboardWindow.isCurrentLayoutT9() && !isT9KeyboardActive`，则设 `isT9KeyboardActive = true` 并 `updateKeyboardSize()`。
- [ ] （可选）在 `updateKeyboardSize()` 开头加 `if (windowManager.view.layoutParams == null) return`。
- [ ] 验证：T9 模式开启时，首次打开键盘即为 10/15；切到符号/表情等为 40/45；回到键盘为 10/15；退出输入法再进入仍为 10/15。
