package io.github.effectnebula.eide.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Раскладка клавиш.
 *
 * Разбор самого нажатия проверить здесь нечем: `KeyEvent` в Compose заворачивает
 * событие AWT, и собрать его в обычном JVM-тесте — это собирать половину оконной
 * системы. Поэтому проверяется таблица: её свойства и есть то, что ломается при
 * правке. Само нажатие проверяется руками по чек-листу ниже.
 */
class KeymapTest {

    private val all = listOf(
        Keys.SaveAll,
        Keys.FindInFile,
        Keys.FindInProject,
        Keys.Run,
        Keys.Stop,
        Keys.Palette,
        Keys.PaletteAlias,
    )

    @Test
    fun `no two commands share a shortcut`() {
        // `commandFor` берёт первое совпадение: два одинаковых сочетания означают,
        // что второе недостижимо, а какое из них второе — вопрос порядка в списке.
        val duplicates = all
            .groupBy { it.key to Triple(it.command, it.shift, it.alt) }
            .filterValues { it.size > 1 }

        assertTrue(duplicates.isEmpty(), "повторяются: $duplicates")
    }

    @Test
    fun `the label spells out the modifiers it actually requires`() {
        // Подпись — это обещание. Оно живёт рядом с сочетанием ровно затем, чтобы
        // расходиться им было негде, но написана она всё-таки руками.
        for (shortcut in all) {
            val label = shortcut.label
            assertEquals(shortcut.command, label.contains("Ctrl+"), "модификатор Ctrl в «$label»")
            assertEquals(shortcut.shift, label.contains("Shift+"), "модификатор Shift в «$label»")
            assertEquals(shortcut.alt, label.contains("Alt+"), "модификатор Alt в «$label»")
        }
    }

    @Test
    fun `commandFor ignores an event that is not in the table`() {
        assertEquals(null, commandFor(emptyList(), keyEventStub()))
    }

    /**
     * Событие, которого в таблице нет ни при каком разборе.
     *
     * Пустая таблица — единственный способ проверить `commandFor` без настоящего
     * `KeyEvent`: до сравнения дело не доходит.
     */
    /** Источник события. `Label` для этого не годится: он требует X11. */
    private object HeadlessComponent : java.awt.Component()

    private fun keyEventStub(): androidx.compose.ui.input.key.KeyEvent =
        androidx.compose.ui.input.key.KeyEvent(java.awt.event.KeyEvent(
            HeadlessComponent,
            java.awt.event.KeyEvent.KEY_PRESSED,
            0L,
            0,
            java.awt.event.KeyEvent.VK_UNDEFINED,
            ' ',
        ))
}
