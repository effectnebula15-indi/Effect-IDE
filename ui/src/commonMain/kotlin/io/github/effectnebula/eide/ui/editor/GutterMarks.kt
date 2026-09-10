package io.github.effectnebula.eide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import io.github.effectnebula.eide.core.editor.EditorState
import kotlinx.coroutines.flow.collectLatest

/**
 * Чем строка отличается от того, что записано в системе контроля версий.
 *
 * Тип свой, а не из `:vcs`: `:ui` про git не знает и знать не должен (ADR-006,
 * правила зависимостей). Перевод одного в другое — работа точки сборки.
 */
enum class GutterMark {
    Added,
    Modified,
    /** Ниже этой строки что-то удалено. Помечается граница: самих строк уже нет. */
    DeletedBelow,
}

/**
 * Пометки гаттера, пересчитываемые после паузы в наборе.
 *
 * Пересчёт стоит сравнения с версией из хранилища, то есть чтения и диффа. Делать
 * это на кадре нельзя, на каждую букву — незачем: человек, набирающий строку,
 * увидит её пометку, когда допишет.
 *
 * [compute] обязана быть `suspend` и уходить с главного потока сама: сколько
 * стоит её работа, знает только тот, кто её передал.
 *
 * Лямбду можно передавать прямо на месте: ни хранилище, ни эффект на неё не
 * завязаны. Это не мелочь — на первой сборке я завязал, и посчитанное
 * выбрасывалось каждой пересборкой. В гаттере при этом не появлялось ничего,
 * а по коду всё выглядело правильно.
 */
@Composable
fun rememberGutterMarks(
    state: EditorState,
    quietMillis: Long = AUTOSAVE_QUIET_MILLIS,
    compute: (suspend (String) -> Map<Int, GutterMark>)?,
): State<Map<Int, GutterMark>> {
    val marks = remember(state) { mutableStateOf(emptyMap<Int, GutterMark>()) }
    val revision = rememberEditorRevision(state)
    val currentCompute by rememberUpdatedState(compute)
    // Ключ эффекта — «есть ли чем считать», а не сама лямбда: лямбда меняется
    // на каждой пересборке, и эффект перезапускался бы без конца.
    val enabled = compute != null

    LaunchedEffect(state, enabled, quietMillis) {
        if (!enabled) {
            marks.value = emptyMap()
            return@LaunchedEffect
        }

        // Первый расчёт — сразу: файл открыт, и пометки должны быть видны до
        // того, как в нём что-нибудь изменят.
        marks.value = currentCompute?.invoke(state.text.toString()).orEmpty()

        // collectLatest, а не collect: пока считается старый текст, мог прийти
        // новый, и досчитывать устаревший незачем.
        saveSignals(snapshotFlow { revision.longValue }, quietMillis) { state.document.version }
            .collectLatest { marks.value = currentCompute?.invoke(state.text.toString()).orEmpty() }
    }

    return marks
}
