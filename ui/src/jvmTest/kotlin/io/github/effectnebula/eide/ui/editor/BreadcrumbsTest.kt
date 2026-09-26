package io.github.effectnebula.eide.ui.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.PythonOutline
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Строка пути над редактором.
 *
 * Сам расчёт пути проверен в `:core`. Здесь — что он доходит до экрана, что
 * пересчитывается при движении курсора и что нажатие возвращает к заголовку.
 */
@OptIn(ExperimentalTestApi::class)
class BreadcrumbsTest {

    private val source = """
        class Игра:

            def шаг(self):
                self.двигать()

        def main():
            pass
    """.trimIndent()

    private fun editor() = EditorState(Document(Rope.of(source)), PlainBreaker)

    private fun EditorState.putCaretOn(needle: String) {
        val text = this.text.substring(0, this.text.length)
        val line = text.split("\n").indexOfFirst { it.contains(needle) }
        setCarets(CaretSet.single(this.text.lineStart(line)))
    }

    @Test
    fun `the path of the caret line is on the screen`() = runComposeUiTest {
        val state = editor()
        state.putCaretOn("self.двигать()")

        setContent { Breadcrumbs(state, PythonOutline::crumbsAt) }
        waitForIdle()

        onNodeWithText("class Игра").assertExists()
        onNodeWithText("def шаг(self)").assertExists()
    }

    @Test
    fun `moving the caret changes the path`() = runComposeUiTest {
        val state = editor()
        state.putCaretOn("self.двигать()")

        setContent { Breadcrumbs(state, PythonOutline::crumbsAt) }
        waitForIdle()
        onNodeWithText("class Игра").assertExists()

        state.putCaretOn("pass")
        waitForIdle()

        // Ушли в другую функцию верхнего уровня: класса в пути больше нет.
        onNodeWithText("def main()").assertExists()
        onNodeWithText("class Игра").assertDoesNotExist()
    }

    @Test
    fun `a tap on a step goes to its header`() = runComposeUiTest {
        val state = editor()
        state.putCaretOn("self.двигать()")

        setContent { Breadcrumbs(state, PythonOutline::crumbsAt) }
        waitForIdle()

        onNodeWithText("class Игра").performClick()
        waitForIdle()

        assertEquals(0, state.text.lineOf(state.carets.primary.head), "курсор на строке класса")
    }

    @Test
    fun `without an outline there is no row at all`() = runComposeUiTest {
        // Расчёт по отступам верен для Python и неверен для языка со скобками.
        // Показывать для них выдуманный путь хуже, чем не показывать никакого.
        val state = editor()
        state.putCaretOn("self.двигать()")

        setContent { Breadcrumbs(state, outline = null) }
        waitForIdle()

        onNodeWithText("class Игра").assertDoesNotExist()
        onNodeWithTag(BREADCRUMBS_TAG).assertDoesNotExist()
    }

    @Test
    fun `an empty path keeps the row and its height`() = runComposeUiTest {
        // Пустой путь и отсутствие расчёта на экране выглядят одинаково, а ведут
        // себя по-разному: строка постоянной высоты не даёт редактору прыгать
        // вверх-вниз, когда курсор выходит из блока на верхний уровень.
        val state = editor()
        state.putCaretOn("class Игра")

        setContent { Breadcrumbs(state, PythonOutline::crumbsAt) }
        waitForIdle()

        onNodeWithTag(BREADCRUMBS_TAG).assertExists()
    }
}
