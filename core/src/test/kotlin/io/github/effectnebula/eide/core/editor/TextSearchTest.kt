package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextSearchTest {

    private fun search(
        pattern: String,
        isRegex: Boolean = false,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
    ) = TextSearch(SearchQuery(pattern, isRegex, caseSensitive, wholeWord))

    @Test
    fun `обычный поиск не считает спецсимволы регулярным выражением`() {
        val text = Rope.of("цена 1+1 и 1+1 рублей")
        val matches = search("1+1").findAll(text).toList()
        assertEquals(2, matches.size, "поиск литерала сломался о плюс")
    }

    @Test
    fun `регистронезависимость по умолчанию`() {
        val text = Rope.of("Привет привет ПРИВЕТ")
        assertEquals(3, search("привет").findAll(text).count())
        assertEquals(1, search("привет", caseSensitive = true).findAll(text).count())
    }

    @Test
    fun `слово целиком работает с кириллицей`() {
        // \b в Java опирается на \w, куда кириллица не входит: наивная реализация
        // не нашла бы здесь ничего.
        val text = Rope.of("шаг шаги подшаг шаг")
        val matches = search("шаг", wholeWord = true).findAll(text).toList()
        assertEquals(2, matches.size, "поиск слова целиком не понял кириллицу")
        assertEquals(0, matches.first().start)
        assertEquals(16, matches.last().start)
    }

    @Test
    fun `совпадение через границу чанка находится`() {
        // Rope режет текст на листья по MAX_LEAF символов. Совпадение, лежащее
        // на стыке, — первое, что ломается при наивной работе с деревом.
        val filler = "x".repeat(Rope.MAX_LEAF - 3)
        val text = Rope.of(filler + "НАЙДИ_МЕНЯ" + "y".repeat(5_000))

        val match = assertNotNull(search("НАЙДИ_МЕНЯ").findNext(text, 0))
        assertEquals(filler.length, match.start)
        assertEquals(filler.length + 10, match.end)
    }

    @Test
    fun `совпадений много и все на месте в большом документе`() {
        val block = "строка без совпадения\nздесь ЦЕЛЬ внутри\n"
        val text = Rope.of(block.repeat(500))

        val matches = search("ЦЕЛЬ").findAll(text).toList()

        assertEquals(500, matches.size)
        for (match in matches) {
            assertEquals("ЦЕЛЬ", text.substring(match.start, match.end))
        }
    }

    @Test
    fun `группы регулярного выражения доступны`() {
        val text = Rope.of("def foo(): pass\ndef bar(): pass")
        val matches = search("""def (\w+)\(""", isRegex = true).findAll(text).toList()

        assertEquals(listOf("foo", "bar"), matches.map { it.groups.first() })
    }

    @Test
    fun `замена подставляет группы`() {
        val text = Rope.of("def foo():\ndef bar():")
        val edit = search("""def (\w+)\(""", isRegex = true).replaceAll(text, "fun $1(")

        assertEquals("fun foo():\nfun bar():", edit.applyTo(text).toString())
    }

    @Test
    fun `при обычном поиске доллар в замене остаётся долларом`() {
        // Иначе замена «на $1 рубль» молча превратилась бы в ссылку на группу.
        val text = Rope.of("цена X")
        val edit = search("X").replaceAll(text, "$1 рубль")

        assertEquals("цена $1 рубль", edit.applyTo(text).toString())
    }

    @Test
    fun `замена всех совпадений это одна корректная транзакция`() {
        val text = Rope.of("aXbXcXd")
        val edit = search("X").replaceAll(text, "___")

        assertEquals("a___b___c___d", edit.applyTo(text).toString())
        assertEquals(3, edit.replacements.size)
    }

    @Test
    fun `поиск вперёд оборачивается по кругу`() {
        val text = Rope.of("цель ... цель")
        val engine = search("цель")

        assertEquals(9, engine.findNext(text, 1)?.start)
        assertEquals(0, engine.findNext(text, 10)?.start, "поиск не обернулся к началу")
        assertNull(engine.findNext(text, 10, wrap = false))
    }

    @Test
    fun `поиск назад находит предыдущее совпадение`() {
        val text = Rope.of("цель ... цель ... цель")
        val engine = search("цель")

        assertEquals(9, engine.findPrevious(text, before = 20)?.start)
        assertEquals(0, engine.findPrevious(text, before = 9)?.start)
        // От начала документа назад — оборачиваемся к последнему совпадению.
        assertEquals(18, engine.findPrevious(text, before = 0)?.start)
    }

    @Test
    fun `битое регулярное выражение не роняет поиск`() {
        val engine = search("(незакрытая скобка", isRegex = true)

        assertNull(engine.regex)
        assertNotNull(engine.error)
        assertTrue(engine.findAll(Rope.of("текст")).toList().isEmpty())
    }

    @Test
    fun `пустой запрос ничего не находит`() {
        val engine = search("")
        assertNull(engine.regex)
        assertNull(engine.error, "пустой запрос это не ошибка")
        assertTrue(engine.findAll(Rope.of("текст")).toList().isEmpty())
    }
}
