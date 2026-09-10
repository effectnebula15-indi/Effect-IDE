package io.github.effectnebula.eide.vcs

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitRepositoryTest {

    private val temp: File = File.createTempFile("eide-git-", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun repo(): GitRepository = GitRepository.init(temp)

    private fun write(name: String, text: String) {
        File(temp, name).writeText(text)
    }

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private fun GitRepository.commitAll(message: String): CommitResult {
        stageAll()
        return commit(message, "Тест", "test@example.org")
    }

    @Test
    fun `fresh repository is clean and on the requested branch`() {
        repo().use { git ->
            assertEquals("main", git.currentBranch())
            assertTrue(git.status().isClean)
        }
    }

    @Test
    fun `untracked file shows up in status`() {
        repo().use { git ->
            write("newfile.txt", "содержимое\n")
            assertEquals(FileStatus.Untracked, git.status().files["newfile.txt"])
        }
    }

    @Test
    fun `status is clean after commit`() {
        repo().use { git ->
            write("a.txt", "раз\n")
            git.commitAll("первый")

            assertTrue(git.status().isClean, "после коммита остались изменения: ${git.status().files}")
        }
    }

    @Test
    fun `edited tracked file is reported as modified`() {
        repo().use { git ->
            write("a.txt", "раз\n")
            git.commitAll("первый")

            write("a.txt", "раз\nдва\n")

            assertEquals(FileStatus.Modified, git.status().files["a.txt"])
        }
    }

    @Test
    fun `deleted file shows up in status`() {
        repo().use { git ->
            write("a.txt", "раз\n")
            git.commitAll("первый")

            File(temp, "a.txt").delete()

            assertEquals(FileStatus.Deleted, git.status().files["a.txt"])
        }
    }

    @Test
    fun `open finds the repository from a nested directory`() {
        repo().use { git ->
            write("a.txt", "раз\n")
            git.commitAll("первый")
        }
        val nested = File(temp, "nested/deeper/here").apply { mkdirs() }

        GitRepository.open(nested).use { found ->
            assertEquals(temp.canonicalFile, found?.workTree?.canonicalFile)
        }
    }

    @Test
    fun `open returns nothing outside a repository`() {
        val plain = File.createTempFile("eide-plain-", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            assertNull(GitRepository.open(plain))
        } finally {
            plain.deleteRecursively()
        }
    }

    @Test
    fun `head returns committed content not the working copy`() {
        repo().use { git ->
            write("a.txt", "закоммичено\n")
            git.commitAll("первый")
            write("a.txt", "ещё не сохранено\n")

            assertEquals("закоммичено\n", git.readFromHead("a.txt")?.decodeToString())
        }
    }

    // --- пометки для гаттера ---------------------------------------------------

    @Test
    fun `added lines are marked as added`() {
        repo().use { git ->
            write("a.txt", "одна\nдве\n")
            git.commitAll("первый")

            val marks = git.gutterMarks("a.txt", "одна\nноль\nдве\n")

            assertEquals(mapOf(1 to LineMark.Added), marks)
        }
    }

    @Test
    fun `changed line is marked modified not add plus delete`() {
        repo().use { git ->
            write("a.txt", "одна\nдве\nтри\n")
            git.commitAll("первый")

            val marks = git.gutterMarks("a.txt", "одна\nДВЕ\nтри\n")

            assertEquals(mapOf(1 to LineMark.Modified), marks)
        }
    }

    @Test
    fun `deletion marks the boundary not the vanished lines`() {
        repo().use { git ->
            write("a.txt", "одна\nдве\nтри\n")
            git.commitAll("первый")

            val marks = git.gutterMarks("a.txt", "одна\nтри\n")

            // Строки «две» больше нет — пометить нечего, поэтому метим строку выше.
            assertEquals(mapOf(0 to LineMark.DeletedBelow), marks)
        }
    }

    @Test
    fun `unchanged file has no gutter marks`() {
        repo().use { git ->
            write("a.txt", "одна\nдве\n")
            git.commitAll("первый")

            assertTrue(git.gutterMarks("a.txt", "одна\nдве\n").isEmpty())
        }
    }

    @Test
    fun `file absent from head has no gutter marks`() {
        repo().use { git ->
            write("a.txt", "раз\n")
            git.commitAll("первый")

            // Новый файл целиком новый: подсвечивать в нём каждую строку бессмысленно.
            assertTrue(git.gutterMarks("newfile.txt", "текст\n").isEmpty())
        }
    }

    @Test
    fun `branches are listed`() {
        repo().use { git ->
            write("a.txt", "раз\n")
            git.commitAll("первый")

            assertEquals(listOf("main"), git.branches())
        }
    }
}
