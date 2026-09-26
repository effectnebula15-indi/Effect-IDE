package io.github.effectnebula.eide.ui.editor

import androidx.compose.ui.Modifier
import io.github.effectnebula.eide.core.editor.EditorState

/**
 * На десктопе системной клавиатуры в смысле IME нет.
 *
 * Ввод сложных письменностей идёт там через оконную систему и приходит уже
 * готовыми символами в `onKeyEvent`. Когда дойдут руки до китайского и японского
 * на десктопе, здесь появится настоящая реализация — но это отдельная работа,
 * и делать вид, что она уже есть, незачем.
 */
internal actual fun Modifier.imeInput(state: EditorState): Modifier = this
