package io.github.effectnebula.eide.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/**
 * Сочетание клавиш.
 *
 * [command] — Ctrl, а на macOS Cmd. Различать их в таблице незачем: это одна и
 * та же клавиша «сделай главное действие», просто под разными пальцами.
 */
data class Shortcut(
    val key: Key,
    val label: String,
    val command: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    fun matches(event: KeyEvent): Boolean =
        event.key == key &&
            (event.isCtrlPressed || event.isMetaPressed) == command &&
            event.isShiftPressed == shift &&
            event.isAltPressed == alt
}

/**
 * Команда с сочетанием клавиш.
 *
 * Существует ради одного свойства: **подпись в палитре и разбор нажатия берутся
 * из одного места.** Первая редакция палитры писала «Ctrl+S» рядом с командой,
 * а нажатие Ctrl+S не было связано ни с чем — подпись врала, и заметить это
 * можно было только попробовав. Теперь список команд и обработчик клавиш
 * строятся из одной таблицы, и разойтись им негде.
 */
data class Binding(val id: String, val title: String, val shortcut: Shortcut?)

/**
 * Какое сочетание набрано.
 *
 * Возвращает id команды или null, если такого сочетания в таблице нет. Порядок
 * важен: первое совпадение и выигрывает, поэтому одинаковых сочетаний в таблице
 * быть не должно — это проверяется тестом.
 */
fun commandFor(bindings: List<Binding>, event: KeyEvent): String? =
    bindings.firstOrNull { it.shortcut?.matches(event) == true }?.id

/**
 * Раскладка «как в IntelliJ», насколько она вообще применима.
 *
 * Отклонения названы: палитра висит и на Ctrl+Shift+A (в IntelliJ это «Find
 * Action»), и на Ctrl+Shift+P — второе пришло из VS Code, но набирают его по
 * привычке чаще. Два сочетания на одно действие тут дешевле спора о вкусах.
 *
 * Запуск и остановка — одна команда, поэтому и сочетания у неё два: Shift+F10
 * запускает, Ctrl+F2 останавливает. Подпись меняется вместе с заголовком, чтобы
 * не предлагать остановить то, что не идёт.
 */
object Keys {
    val SaveAll = Shortcut(Key.S, "Ctrl+S", command = true)
    val FindInFile = Shortcut(Key.F, "Ctrl+F", command = true)
    val FindInProject = Shortcut(Key.F, "Ctrl+Shift+F", command = true, shift = true)
    val Run = Shortcut(Key.F10, "Shift+F10", shift = true)
    val Stop = Shortcut(Key.F2, "Ctrl+F2", command = true)
    val Palette = Shortcut(Key.A, "Ctrl+Shift+A", command = true, shift = true)
    val PaletteAlias = Shortcut(Key.P, "Ctrl+Shift+P", command = true, shift = true)
}
