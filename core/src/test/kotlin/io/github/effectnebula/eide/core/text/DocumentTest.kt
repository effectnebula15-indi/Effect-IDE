package io.github.effectnebula.eide.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTest {

    @Test
    fun `edit changes text and version`() {
        val document = Document(Rope.of("привет"))
        val change = document.apply(EditTransaction.insert(6, ", мир"))

        assertEquals("привет, мир", document.text.toString())
        assertEquals(1, document.version)
        assertEquals(0, change?.versionBefore)
    }

    @Test
    fun `empty edit does nothing`() {
        val document = Document(Rope.of("текст"))
        assertNull(document.apply(EditTransaction(emptyList())))
        assertEquals(0, document.version)
        assertFalse(document.canUndo)
    }

    @Test
    fun `typed word undoes in one step`() {
        val document = Document()
        var at = 0L
        for (letter in "привет") {
            document.apply(EditTransaction.insert(document.text.length, letter.toString()), EditKind.Typing, at)
            at += 50
        }
        assertEquals("привет", document.text.toString())

        document.undo()

        assertEquals("", document.text.toString(), "набор распался на отдельные шаги undo")
        assertFalse(document.canUndo)
    }

    @Test
    fun `pause in typing breaks the group`() {
        val document = Document()
        document.apply(EditTransaction.insert(0, "abc"), EditKind.Typing, 0)
        // Пауза длиннее окна группировки — это уже другое действие.
        document.apply(EditTransaction.insert(3, "def"), EditKind.Typing, Document.GROUPING_WINDOW_MS + 1)

        document.undo()
        assertEquals("abc", document.text.toString())

        document.undo()
        assertEquals("", document.text.toString())
    }

    @Test
    fun `typing elsewhere starts a new group`() {
        val document = Document(Rope.of("aaaa bbbb"))
        document.apply(EditTransaction.insert(4, "X"), EditKind.Typing, 0)
        // Курсор переехал: та же секунда, тот же характер, но другое место.
        document.apply(EditTransaction.insert(0, "Y"), EditKind.Typing, 10)

        document.undo()
        assertEquals("aaaaX bbbb", document.text.toString(), "правки в разных местах склеились")
    }

    @Test
    fun `paste never groups`() {
        val document = Document()
        document.apply(EditTransaction.insert(0, "один"), EditKind.Other, 0)
        document.apply(EditTransaction.insert(4, "два"), EditKind.Other, 10)

        document.undo()
        assertEquals("один", document.text.toString())
    }

    @Test
    fun `undo and redo round trip`() {
        val document = Document(Rope.of("начало"))
        document.apply(EditTransaction.insert(6, " и продолжение"))
        val afterEdit = document.text.toString()

        assertTrue(document.undo() != null)
        assertEquals("начало", document.text.toString())
        assertTrue(document.canRedo)

        assertTrue(document.redo() != null)
        assertEquals(afterEdit, document.text.toString())
    }

    @Test
    fun `new edit clears redo history`() {
        val document = Document(Rope.of("текст"))
        document.apply(EditTransaction.insert(5, " один"))
        document.undo()
        assertTrue(document.canRedo)

        document.apply(EditTransaction.insert(5, " два"))

        assertFalse(document.canRedo, "redo пережил новую правку и теперь ведёт в несуществующее состояние")
        assertEquals("текст два", document.text.toString())
    }

    @Test
    fun `multi caret edit undoes as a whole`() {
        val document = Document(Rope.of("aaaaabbbbbccccc"))
        document.apply(
            EditTransaction.of(
                Replacement(0, 0, ">"),
                Replacement(5, 5, ">"),
                Replacement(10, 10, ">"),
            )
        )
        assertEquals(">aaaaa>bbbbb>ccccc", document.text.toString())

        document.undo()

        assertEquals("aaaaabbbbbccccc", document.text.toString())
    }

    @Test
    fun `undo on empty history is safe`() {
        val document = Document(Rope.of("текст"))
        assertNull(document.undo())
        assertNull(document.redo())
        assertEquals("текст", document.text.toString())
    }

    @Test
    fun `long random history unwinds to the original text`() {
        val document = Document(Rope.of("старт"))
        val random = kotlin.random.Random(11)
        var at = 0L
        var applied = 0

        repeat(200) {
            val length = document.text.length
            val start = random.nextInt(0, length + 1)
            val end = (start + random.nextInt(0, 3)).coerceAtMost(length)
            val kind = if (random.nextBoolean()) EditKind.Typing else EditKind.Other
            at += random.nextInt(0, 1000)
            if (document.apply(EditTransaction.replace(start, end, "x".repeat(random.nextInt(0, 3))), kind, at) != null) {
                applied++
            }
        }

        assertTrue(applied > 0)
        while (document.canUndo) document.undo()

        assertEquals("старт", document.text.toString(), "история не свернулась обратно в исходный текст")
    }
}
