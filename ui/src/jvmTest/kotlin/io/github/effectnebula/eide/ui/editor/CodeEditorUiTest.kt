package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Проверка самого редактора: жест доходит до состояния.
 *
 * Всё, что есть у редактора на экране, нарисовано на одном холсте, поэтому узлов
 * с текстом здесь нет и искать их бесполезно. Утверждения строятся иначе: тычок
 * мышью в известное место обязан поставить курсор туда, куда человек ткнул, — и
 * это же проверяет прокрутку, потому что после неё под тем же местом на экране
 * лежит другой текст.
 *
 * Числа в утверждениях относительные: ширина цифры и высота строки зависят от
 * системного шрифта тестовой машины, и жёсткое число здесь означало бы проверку
 * шрифта, а не редактора.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorUiTest {

    private val colors = EditorColors(
        background = Color.Black,
        text = Color.White,
        gutterBackground = Color.DarkGray,
        gutterText = Color.Gray,
        currentLineGutterText = Color.White,
        selection = Color.Blue,
        caret = Color.White,
        searchMatch = Color.Green,
    )

    private fun editor(text: String) = EditorState(Document(Rope.of(text)), PlainBreaker)

    /** Строка, заведомо не помещающаяся в окно теста. */
    private val longLine = "0123456789".repeat(30)

    @Test
    fun `a tap moves the caret to the tapped place`() = runComposeUiTest {
        val state = editor(longLine)
        setContent {
            CodeEditor(state, colors, Modifier.size(400.dp, 200.dp))
        }

        onRoot().performMouseInput { clickAt(Offset(300f, 5f)) }
        waitForIdle()

        val head = state.carets.primary.head
        assertTrue(head > 0, "тычок в середину строки не сдвинул курсор: $head")
    }

    @Test
    fun `scrolling sideways brings later columns under the same point`() = runComposeUiTest {
        val state = editor(longLine)
        setContent {
            CodeEditor(state, colors, Modifier.size(400.dp, 200.dp))
        }

        onRoot().performMouseInput { clickAt(Offset(300f, 5f)) }
        waitForIdle()
        val before = state.carets.primary.head

        // Колесо вбок: то же самое, что провести пальцем по горизонтали.
        onRoot().performMouseInput {
            moveTo(Offset(200f, 100f))
            scroll(200f, ScrollWheel.Horizontal)
        }
        waitForIdle()

        onRoot().performMouseInput { clickAt(Offset(300f, 5f)) }
        waitForIdle()
        val after = state.carets.primary.head

        assertTrue(after > before, "после сдвига вправо под тем же местом должен лежать текст дальше по строке: $before → $after")
    }

    @Test
    fun `sideways scrolling stops at the end of the longest line`() = runComposeUiTest {
        // Без ограничения текст уезжает в пустоту и найти его обратно нечем.
        // Проверяется наблюдаемое следствие: докрутив до упора, у левого края
        // экрана обязан лежать текст, а не хвост за концом строки.
        val state = editor(longLine)
        setContent {
            CodeEditor(state, colors, Modifier.size(400.dp, 200.dp))
        }

        onRoot().performMouseInput {
            moveTo(Offset(200f, 100f))
            scroll(100_000f, ScrollWheel.Horizontal)
        }
        waitForIdle()

        // 60 пикселей — заведомо правее гаттера: он шире трёх цифр не бывает
        // для документа в одну строку.
        onRoot().performMouseInput { clickAt(Offset(60f, 5f)) }
        waitForIdle()

        val head = state.carets.primary.head
        assertTrue(
            head < longLine.length,
            "прокрутка ушла за конец строки: у левого края уже нет текста ($head из ${longLine.length})",
        )
    }

    @Test
    fun `scrolling down brings later lines under the same point`() = runComposeUiTest {
        val state = editor((0 until 200).joinToString("\n") { "line $it" })
        setContent {
            CodeEditor(state, colors, Modifier.size(400.dp, 200.dp))
        }

        onRoot().performMouseInput { clickAt(Offset(300f, 5f)) }
        waitForIdle()
        val before = state.text.lineOf(state.carets.primary.head)

        onRoot().performMouseInput {
            moveTo(Offset(200f, 100f))
            scroll(300f, ScrollWheel.Vertical)
        }
        waitForIdle()

        onRoot().performMouseInput { clickAt(Offset(300f, 5f)) }
        waitForIdle()

        val after = state.text.lineOf(state.carets.primary.head)
        assertTrue(after > before, "после прокрутки вниз под верхом экрана должна быть строка ниже: $before → $after")
    }
}

/**
 * Щелчок в заданной точке.
 *
 * Собран из moveTo/press/release: готового `click` у мышиного сценария в этой
 * версии нет, а тот, что находится по имени, относится к касанию.
 */
@OptIn(ExperimentalTestApi::class)
private fun MouseInjectionScope.clickAt(position: Offset) {
    moveTo(position)
    press()
    release()
}
