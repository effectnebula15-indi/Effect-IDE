package io.github.effectnebula.eide.ui.editor

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.establishTextInputSession
import io.github.effectnebula.eide.core.editor.EditorListener
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.InputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal actual fun Modifier.imeInput(state: EditorState): Modifier = this then ImeInputElement(state)

private data class ImeInputElement(val state: EditorState) : ModifierNodeElement<ImeInputNode>() {

    override fun create(): ImeInputNode = ImeInputNode(state)

    override fun update(node: ImeInputNode) {
        node.setState(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "imeInput"
    }
}

/**
 * Сессия ввода живёт ровно столько, сколько редактор в фокусе.
 *
 * Привязка к фокусу, а не к жизни узла, важна: клавиатура, оставшаяся
 * подключённой к невидимому редактору, продолжает править текст — а человек
 * в это время уже в другой панели.
 */
private class ImeInputNode(private var state: EditorState) :
    Modifier.Node(),
    PlatformTextInputModifierNode,
    FocusEventModifierNode {

    private var session: Job? = null
    private var focused = false

    fun setState(state: EditorState) {
        if (this.state === state) return
        this.state = state
        if (focused) restartSession()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        if (focused) restartSession() else stopSession()
    }

    override fun onDetach() {
        stopSession()
        focused = false
    }

    private fun restartSession() {
        stopSession()
        val target = state
        session = coroutineScope.launch {
            establishTextInputSession { runSession(target) }
        }
    }

    private fun stopSession() {
        session?.cancel()
        session = null
    }
}

/**
 * Разговор с клавиатурой: соединение туда, оповещения обратно.
 *
 * `startInputMethod` не возвращается никогда — сессия заканчивается только
 * отменой корутины. Поэтому отписка живёт в `finally`, а не после вызова.
 */
private suspend fun PlatformTextInputSession.runSession(state: EditorState): Nothing {
    val ime = InputSession(state)
    val manager = view.context.getSystemService(InputMethodManager::class.java)

    // Клавиатура обязана знать о правках, которых не делала: тап, аппаратная
    // клавиша, undo. Иначе её представление о позиции курсора расходится с
    // документом, и следующий backspace стирает не там.
    fun notifyKeyboard() {
        val selection = ime.selection
        val composing = ime.composing
        manager?.updateSelection(
            view,
            selection.start,
            selection.end,
            composing?.start ?: -1,
            composing?.end ?: -1,
        )
    }

    val listener = EditorListener { notifyKeyboard() }
    state.addListener(listener)

    try {
        startInputMethod { editorInfo ->
            editorInfo.fill(ime)
            EditorInputConnection(view, ime, onSelectionChanged = ::notifyKeyboard)
        }
    } finally {
        state.removeListener(listener)
    }
}

private fun EditorInfo.fill(ime: InputSession) {
    // NO_SUGGESTIONS: автозамена в коде — источник ошибок, а не помощи.
    // MULTI_LINE: без него клавиатура показывает Enter как «готово» и переноса
    // строки набрать нельзя.
    inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    // NO_FULLSCREEN: в ландшафте клавиатура иначе разворачивается на весь экран
    // со своим полем ввода, и редактора просто не видно.
    // NO_PERSONALIZED_LEARNING: чужой код не должен попадать в словарь клавиатуры.
    imeOptions = EditorInfo.IME_ACTION_NONE or
        EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    val selection = ime.selection
    initialSelStart = selection.start
    initialSelEnd = selection.end
}
