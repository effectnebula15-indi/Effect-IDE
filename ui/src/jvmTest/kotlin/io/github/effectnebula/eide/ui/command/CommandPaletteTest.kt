package io.github.effectnebula.eide.ui.command

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.effectnebula.eide.core.command.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Палитра команд.
 *
 * Проверяется то, ради чего её открывают: набрал три буквы, нажал Enter, и
 * выполнилось задуманное. Ранжирование проверено отдельно в `:core`; здесь —
 * что выбранное на экране и выполненное по Enter это одно и то же.
 */
@OptIn(ExperimentalTestApi::class)
class CommandPaletteTest {

    private val commands = listOf(
        Command("file.save", "Сохранить файл", "Ctrl+S"),
        Command("file.saveAll", "Сохранить всё"),
        Command("run.start", "Запустить", "Ctrl+R"),
        Command("run.stop", "Остановить"),
        Command("git.commit", "Зафиксировать изменения"),
    )

    @Test
    fun `enter runs the first command of the list`() = runComposeUiTest {
        var ran: Command? = null
        setContent { CommandPalette(commands, onRun = { ran = it }, onDismiss = {}) }
        waitForIdle()

        onNode(isEditable()).performTextInput("заф")
        waitForIdle()
        onNode(isEditable()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("git.commit", ran?.id)
    }

    @Test
    fun `the arrow keys pick another command`() = runComposeUiTest {
        var ran: Command? = null
        setContent { CommandPalette(commands, onRun = { ran = it }, onDismiss = {}) }
        waitForIdle()

        onNode(isEditable()).performTextInput("сохранить")
        waitForIdle()
        onNode(isEditable()).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNode(isEditable()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("file.saveAll", ran?.id)
    }

    @Test
    fun `selection wraps around instead of stopping at the edge`() = runComposeUiTest {
        var ran: Command? = null
        setContent { CommandPalette(commands, onRun = { ran = it }, onDismiss = {}) }
        waitForIdle()

        onNode(isEditable()).performTextInput("сохранить")
        waitForIdle()
        // Список из двух; вверх с первой строки — это последняя, а не упор.
        onNode(isEditable()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNode(isEditable()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("file.saveAll", ran?.id)
    }

    @Test
    fun `escape closes the palette without running anything`() = runComposeUiTest {
        var ran: Command? = null
        var dismissed = false
        setContent { CommandPalette(commands, onRun = { ran = it }, onDismiss = { dismissed = true }) }
        waitForIdle()

        onNode(isEditable()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(true, dismissed)
        assertNull(ran)
    }

    @Test
    fun `a tap runs the command it landed on`() = runComposeUiTest {
        var ran: Command? = null
        setContent { CommandPalette(commands, onRun = { ran = it }, onDismiss = {}) }
        waitForIdle()

        onNodeWithText("Остановить").performClick()
        waitForIdle()

        assertEquals("run.stop", ran?.id)
    }

    @Test
    fun `a narrowed list does not run a command from the old one`() = runComposeUiTest {
        // Так это и ломается: выбрали пятую строку, дописали букву, строк стало
        // две — и Enter выполняет то, чего на экране уже нет.
        var ran: Command? = null
        setContent { CommandPalette(commands, onRun = { ran = it }, onDismiss = {}) }
        waitForIdle()

        onNode(isEditable()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNode(isEditable()).performTextInput("сохранить")
        waitForIdle()
        onNode(isEditable()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("file.save", ran?.id, "после сужения списка выбор начинается заново")
    }

    @Test
    fun `an empty query shows every command`() = runComposeUiTest {
        setContent { CommandPalette(commands, onRun = {}, onDismiss = {}) }
        waitForIdle()

        onNodeWithText("Сохранить файл").assertIsDisplayed()
        onNodeWithText("Зафиксировать изменения").assertIsDisplayed()
        // Подпись горячей клавиши — рядом с командой, а не в поиске.
        onNodeWithText("Ctrl+S").assertIsDisplayed()
    }

    @Test
    fun `nothing found says so`() = runComposeUiTest {
        setContent { CommandPalette(commands, onRun = {}, onDismiss = {}) }
        waitForIdle()

        onNode(isEditable()).performTextInput("щщщ")
        waitForIdle()

        onNodeWithText("нет такой команды").assertExists()
    }

    @Test
    fun `a shrinking command list does not leave the selection hanging`() = runComposeUiTest {
        // Список команд меняется снаружи: пока программа работает, «Запустить»
        // уступает место «Остановить». Выбор при этом может указывать за конец
        // нового списка — и Enter не сделает ничего, а это выглядит как
        // сломанная палитра, а не как «команда исчезла».
        var ran: Command? = null
        var shown by mutableStateOf(commands)

        setContent { CommandPalette(shown, onRun = { ran = it }, onDismiss = {}) }
        waitForIdle()

        onNode(isEditable()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()

        shown = commands.take(2)
        waitForIdle()
        onNode(isEditable()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("file.saveAll", ran?.id, "выбор подрезан до последней строки нового списка")
    }

}
