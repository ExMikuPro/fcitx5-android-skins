/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag

enum class ReturnKeyAction { Enter, Go, Search, Send, Next, Done, Previous }

class ReturnKeyDrawableComponent :
    UniqueComponent<ReturnKeyDrawableComponent>(), Dependent, ManagedHandler by managedHandler() {

    companion object {
        @DrawableRes
        val DEFAULT_DRAWABLE = R.drawable.ic_baseline_keyboard_return_24
    }

    private val broadcaster: InputBroadcaster by manager.must()

    @DrawableRes
    var resourceId: Int = DEFAULT_DRAWABLE
        private set

    var action: ReturnKeyAction = ReturnKeyAction.Enter
        private set

    @DrawableRes
    private var actionDrawable: Int = DEFAULT_DRAWABLE
    private var editorAction: ReturnKeyAction = ReturnKeyAction.Enter

    private fun actionFromEditorInfo(info: EditorInfo): ReturnKeyAction {
        if (info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            return ReturnKeyAction.Enter
        }
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> ReturnKeyAction.Go
            EditorInfo.IME_ACTION_SEARCH -> ReturnKeyAction.Search
            EditorInfo.IME_ACTION_SEND -> ReturnKeyAction.Send
            EditorInfo.IME_ACTION_NEXT -> ReturnKeyAction.Next
            EditorInfo.IME_ACTION_DONE -> ReturnKeyAction.Done
            EditorInfo.IME_ACTION_PREVIOUS -> ReturnKeyAction.Previous
            else -> ReturnKeyAction.Enter
        }
    }

    @DrawableRes
    private fun drawableFromEditorInfo(info: EditorInfo): Int {
        if (info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            return R.drawable.ic_baseline_keyboard_return_24
        }
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> R.drawable.ic_baseline_arrow_forward_24
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_baseline_search_24
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_baseline_send_24
            EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_baseline_keyboard_tab_24
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_baseline_done_24
            EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_baseline_keyboard_tab_reverse_24
            else -> R.drawable.ic_baseline_keyboard_return_24
        }
    }

    fun updateDrawableOnEditorInfo(info: EditorInfo) {
        actionDrawable = drawableFromEditorInfo(info)
        editorAction = actionFromEditorInfo(info)
        update(actionDrawable, editorAction)
    }

    fun updateDrawableOnPreedit(preeditEmpty: Boolean) {
        val newResId = if (preeditEmpty) actionDrawable else DEFAULT_DRAWABLE
        val newAction = if (preeditEmpty) editorAction else ReturnKeyAction.Enter
        update(newResId, newAction)
    }

    private fun update(newResId: Int, newAction: ReturnKeyAction) {
        if (resourceId == newResId && action == newAction) return
        resourceId = newResId
        action = newAction
        broadcaster.onReturnKeyDrawableUpdate(resourceId)
        broadcaster.onReturnKeyActionUpdate(action)
    }
}
