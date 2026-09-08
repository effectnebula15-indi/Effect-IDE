package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Поиск и замена глазами пользователя.
 *
 * Проверяется не то, что регулярное выражение находит подстроку — это работа
 * `TextSearch` и его собственных тестов, — а то, что кнопки делают ожидаемое:
 * «дальше» идёт дальше, «заменить» не топчется на месте, обход по кругу
 * возвращается к началу.
 */
class SearchSessionTest {

    private object Breaker : GraphemeBreaker {
        override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
        override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
    }

    private fun editor(text: String): EditorState =
        EditorState(Document(Rope.of(text)), Breaker)

    private fun session(text: String, pattern: String, regex: Boolean = false): Pair<EditorState, SearchSession> {
        val state = editor(text)
        val session = SearchSession(state)
        session.setQuery(SearchQuery(pattern, isRegex = regex))
        return state to session
    }

    private fun EditorState.dump() = text.substring(0, text.length)

    // --- перемещение по совпадениям --------------------------------------------

    @Test
    fun `find next selects the match`() {
        val (state, session) = session("один два один", "один")

        assertTrue(session.findNext())

        assertEquals(0, state.carets.primary.start)
        assertEquals(4, state.carets.primary.end)
    }

    @Test
    fun `find next does not stand on the same match`() {
        // Иначе кнопка «дальше» после первого нажатия перестаёт работать, и
        // выглядит это как «поиск сломался».
        val (state, session) = session("один два один", "один")

        session.findNext()
        assertTrue(session.findNext())

        assertEquals(9, state.carets.primary.start)
    }

    @Test
    fun `find next wraps around to the beginning`() {
        val (state, session) = session("один два один", "один")

        session.findNext()
        session.findNext()
        assertTrue(session.findNext())

        assertEquals(0, state.carets.primary.start, "обход по кругу не вернулся к началу")
    }

    @Test
    fun `find previous walks backwards`() {
        val (state, session) = session("один два один", "один")

        session.findNext()
        session.findNext()
        assertTrue(session.findPrevious())

        assertEquals(0, state.carets.primary.start)
    }

    @Test
    fun `nothing found leaves the caret alone`() {
        val (state, session) = session("один два", "три")
        val before = state.carets.primary

        assertFalse(session.findNext())

        assertNull(session.current)
        assertEquals(before, state.carets.primary)
    }

    @Test
    fun `search starts from the caret, not from the beginning`() {
        val (state, session) = session("один два один", "один")
        state.setCarets(CaretSet.single(5))

        session.findNext()

        assertEquals(9, state.carets.primary.start, "поиск начался не от курсора")
    }

    // --- замена ----------------------------------------------------------------

    @Test
    fun `replace changes the current match and moves on`() {
        val (state, session) = session("один два один", "один")
        session.findNext()

        assertTrue(session.replaceCurrent("ОДИН"))

        assertEquals("ОДИН два один", state.dump())
        assertEquals(9, state.carets.primary.start, "замена не перешла к следующему")
    }

    @Test
    fun `replace without a current match does nothing`() {
        val (state, session) = session("один два", "один")

        assertFalse(session.replaceCurrent("ОДИН"))

        assertEquals("один два", state.dump())
    }

    @Test
    fun `replace all is a single undo action`() {
        // Иначе откат замены по всему файлу превращается в сотню нажатий.
        val (state, session) = session("а б а б а", "а")

        assertEquals(3, session.replaceAll("Я"))
        assertEquals("Я б Я б Я", state.dump())

        state.undo()

        assertEquals("а б а б а", state.dump())
    }

    @Test
    fun `replace all on nothing found changes nothing`() {
        val (state, session) = session("один два", "три")

        assertEquals(0, session.replaceAll("ТРИ"))
        assertEquals("один два", state.dump())
    }

    // --- регулярные выражения ---------------------------------------------------

    @Test
    fun `group references work in regex mode`() {
        val (state, session) = session("имя: Иван", "имя: (\\w+)", regex = true)

        // \w в Java по умолчанию только ASCII, и по русскому тексту не находит
        // ничего. Человек пишет это выражение, зная Python, где наоборот.
        assertTrue(session.findNext(), "\\w не увидел кириллицу")
        assertTrue(session.replaceCurrent("$1 — имя"))

        assertEquals("Иван — имя", state.dump())
    }

    @Test
    fun `case-insensitive search works on cyrillic`() {
        // Это работает и без юникодного флага в выражении — проверено, и
        // проверка стоит здесь именно поэтому: чтобы следующая «оптимизация»
        // регулярного выражения не сломала обычный поиск по русскому тексту.
        val (state, session) = session("Шаг первый, шаг второй", "ШАГ")

        assertTrue(session.findNext(), "поиск без учёта регистра не нашёл кириллицу")
        assertEquals(0, state.carets.primary.start)

        assertTrue(session.findNext())
        assertEquals(12, state.carets.primary.start)
    }

    @Test
    fun `whole word works on cyrillic`() {
        val state = editor("шаг шагает")
        val session = SearchSession(state)
        session.setQuery(SearchQuery("шаг", wholeWord = true))

        assertEquals(1, session.count(), "«слово целиком» посчитало часть слова")
    }

    @Test
    fun `a dollar in plain mode is a dollar`() {
        // Без этого доллар в заменяемом тексте молча превратился бы в ссылку
        // на группу, и человек получил бы пустоту вместо знака валюты.
        val (state, session) = session("цена", "цена")
        session.findNext()

        session.replaceCurrent("$1000")

        assertEquals("$1000", state.dump())
    }

    @Test
    fun `a broken regex reports an error instead of throwing`() {
        val state = editor("текст")
        val session = SearchSession(state)

        session.setQuery(SearchQuery("(незакрытая", isRegex = true))

        assertTrue(session.error != null, "сломанное выражение прошло молча")
        assertFalse(session.findNext())
    }

    // --- подсветка и счёт -------------------------------------------------------

    @Test
    fun `only matches inside the range are highlighted`() {
        val (_, session) = session("аа бб аа бб аа", "аа")

        val visible = session.matchesIn(from = 3, to = 9)

        assertEquals(listOf(6), visible.map { it.start })
    }

    @Test
    fun `a match reaching into the range is highlighted too`() {
        // Иначе при прокрутке совпадение, начавшееся выше экрана, пропадает.
        val (_, session) = session("ааааа", "ааа")

        val visible = session.matchesIn(from = 2, to = 5)

        assertEquals(listOf(0), visible.map { it.start })
    }

    @Test
    fun `an empty query highlights nothing`() {
        val state = editor("текст")
        val session = SearchSession(state)

        assertEquals(emptyList(), session.matchesIn(0, 5))
        assertEquals(0, session.count())
    }

    @Test
    fun `counting stops at the ceiling and says so`() {
        // Точный счёт на большом файле — полный проход; на каждое нажатие в
        // строке поиска это неприемлемо.
        val (_, session) = session("а".repeat(2_000), "а")

        assertEquals(500, session.count())
        assertFalse(session.countIsExact(), "потолок счёта выдан за точное число")
    }

    @Test
    fun `a small number of matches is counted exactly`() {
        val (_, session) = session("а б а", "а")

        assertEquals(2, session.count())
        assertTrue(session.countIsExact())
    }
}
