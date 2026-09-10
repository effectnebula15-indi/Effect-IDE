package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Свёртка: какие блоки видит и как из-за неё сдвигаются номера строк.
 *
 * Перевод номеров сделан на слитых отрезках с накопленными суммами — быстро,
 * но не очевидно. Поэтому он сверяется с прямым перебором: медленным, зато
 * заведомо правильным.
 */
class FoldingTest {

    private fun rope(vararg lines: String) = Rope.of(lines.joinToString("\n"))

    // --- поиск блоков ----------------------------------------------------------

    @Test
    fun `a python function is one block`() {
        val text = rope(
            "def первая():",
            "    один = 1",
            "    два = 2",
            "третья = 3",
        )

        assertEquals(listOf(FoldRegion(0, 2)), IndentFolding.regions(text))
    }

    @Test
    fun `nested blocks are all found`() {
        val text = rope(
            "class Имя:",
            "    def метод(self):",
            "        тело = 1",
            "    другое = 2",
        )

        assertEquals(
            listOf(FoldRegion(0, 3), FoldRegion(1, 2)),
            IndentFolding.regions(text),
        )
    }

    @Test
    fun `a blank line inside a block does not end it`() {
        // Между методами класса пустая строка — норма, а не конец класса.
        val text = rope(
            "class Имя:",
            "    первый = 1",
            "",
            "    второй = 2",
            "снаружи = 3",
        )

        assertEquals(FoldRegion(0, 3), IndentFolding.regions(text).first())
    }

    @Test
    fun `trailing blank lines stay outside the block`() {
        val text = rope("def имя():", "    тело = 1", "", "")

        assertEquals(listOf(FoldRegion(0, 1)), IndentFolding.regions(text))
    }

    @Test
    fun `flat text has nothing to fold`() {
        assertEquals(emptyList(), IndentFolding.regions(rope("один = 1", "два = 2")))
    }

    @Test
    fun `a tab counts up to the next tab stop`() {
        // Смешанные отступы иначе дают бессмысленную вложенность.
        val text = rope("def имя():", "\tтело = 1", "снаружи = 2")

        assertEquals(listOf(FoldRegion(0, 1)), IndentFolding.regions(text))
    }

    // --- два дешёвых способа спросить то же самое ------------------------------

    @Test
    fun `the cheap check agrees with the full pass`() {
        // isFoldable и regionAt существуют ради цены: полный проход по файлу на
        // кадре недопустим. Отвечать они обязаны то же самое, что полный проход,
        // иначе треугольник в гаттере будет обещать не то, что случится по нажатию.
        val text = rope(
            "class Имя:",
            "    def первый(self):",
            "        тело = 1",
            "",
            "    def второй(self):",
            "        тело = 2",
            "снаружи = 3",
            "",
        )

        val full = IndentFolding.regions(text).associateBy { it.header }

        for (line in 0 until text.lineCount) {
            assertEquals(
                full.containsKey(line),
                IndentFolding.isFoldable(text, line),
                "строка $line: дешёвая проверка разошлась с полным проходом",
            )
            assertEquals(
                full[line],
                IndentFolding.regionAt(text, line),
                "строка $line: участок по нажатию разошёлся с полным проходом",
            )
        }
    }

    // --- перевод номеров -------------------------------------------------------

    /** Прямой перебор: заведомо правильно, заведомо медленно. */
    private fun slowVisibleLines(state: FoldState, lineCount: Int): List<Int> =
        (0 until lineCount).filterNot { state.isHidden(it) }

    private fun checkAgainstBruteForce(state: FoldState, lineCount: Int) {
        val visible = slowVisibleLines(state, lineCount)

        assertEquals(
            visible.size,
            state.visibleLineCount(lineCount),
            "число видимых строк разошлось с перебором",
        )
        for ((visual, document) in visible.withIndex()) {
            assertEquals(document, state.documentLine(visual, lineCount), "видимая $visual")
            assertEquals(visual, state.visualLine(document, lineCount), "документная $document")
        }
    }

    @Test
    fun `nothing folded means the numbers match`() {
        val state = FoldState()

        assertTrue(state.isEmpty)
        checkAgainstBruteForce(state, lineCount = 10)
    }

    @Test
    fun `one folded block shifts everything below it`() {
        val state = FoldState()
        state.fold(FoldRegion(header = 2, last = 5))

        // Спрятаны строки 3, 4 и 5; видны 0, 1, 2, 6, 7, 8, 9. Четвёртая сверху
        // видимая — это шестая строка документа.
        assertEquals(6, state.documentLine(3, lineCount = 10))
        checkAgainstBruteForce(state, lineCount = 10)
    }

    @Test
    fun `nested folded blocks do not count lines twice`() {
        // Наивная сумма спрятанных строк дала бы здесь отрицательную высоту.
        val state = FoldState()
        state.fold(FoldRegion(header = 0, last = 9))
        state.fold(FoldRegion(header = 2, last = 5))

        assertEquals(1, state.visibleLineCount(lineCount = 10))
        checkAgainstBruteForce(state, lineCount = 10)
    }

    @Test
    fun `touching blocks merge`() {
        val state = FoldState()
        state.fold(FoldRegion(header = 0, last = 3))
        state.fold(FoldRegion(header = 4, last = 7))

        checkAgainstBruteForce(state, lineCount = 12)
    }

    @Test
    fun `many blocks agree with brute force`() {
        val state = FoldState()
        // Разной длины и с разрывами: именно на стыках и живут ошибки на единицу.
        state.fold(FoldRegion(header = 1, last = 2))
        state.fold(FoldRegion(header = 5, last = 9))
        state.fold(FoldRegion(header = 12, last = 13))
        state.fold(FoldRegion(header = 20, last = 40))

        checkAgainstBruteForce(state, lineCount = 60)
    }

    @Test
    fun `blocks inside blocks agree with brute force`() {
        // Отдельно от предыдущего теста, потому что проверяет другое: слияние
        // пересекающихся участков. Без него отрезки перестают быть
        // непересекающимися, накопленные суммы врут, а двоичный поиск по
        // немонотонному ключу отвечает как придётся — иногда даже верно.
        // Проверять это на одном вложенном блоке бесполезно: там «как придётся»
        // случайно совпадает с правильным ответом.
        val state = FoldState()
        state.fold(FoldRegion(header = 2, last = 20))
        state.fold(FoldRegion(header = 5, last = 9))
        state.fold(FoldRegion(header = 12, last = 15))
        state.fold(FoldRegion(header = 22, last = 25))

        checkAgainstBruteForce(state, lineCount = 30)
    }

    @Test
    fun `a hidden line reports the number of its header`() {
        // Курсор внутри свёрнутого блока должен показываться на самом блоке,
        // а не пропадать с экрана.
        val state = FoldState()
        state.fold(FoldRegion(header = 2, last = 5))

        assertEquals(state.visualLine(2, 10), state.visualLine(4, 10))
    }

    @Test
    fun `unfolding brings the numbers back`() {
        val state = FoldState()
        state.fold(FoldRegion(header = 2, last = 5))
        state.unfold(2)

        assertTrue(state.isEmpty)
        checkAgainstBruteForce(state, lineCount = 10)
    }

    @Test
    fun `toggle folds and unfolds`() {
        val state = FoldState()
        val region = FoldRegion(header = 1, last = 3)

        state.toggle(region)
        assertTrue(state.isFolded(1))
        state.toggle(region)
        assertFalse(state.isFolded(1))
    }

    @Test
    fun `an empty block is not folded`() {
        val state = FoldState()
        state.fold(FoldRegion(header = 3, last = 3))

        assertTrue(state.isEmpty, "свёрнут участок, в котором нечего прятать")
    }

    @Test
    fun `a line outside any block has no enclosing region`() {
        val state = FoldState()
        state.fold(FoldRegion(header = 2, last = 5))

        assertNull(state.enclosing(1))
        assertEquals(FoldRegion(2, 5), state.enclosing(4))
    }
}
