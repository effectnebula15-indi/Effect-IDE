package io.github.effectnebula.eide.ui.run

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Панель вывода программы.
 *
 * Обещание у неё двойное и внутренне противоречивое: доезжать до конца, когда
 * приходят новые строки, и не дёргать того, кто отлистал назад читать
 * трассировку. По отдельности каждое сделать легко.
 */
@OptIn(ExperimentalTestApi::class)
class OutputPanelTest {

    private fun lines(count: Int) = (0 until count).joinToString("\n") { "строка вывода номер $it" }

    /** Насколько прокручена панель и докуда её вообще можно прокрутить. */
    private fun SemanticsNodeInteraction.scrollPosition(): Pair<Float, Float> {
        val range = fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }

    @Test
    fun `new output brings the panel to the end`() = runComposeUiTest {
        // Так и было сломано: эффект знал только про текст, а разметки нового
        // текста к тому моменту ещё не случилось. Панель «доезжала до конца»
        // прежнего содержимого, то есть оставалась на первой строке.
        var text by mutableStateOf("первая строка\n")

        setContent { OutputPanel(text, Modifier.size(300.dp, 120.dp)) }
        waitForIdle()

        text = lines(100)
        waitForIdle()

        val (value, max) = onNode(hasScrollAction()).scrollPosition()
        assertTrue(max > 0f, "прокручивать нечего — тест ничего не проверяет")
        assertEquals(max, value, "панель не доехала до конца вывода")
    }

    @Test
    fun `output does not yank back the one who scrolled up`() = runComposeUiTest {
        // Второе обещание панели: программа пишет дальше, а человек читает
        // трассировку выше — и его не должно утаскивать вниз.
        var text by mutableStateOf(lines(100))

        setContent { OutputPanel(text, Modifier.size(300.dp, 120.dp)) }
        waitForIdle()

        onNode(hasScrollAction()).performTouchInput { swipeDown() }
        waitForIdle()

        val (afterSwipe, max) = onNode(hasScrollAction()).scrollPosition()
        assertTrue(afterSwipe < max - 48f, "жест не отлистал назад — проверять нечего")

        text = lines(200)
        waitForIdle()

        val (afterOutput, newMax) = onNode(hasScrollAction()).scrollPosition()
        assertTrue(
            afterOutput < newMax - 48f,
            "новый вывод утащил вниз того, кто отлистал назад: $afterOutput из $newMax",
        )
    }

    @Test
    fun `output that fits does not scroll`() = runComposeUiTest {
        var text by mutableStateOf("")

        setContent { OutputPanel(text, Modifier.size(300.dp, 400.dp)) }
        waitForIdle()

        text = "одна строка"
        waitForIdle()

        val (value, _) = onNode(hasScrollAction()).scrollPosition()
        assertEquals(0f, value)
    }
}
