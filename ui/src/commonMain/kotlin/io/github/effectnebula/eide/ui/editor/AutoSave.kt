package io.github.effectnebula.eide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import io.github.effectnebula.eide.core.editor.EditorState
import kotlinx.coroutines.flow.debounce

/**
 * Пауза в наборе, после которой буфер уходит на диск.
 *
 * Цена выбрана в обе стороны: меньше — лишние записи на каждое слово, больше —
 * больше потерянного текста, если систему не устроит наше существование.
 */
const val AUTOSAVE_QUIET_MILLIS: Long = 1_000

/**
 * Автосохранение буфера после паузы в наборе.
 *
 * Android вправе убить процесс в любой момент и без предупреждения. Редактор,
 * теряющий текст, не редактор — а «сохрани сам, я не обещал» здесь не работает:
 * на телефоне приложение уходит в фон от входящего звонка.
 *
 * Сохранение — `suspend`: решать, в каком потоке писать файл, должен тот, кто
 * знает его размер. Здесь известно только, когда писать.
 *
 * Это ещё не журнал правок из плана: при убийстве процесса теряется последняя
 * секунда набора. Настоящая защита — запись правок, а не файла целиком, и она
 * появится вместе с несколькими открытыми файлами.
 */
@Composable
fun AutoSave(
    state: EditorState,
    quietMillis: Long = AUTOSAVE_QUIET_MILLIS,
    save: suspend () -> Unit,
) {
    val revision = rememberEditorRevision(state)
    val currentSave by rememberUpdatedState(save)

    LaunchedEffect(revision, quietMillis) {
        // Первое значение snapshotFlow — текущее состояние, а не изменение:
        // сохранять сразу после открытия файла незачем.
        var savedRevision = revision.longValue

        snapshotFlow { revision.longValue }
            .debounce(quietMillis)
            .collect { value ->
                if (value == savedRevision) return@collect
                savedRevision = value
                currentSave()
            }
    }
}
