package io.github.effectnebula.eide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import io.github.effectnebula.eide.core.editor.EditorListener
import io.github.effectnebula.eide.core.editor.EditorState

/**
 * Ревизия редактора как наблюдаемое значение Compose.
 *
 * `EditorState` живёт в `:core` и про Compose не знает: там обычные поля, которые
 * снапшот-система не отслеживает. Без этого моста экран перерисовывался бы только
 * по совпадению — у нас это делало мигание курсора, и набранный символ появлялся
 * с задержкой до полусекунды.
 *
 * Возвращается само состояние, а не число: читать его надо там, где нужен
 * результат. Чтение в фазе отрисовки инвалидирует только отрисовку, чтение в
 * composable — пересобирает его. Разница принципиальна: перерисовка текста на
 * каждое нажатие допустима, пересборка всего экрана — нет.
 */
@Composable
internal fun rememberEditorRevision(state: EditorState): MutableLongState {
    val revision = remember(state) { mutableLongStateOf(state.revision) }

    DisposableEffect(state) {
        val listener = EditorListener { revision.longValue = state.revision }
        state.addListener(listener)
        onDispose { state.removeListener(listener) }
    }

    return revision
}

/**
 * Подписка для composable, которому нужна пересборка на каждой правке.
 *
 * Возвращаемое значение обычно не нужно — важно само чтение.
 */
@Composable
internal fun observeEditor(state: EditorState): Long = rememberEditorRevision(state).longValue
