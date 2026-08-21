/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

enum class BdsToolbarAction {
    Keyboard,
    InputMethod,
    Settings,
    Clipboard,
    Emoji,
    VoiceInput,
    Handwriting,
    TextEditing,
    MoreMenu,
    Undo,
    Redo,
    HideKeyboard,
    Theme,
    ReloadConfig
}

/**
 * Baidu's legacy `pop_menu_icons` ABI.
 *
 * The names were recovered from Baidu Input 8.6.6.7's `MenuFunction.bitmapIndex`
 * values. 240 dpi packages contain slots 0..32 while newer/higher-density packages
 * can contain slots 0..40, so callers must still check whether a skin supplies a slot.
 */
enum class BdsLegacyMenuIcon(val id: Int, val baiduFunction: String) {
    Fallback(0, "UNASSIGNED_FALLBACK"),
    Theme(1, "CLICK_INDEX_THEME"),
    InputType(2, "CLICK_INDEX_IM"),
    LexiconOptimization(3, "CLICK_INDEX_CK"),
    NightMode(4, "CLICK_INDEX_NIGHTMODE"),
    PanelResize(5, "CLICK_INDEX_ADJUSTHEIGHT"),
    Settings(6, "CLICK_INDEX_SETTING"),
    DayMode(7, "CLICK_INDEX_DAYMODE"),
    SingleHanded(8, "CLICK_INDEX_SINGLE"),
    DoubleHanded(9, "CLICK_INDEX_DOUBLE"),
    TraditionalChinese(10, "CLICK_INDEX_TRADITIONAL"),
    SimplifiedChinese(11, "CLICK_INDEX_SIMPLIFIED"),
    Sound(12, "CLICK_INDEX_SOUND"),
    Vibrate(13, "CLICK_INDEX_VIBRATE"),
    FontSize(14, "CLICK_INDEX_FONTSIZE"),
    KeyboardHandwriting(15, "CLICK_INDEX_KEYWRITE"),
    KeyboardHandwritingOff(16, "CLICK_INDEX_NO_KEYWRITE"),
    Tools(17, "CLICK_INDEX_TOOL"),
    HandwritingMode(18, "CLICK_INDEX_HANDWRITE_MODE"),
    Feedback(19, "CLICK_INDEX_FEEDBACK"),
    LandscapeNineKey(20, "CLICK_INDEX_PADMODE"),
    LandscapeNineKeyOff(21, "CLICK_INDEX_NON_PADMOD"),
    DeveloperMode(22, "CLICK_INDEX_DEVELOPER_MODE"),
    FloatingKeyboard(23, "CLICK_INDEX_FLOAT_MODE"),
    FloatingKeyboardOff(24, "CLICK_INDEX_NOT_FLOAT_MODE"),
    Editor(25, "CLICK_INDEX_EDITOR"),
    Emoji(26, "CLICK_INDEX_EMOJI"),
    InputManager(27, "CLICK_INDEX_INPUT_MANAGER"),
    LazyPhrases(28, "CLICK_INDEX_LAZY"),
    Voice(29, "CLICK_INDEX_VOICE"),
    Sync(30, "CLICK_INDEX_SYN"),
    Search(31, "CLICK_INDEX_SEARCH"),
    EmojiSecretOn(32, "CLICK_INDEX_EMOJI_INVER_OPEN"),
    EmojiSecretOff(33, "CLICK_INDEX_EMOJI_INVER_CLOSE"),
    SmartReplyOn(34, "CLICK_INDEX_SMART_REPLY_ON"),
    SmartReplyOff(35, "CLICK_INDEX_SMART_REPLY_OFF"),
    Language(36, "CLICK_INDEX_LANGUAGE"),
    Translate(37, "CLICK_INDEX_TRANSLATE"),
    Ocr(38, "CLICK_INDEX_OCR"),
    GameKeyboardOn(39, "CLICK_INDEX_GAME_KEYBOARD_OPEN"),
    GameKeyboardOff(40, "CLICK_INDEX_GAME_KEYBOARD_CLOSE");

    companion object {
        private val byId = entries.associateBy(BdsLegacyMenuIcon::id)
        fun fromId(id: Int): BdsLegacyMenuIcon? = byId[id]
    }
}

object BdsToolbarIconMapping {
    val menuIconIds: Map<BdsToolbarAction, Int> = mapOf(
        BdsToolbarAction.Keyboard to BdsLegacyMenuIcon.InputType.id,
        BdsToolbarAction.InputMethod to BdsLegacyMenuIcon.Language.id,
        BdsToolbarAction.Settings to BdsLegacyMenuIcon.Settings.id,
        BdsToolbarAction.Emoji to BdsLegacyMenuIcon.Emoji.id,
        BdsToolbarAction.VoiceInput to BdsLegacyMenuIcon.Voice.id,
        BdsToolbarAction.Handwriting to BdsLegacyMenuIcon.HandwritingMode.id,
        BdsToolbarAction.TextEditing to BdsLegacyMenuIcon.Editor.id,
        BdsToolbarAction.MoreMenu to BdsLegacyMenuIcon.Tools.id,
        BdsToolbarAction.Theme to BdsLegacyMenuIcon.Theme.id
    )

    /** No matching legacy atlas function exists; these always keep Fcitx defaults. */
    val pendingConfirmation: Map<BdsToolbarAction, List<Int>> = mapOf(
        // Clipboard was added later as bitmap index 50, outside pop_menu_icons 0..40.
        BdsToolbarAction.Clipboard to listOf(50),
        BdsToolbarAction.Undo to emptyList(),
        BdsToolbarAction.Redo to emptyList(),
        BdsToolbarAction.HideKeyboard to emptyList()
    )

    fun iconId(action: BdsToolbarAction): Int? = menuIconIds[action]

    fun actionsFor(iconId: Int): List<BdsToolbarAction> =
        menuIconIds.filterValues { it == iconId }.keys.toList()
}
