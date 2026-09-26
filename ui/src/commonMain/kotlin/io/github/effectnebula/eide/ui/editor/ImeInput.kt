package io.github.effectnebula.eide.ui.editor

import androidx.compose.ui.Modifier
import io.github.effectnebula.eide.core.editor.EditorState

/**
 * Подключение системной клавиатуры к редактору.
 *
 * Существует ради Android: там экранная клавиатура — единственный способ ввода
 * у большинства людей, и говорит она не нажатиями клавиш, а через
 * `InputConnection`. На десктопе такого понятия нет, и модификатор ничего не
 * делает: ввод там идёт клавишами через `editorKeyInput`.
 */
internal expect fun Modifier.imeInput(state: EditorState): Modifier
