package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.platform.GraphemeBreaker
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Набор открытых файлов.
 *
 * Главное, что здесь проверяется: переключение между файлами не теряет ни
 * несохранённые правки, ни положение курсора. Потеря такого рода не всплывает
 * сразу и списывается на «я, наверное, сам стёр».
 */
class WorkspaceTest {

    private object Breaker : GraphemeBreaker {
        override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
        override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
    }

    private fun sandbox(): File = Files.createTempDirectory("eide-workspace").toFile().apply {
        deleteOnExit()
    }

    private fun File.write(name: String, text: String): File =
        File(this, name).apply { writeText(text) }

    private fun workspace(root: File) = Workspace(ProjectTree(root), Breaker)

    private fun textOf(open: OpenFile) = open.state.text.let { it.substring(0, it.length) }

    // --- открытие --------------------------------------------------------------

    @Test
    fun `opening a file makes it active`() {
        val root = sandbox()
        val file = root.write("main.py", "print(1)\n")
        val workspace = workspace(root)

        val open = workspace.open(file)

        assertSame(open, workspace.active)
        assertEquals("print(1)\n", textOf(open))
        assertEquals(listOf("main.py"), workspace.files.map { it.name })
    }

    @Test
    fun `reopening returns the same buffer with its edits`() {
        // Иначе несохранённые правки исчезают при возврате к файлу, и человек
        // винит себя.
        val root = sandbox()
        val first = root.write("a.py", "aaa")
        val second = root.write("b.py", "bbb")
        val workspace = workspace(root)

        val opened = workspace.open(first)
        opened.state.type("X")
        workspace.open(second)
        val again = workspace.open(first)

        assertSame(opened, again)
        assertEquals("Xaaa", textOf(again))
    }

    @Test
    fun `the same file by a different path is one buffer`() {
        // "./main.py" и "main.py" — один файл; два буфера над ним означали бы
        // две версии текста и потерю одной из них при записи.
        val root = sandbox()
        val file = root.write("main.py", "text")
        val workspace = workspace(root)

        val direct = workspace.open(file)
        val indirect = workspace.open(File(root, "./main.py"))

        assertSame(direct, indirect)
        assertEquals(1, workspace.files.size)
    }

    @Test
    fun `caret position survives switching between files`() {
        val root = sandbox()
        val first = root.write("a.py", "0123456789")
        val second = root.write("b.py", "bbb")
        val workspace = workspace(root)

        val opened = workspace.open(first)
        repeat(4) { opened.state.move(io.github.effectnebula.eide.core.editor.MoveTo.Right) }
        workspace.open(second)
        workspace.open(first)

        assertEquals(4, opened.state.carets.primary.head, "курсор не пережил переключение")
    }

    // --- изменённость ----------------------------------------------------------

    @Test
    fun `a freshly opened file is not modified`() {
        val root = sandbox()
        val workspace = workspace(root)

        assertFalse(workspace.open(root.write("main.py", "text")).isModified)
        assertFalse(workspace.hasUnsaved)
    }

    @Test
    fun `redoing after an undo marks the file modified again`() {
        val root = sandbox()
        val open = workspace(root).open(root.write("main.py", "text"))

        open.state.type("!")
        open.state.undo()
        open.state.redo()

        assertTrue(open.isModified)
    }

    @Test
    fun `undoing past the saved state marks the file modified`() {
        // Сохранились посреди работы, потом откатились дальше назад: текст
        // на диске и текст на экране разошлись, и это тоже «изменён».
        val root = sandbox()
        val file = root.write("main.py", "text")
        val workspace = workspace(root)
        val open = workspace.open(file)

        open.state.type("!")
        workspace.save(open)
        assertFalse(open.isModified)

        open.state.undo()

        assertTrue(open.isModified)
    }

    @Test
    fun `moving the caret does not mark the file modified`() {
        // Ревизия редактора растёт и от движения курсора. Звёздочка после
        // простого тыка в текст — мелкая ложь, которая быстро надоедает.
        val root = sandbox()
        val open = workspace(root).open(root.write("main.py", "text"))

        open.state.move(io.github.effectnebula.eide.core.editor.MoveTo.Right)

        assertFalse(open.isModified)
    }

    @Test
    fun `editing marks the file modified and saving clears it`() {
        val root = sandbox()
        val file = root.write("main.py", "text")
        val workspace = workspace(root)
        val open = workspace.open(file)

        open.state.type("!")
        assertTrue(open.isModified)
        assertTrue(workspace.hasUnsaved)

        workspace.save(open)

        assertFalse(open.isModified)
        assertEquals("!text", file.readText())
    }

    @Test
    fun `saving writes only what changed`() {
        val root = sandbox()
        val first = root.write("a.py", "aaa")
        root.write("b.py", "bbb")
        val workspace = workspace(root)

        workspace.open(first).state.type("X")
        workspace.open(File(root, "b.py"))

        assertEquals(1, workspace.saveModified(), "записан не только изменённый файл")
        assertEquals(0, workspace.saveModified(), "повторная запись без правок")
    }

    @Test
    fun `undoing back to the original clears the modified mark`() {
        val root = sandbox()
        val open = workspace(root).open(root.write("main.py", "text"))

        open.state.type("!")
        open.state.undo()

        assertFalse(open.isModified, "текст вернулся к исходному, а файл всё ещё «изменён»")
    }

    // --- закрытие --------------------------------------------------------------

    @Test
    fun `closing the active file activates a neighbour`() {
        val root = sandbox()
        val first = root.write("a.py", "aaa")
        val second = root.write("b.py", "bbb")
        val workspace = workspace(root)

        val opened = workspace.open(first)
        workspace.open(second)
        workspace.close(second)

        assertSame(opened, workspace.active, "после закрытия вкладки остался пустой экран")
    }

    @Test
    fun `closing the last file leaves nothing active`() {
        val root = sandbox()
        val file = root.write("main.py", "text")
        val workspace = workspace(root)

        workspace.open(file)
        assertTrue(workspace.close(file))

        assertNull(workspace.active)
        assertEquals(emptyList(), workspace.files)
    }

    @Test
    fun `closing a file that is not open changes nothing`() {
        val root = sandbox()
        val workspace = workspace(root)
        val open = workspace.open(root.write("a.py", "aaa"))

        assertFalse(workspace.close(File(root, "b.py")))

        assertSame(open, workspace.active)
    }

    @Test
    fun `closing a non-active file keeps the active one`() {
        val root = sandbox()
        val first = root.write("a.py", "aaa")
        val second = root.write("b.py", "bbb")
        val workspace = workspace(root)

        workspace.open(first)
        val active = workspace.open(second)
        workspace.close(first)

        assertSame(active, workspace.active)
    }

    // --- только для чтения -----------------------------------------------------

    @Test
    fun `a binary file opens read-only and is never written`() {
        val root = sandbox()
        val file = File(root, "blob.bin").apply { writeBytes(byteArrayOf(1, 0, 2, 0, 3)) }
        val workspace = workspace(root)

        val open = workspace.open(file)
        assertTrue(open.isReadOnly)

        val before = file.readBytes()
        workspace.save(open)

        assertContentEqualsBytes(before, file.readBytes())
    }

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList(), "файл только для чтения был перезаписан")
    }

    // --- активация -------------------------------------------------------------

    @Test
    fun `activating an unopened file reports failure`() {
        val root = sandbox()
        val workspace = workspace(root)
        workspace.open(root.write("a.py", "aaa"))

        assertFalse(workspace.activate(File(root, "b.py")))
    }
}
