package io.github.effectnebula.eide.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.MoveTo

/**
 * Ввод с аппаратной клавиатуры.
 *
 * На Android это не экзотика: телефон или планшет с bluetooth-клавиатурой —
 * заявленный сценарий, и на нём редактор обязан работать полностью.
 *
 * Экранная клавиатура сюда не приходит: она общается через `InputConnection` —
 * это `imeInput`. Исключение — клавиши, которые она шлёт как настоящие нажатия
 * (стрелки, у части клавиатур Backspace): их `BaseInputConnection.sendKeyEvent`
 * пересылает во View, и они попадают именно сюда.
 */
internal fun Modifier.editorKeyInput(state: EditorState): Modifier =
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        handleKey(state, event)
    }

/**
 * Отступ — четыре пробела, а не табуляция.
 *
 * Причина не в религии, а в Python: смешение табов и пробелов там ошибка
 * времени выполнения, а не вопрос вкуса. Настройкой это станет тогда, когда
 * появятся настройки.
 */
internal const val INDENT: String = "    "

private fun handleKey(state: EditorState, event: KeyEvent): Boolean {
    val shift = event.isShiftPressed
    // Meta — это Cmd на macOS; там сочетания те же, но с другой клавишей.
    val command = event.isCtrlPressed || event.isMetaPressed

    when (event.key) {
        Key.DirectionLeft -> {
            state.move(if (command) MoveTo.WordLeft else MoveTo.Left, shift)
            return true
        }
        Key.DirectionRight -> {
            state.move(if (command) MoveTo.WordRight else MoveTo.Right, shift)
            return true
        }
        Key.DirectionUp -> {
            // Ctrl+Alt+Up — клонировать курсор вверх, как в IntelliJ.
            if (command && event.isAltPressed) state.addCaretAbove() else state.move(MoveTo.Up, shift)
            return true
        }
        Key.DirectionDown -> {
            if (command && event.isAltPressed) state.addCaretBelow() else state.move(MoveTo.Down, shift)
            return true
        }
        Key.MoveHome -> {
            state.move(if (command) MoveTo.DocumentStart else MoveTo.LineStart, shift)
            return true
        }
        Key.MoveEnd -> {
            state.move(if (command) MoveTo.DocumentEnd else MoveTo.LineEnd, shift)
            return true
        }
        Key.Backspace -> {
            state.deleteBackward()
            return true
        }
        Key.Delete -> {
            state.deleteForward()
            return true
        }
        Key.Enter, Key.NumPadEnter -> {
            state.insertNewline()
            return true
        }
        Key.Tab -> {
            // Табуляция уходит в текст, а не переводит фокус: это редактор кода.
            state.type(INDENT)
            return true
        }
        Key.A -> if (command) {
            state.selectAll()
            return true
        }
        Key.Z -> if (command) {
            if (shift) state.redo() else state.undo()
            return true
        }
        Key.Y -> if (command) {
            state.redo()
            return true
        }
        else -> Unit
    }

    // Печатаемый символ. Сочетания с Ctrl отсекаем: иначе Ctrl+S вставил бы
    // в текст букву s.
    if (command) return false
    val codePoint = event.utf16CodePoint
    if (codePoint == 0 || isControlCharacter(codePoint)) return false

    state.type(codePointToString(codePoint))
    return true
}

/**
 * Управляющие символы в текст не попадают.
 *
 * Enter и Tab обработаны выше отдельно, а всё остальное ниже пробела — это
 * служебные коды, которым в документе делать нечего.
 */
private fun isControlCharacter(codePoint: Int): Boolean = codePoint < 0x20 || codePoint == 0x7F

/**
 * Код символа в строку, без `Character.toChars`.
 *
 * Это общий код для Android и десктопа, а `java.lang.Character` в общем коде
 * недоступен — даже при том, что обе платформы в итоге работают на JVM.
 */
private fun codePointToString(codePoint: Int): String =
    if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        val value = codePoint - 0x10000
        charArrayOf(
            ((value shr 10) + 0xD800).toChar(),
            ((value and 0x3FF) + 0xDC00).toChar(),
        ).concatToString()
    }
