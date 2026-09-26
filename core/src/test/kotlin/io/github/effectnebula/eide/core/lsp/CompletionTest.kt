package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.text.Replacement
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Что делает выбор варианта автодополнения.
 *
 * Сервер знает, что предложить; куда вставить — решаем мы, и ошибка здесь
 * видна сразу: вставка съедает лишнее или оставляет набранное рядом с
 * вставленным (`papath`).
 */
class CompletionTest {

    private fun item(label: String, insert: String = label, replaces: LspRange? = null, sort: String? = null) =
        CompletionItem(label, insert, detail = null, replaces = replaces, sortText = sort)

    @Test
    fun `the typed prefix is replaced, not appended to`() {
        // Ровно так выглядит поломка: «os.pa» + «path» = «os.papath».
        val text = Rope.of("os.pa")

        assertEquals(Replacement(3, 5, "path"), item("path").edit(text, 5, PositionEncoding.Utf16))
    }

    @Test
    fun `the word starts after a dot, not at the line start`() {
        val text = Rope.of("x = os.pa")

        assertEquals(7, completionWordStart(text, text.length))
        assertEquals("pa", completionPrefix(text, text.length))
    }

    @Test
    fun `cyrillic names are words too`() {
        // `значение_x` — законное имя в Python, и начинающий пишет именно так.
        val text = Rope.of("s = знач")

        assertEquals("знач", completionPrefix(text, text.length))
    }

    @Test
    fun `the word does not cross into the previous line`() {
        val text = Rope.of("abc\ndef")

        assertEquals(4, completionWordStart(text, 4))
        assertEquals("", completionPrefix(text, 4))
    }

    @Test
    fun `an empty prefix inserts at the caret`() {
        val text = Rope.of("os.")

        assertEquals(Replacement(3, 3, "path"), item("path").edit(text, 3, PositionEncoding.Utf16))
    }

    @Test
    fun `a server range is replaced up to the caret, not up to where it ended`() {
        // Участок сервер считал, пока человек ещё не допечатал: `pa` → `pat`.
        // Правый край участка остался позади курсора, и вставка по нему
        // оставила бы лишнюю `t`.
        val text = Rope.of("os.pat")
        val range = LspRange(LspPosition(0, 3), LspPosition(0, 5))

        assertEquals(Replacement(3, 6, "path"), item("path", replaces = range).edit(text, 6, PositionEncoding.Utf16))
    }

    @Test
    fun `a server range is read in the negotiated encoding`() {
        // Участок после эмодзи: в UTF-32 его начало на единицу левее.
        val text = Rope.of("😀pa")
        val range = LspRange(LspPosition(0, 1), LspPosition(0, 3))

        assertEquals(Replacement(2, 4, "path"), item("path", replaces = range).edit(text, 4, PositionEncoding.Utf32))
    }

    @Test
    fun `typing further narrows the list without asking the server`() {
        val items = listOf(item("path"), item("pardir"), item("pathsep"))

        assertEquals(listOf("path", "pathsep"), items.matching("pat").map { it.label })
        assertEquals(listOf("path", "pardir", "pathsep"), items.matching("").map { it.label })
    }

    @Test
    fun `narrowing ignores case`() {
        assertEquals(listOf("path"), listOf(item("path")).matching("PA").map { it.label })
    }

    @Test
    fun `filter text wins over the label`() {
        val odd = CompletionItem("путь (os)", "path", null, null, filterText = "path")

        assertEquals(listOf(odd), listOf(odd).matching("pa"))
    }

    @Test
    fun `sort text orders the list, the label breaks ties`() {
        val items = listOf(item("zeta", sort = "a"), item("alpha", sort = "b"), item("beta", sort = "a"))

        assertEquals(listOf("beta", "zeta", "alpha"), items.sortedWith(COMPLETION_ORDER).map { it.label })
    }

    @Test
    fun `what matches the typed case comes first`() {
        // Так было на живом pylsp: `sortText` поставил `PathLike` перед `path`,
        // потому что `P` меньше `p`. Enter вставляет первый вариант — и на
        // строчное `pa` вставлялось заглавное.
        val items = listOf(item("PathLike"), item("pardir"), item("path"))

        assertEquals(listOf("pardir", "path", "PathLike"), items.matching("pa").map { it.label })
        assertEquals(listOf("PathLike", "pardir", "path"), items.matching("Pa").map { it.label })
    }

}
