package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.FoldState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Свёртка со стороны пальца.
 *
 * Перевод номеров проверен в ядре обычными тестами; здесь проверяется то, что
 * ядру недоступно: доходит ли тычок в гаттер до свёртки и меняется ли после
 * этого попадание по строкам.
 */
@OptIn(ExperimentalTestApi::class)
class FoldingUiTest {

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

    private val program = "def имя():\n    один = 1\n    два = 2\nснаружи = 3"

    private fun editor() = EditorState(Document(Rope.of(program)), PlainBreaker)

    @Test
    fun `a tap on the gutter folds the block`() = runComposeUiTest {
        val state = editor()
        val folds = FoldState()

        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), folds = folds) }
        waitForIdle()

        onRoot().performMouseInput { clickAt(Offset(4f, 4f)) }
        waitForIdle()

        assertTrue(folds.isFolded(0), "тычок в треугольник не свернул блок")
        assertEquals(2, folds.visibleLineCount(4), "спрятались не те строки")
    }

    @Test
    fun `a second tap unfolds it back`() = runComposeUiTest {
        val state = editor()
        val folds = FoldState()

        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), folds = folds) }
        waitForIdle()

        onRoot().performMouseInput { clickAt(Offset(4f, 4f)) }
        waitForIdle()
        onRoot().performMouseInput { clickAt(Offset(4f, 4f)) }
        waitForIdle()

        assertFalse(folds.isFolded(0))
    }

    @Test
    fun `after folding the row below shows the line after the block`() = runComposeUiTest {
        // Главное, ради чего свёртка вообще опасна: строки на экране перестают
        // совпадать со строками файла. Проверяется тычком по тексту, а не
        // моделью — модель проверена отдельно.
        val state = editor()
        val folds = FoldState()
        var lineHeight = 0f

        setContent {
            val measurer = rememberTextMeasurer()
            lineHeight = remember {
                measurer.measure("0", TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace))
                    .size.height.toFloat()
            }
            CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), fontSizeSp = 13f, folds = folds)
        }
        waitForIdle()

        // Вторая строка сверху до свёртки — «один = 1», то есть первая в файле.
        onRoot().performMouseInput { clickAt(Offset(200f, lineHeight * 1.5f)) }
        waitForIdle()
        assertEquals(1, state.text.lineOf(state.carets.primary.head))

        onRoot().performMouseInput { clickAt(Offset(4f, 4f)) }
        waitForIdle()

        // После свёртки под тем же местом лежит «снаружи = 3» — третья в файле.
        onRoot().performMouseInput { clickAt(Offset(200f, lineHeight * 1.5f)) }
        waitForIdle()
        assertEquals(
            3,
            state.text.lineOf(state.carets.primary.head),
            "тычок попал не в ту строку: свёртка не сдвинула соответствие",
        )
    }

    @Test
    fun `folding takes the caret out of the hidden text`() = runComposeUiTest {
        // Иначе следующая же буква правит текст, которого на экране нет.
        val state = editor()
        val folds = FoldState()
        state.setCarets(io.github.effectnebula.eide.core.editor.CaretSet.single(state.text.lineStart(2)))

        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), folds = folds) }
        waitForIdle()

        onRoot().performMouseInput { clickAt(Offset(4f, 4f)) }
        waitForIdle()

        assertTrue(folds.isFolded(0), "блок не свернулся — проверять нечего")
        assertEquals(
            0,
            state.text.lineOf(state.carets.primary.head),
            "курсор остался внутри спрятанного текста",
        )
    }

    @Test
    fun `a tap on the gutter of a plain line still moves the caret`() = runComposeUiTest {
        // Прежнее поведение гаттера сохраняется там, где сворачивать нечего.
        val state = editor()
        val folds = FoldState()
        var lineHeight = 0f

        setContent {
            val measurer = rememberTextMeasurer()
            lineHeight = remember {
                measurer.measure("0", TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace))
                    .size.height.toFloat()
            }
            CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), fontSizeSp = 13f, folds = folds)
        }
        waitForIdle()

        onRoot().performMouseInput { clickAt(Offset(4f, lineHeight * 1.5f)) }
        waitForIdle()

        assertTrue(folds.isEmpty, "свернулось то, чего сворачивать не просили")
        assertEquals(1, state.text.lineOf(state.carets.primary.head))
    }
}
