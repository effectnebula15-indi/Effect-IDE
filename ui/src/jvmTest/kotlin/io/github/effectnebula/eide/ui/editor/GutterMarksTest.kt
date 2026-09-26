package io.github.effectnebula.eide.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Пометки правок в гаттере.
 *
 * Проверяется не дифф — это работа `:vcs` и его тестов, — а то, что посчитанное
 * доходит до экрана и переживает пересборку. Ровно здесь всё и сломалось при
 * первой сборке: лямбда расчёта была ключом хранилища, пересоздавалась на каждой
 * пересборке, и результат выбрасывался немедленно. По коду это выглядело
 * правильно; увидеть удалось только на снимке экрана.
 */
@OptIn(ExperimentalTestApi::class)
class GutterMarksTest {

    private fun editor(text: String) = EditorState(Document(Rope.of(text)), PlainBreaker)

    @Test
    fun `marks show up without waiting for the pause`() = runComposeUiTest {
        // Открыли файл — пометки должны быть видны сразу, а не после первой правки.
        val state = editor("одна\nдве\nтри")
        var seen: Map<Int, GutterMark> = emptyMap()

        setContent {
            // Лямбда прямо на месте: она пересоздаётся на каждой пересборке, и
            // это не должно ничего ломать.
            val marks by rememberGutterMarks(state) { mapOf(1 to GutterMark.Added) }
            seen = marks
        }
        waitForIdle()

        assertEquals(mapOf(1 to GutterMark.Added), seen)
    }

    @Test
    fun `marks survive recomposition`() = runComposeUiTest {
        val state = editor("одна\nдве")
        var seen: Map<Int, GutterMark> = emptyMap()
        var computations = 0

        setContent {
            val marks by rememberGutterMarks(state) {
                computations++
                mapOf(0 to GutterMark.Modified)
            }
            seen = marks
        }
        waitForIdle()

        // Пересборка от движения курсора: пометки обязаны остаться на месте,
        // а считать заново их незачем — текст не менялся.
        repeat(3) {
            state.setCarets(io.github.effectnebula.eide.core.editor.CaretSet.single(it))
            waitForIdle()
        }

        assertEquals(mapOf(0 to GutterMark.Modified), seen, "пометки исчезли после пересборки")
        assertEquals(1, computations, "пометки пересчитывались без правок")
    }

    @Test
    fun `no source of marks means no marks`() = runComposeUiTest {
        val state = editor("одна")
        var seen: Map<Int, GutterMark> = mapOf(0 to GutterMark.Added)

        setContent {
            val marks by rememberGutterMarks(state, compute = null)
            seen = marks
        }
        waitForIdle()

        assertEquals(emptyMap(), seen, "без репозитория в гаттере не должно быть ничего")
    }
}
