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

    @Test
    fun `undoing a typed word reports every edit it applied`() {
        // Откат группы — три удаления, а не одно. Подписчик, переносящий офсеты
        // (пометки ошибок), должен увидеть все три: иначе он сдвинет всё на одну
        // букву вместо трёх.
        val document = Document(Rope.of("xy"))
        for ((index, letter) in "abc".withIndex()) {
            document.apply(EditTransaction.insert(1 + index, letter.toString()), EditKind.Typing, 0)
        }
        assertEquals("xabcy", document.text.toString())

        val change = document.undo()!!

        assertEquals(3, change.edits.size)
        assertEquals(1, change.mapOffset(4), "офсет перед «y» должен вернуться на место")
        assertEquals(1, change.mapOffset(2), "офсет внутри удалённого — на место удаления")
        assertEquals(0, change.mapOffset(0))
        assertEquals(change.versionBefore + 1, change.versionAfter)
    }

    @Test
    fun `the earliest change of an undone deletion is found across lines`() {
        // Стёрли «x», перенос строки и кавычку. Откат возвращает их в обратном
        // порядке, и последней применяется вставка «x» строкой ниже. Самое раннее
        // изменение — кавычка в первой строке, а не место последней правки.
        val document = Document(Rope.of("s = \"\"\"\nxbody"))
        document.apply(EditTransaction.delete(8, 9), EditKind.Deleting, 0)
        document.apply(EditTransaction.delete(7, 8), EditKind.Deleting, 0)
        document.apply(EditTransaction.delete(6, 7), EditKind.Deleting, 0)
        assertEquals("s = \"\"body", document.text.toString())

        val change = document.undo()!!

        assertEquals("s = \"\"\"\nxbody", document.text.toString())
        assertEquals(8, change.edits.last().replacements.first().start)
        assertEquals(6, change.firstChangedOffset())
    }

    @Test
    fun `a plain edit is a one-edit change`() {
        val document = Document(Rope.of("abc"))
        val change = document.apply(EditTransaction.replace(1, 2, "XY"))!!

        assertEquals(1, change.edits.size)
        assertEquals(1, change.firstChangedOffset())
        assertEquals(4, change.mapOffset(3))
    }
}
