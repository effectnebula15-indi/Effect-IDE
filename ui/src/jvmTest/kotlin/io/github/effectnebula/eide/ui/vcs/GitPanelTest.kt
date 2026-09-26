package io.github.effectnebula.eide.ui.vcs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Панель git.
 *
 * Проверяется главным образом то, чего панель делать **не** должна: фиксировать
 * без сообщения, без автора и без изменений. Коммит — запись в историю, которую
 * потом никто не перепишет; лучше не дать сделать, чем сделать не то.
 */
@OptIn(ExperimentalTestApi::class)
class GitPanelTest {

    private val changes = listOf(
        ChangedFile("core/src/main/kotlin/Rope.kt", ChangeKind.Modified),
        ChangedFile("docs/new.md", ChangeKind.Untracked),
    )

    @Test
    fun `commit hands over the message`() = runComposeUiTest {
        var committed: String? = null
        setContent {
            GitPanel(
                branch = "main",
                files = changes,
                identity = "Тест <test@example.org>",
                busy = false,
                notice = null,
                onRefresh = {},
                onCommit = { committed = it },
                onOpen = {},
            )
        }
        waitForIdle()

        onNode(isEditable()).performTextInput("  правка ядра  ")
        waitForIdle()
        onNodeWithText("зафиксировать (2)").performClick()
        waitForIdle()

        // Пробелы по краям срезаны: сообщение коммита с ними живёт в истории вечно.
        assertEquals("правка ядра", committed)
    }

    @Test
    fun `an empty message does not commit and says why`() = runComposeUiTest {
        var committed: String? = null
        setContent {
            GitPanel("main", changes, "Тест <test@example.org>", false, null, {}, { committed = it }, {})
        }
        waitForIdle()

        onNodeWithText("зафиксировать (2)").performClick()
        waitForIdle()

        assertNull(committed)
        onNodeWithText("нужно сообщение коммита").assertExists()
    }

    @Test
    fun `without an author there is no commit`() = runComposeUiTest {
        // Коммит от выдуманного автора видно в истории годами. Панель обязана
        // сказать, чего не хватает, а не просто не сработать.
        var committed: String? = null
        setContent {
            GitPanel("main", changes, null, false, null, {}, { committed = it }, {})
        }
        waitForIdle()

        onNode(isEditable()).performTextInput("правка")
        waitForIdle()
        onNodeWithText("зафиксировать (2)").performClick()
        waitForIdle()

        assertNull(committed)
        onNodeWithText("не задан автор: git config user.name и user.email").assertExists()
    }

    @Test
    fun `nothing changed means nothing to commit`() = runComposeUiTest {
        var committed: String? = null
        setContent {
            GitPanel("main", emptyList(), "Тест <test@example.org>", false, null, {}, { committed = it }, {})
        }
        waitForIdle()

        onNode(isEditable()).performTextInput("правка")
        waitForIdle()
        onNodeWithText("зафиксировать (0)").performClick()
        waitForIdle()

        assertNull(committed)
        onNodeWithText("нечего фиксировать").assertExists()
    }

    @Test
    fun `a commit in flight does not start a second one`() = runComposeUiTest {
        var commits = 0
        setContent {
            GitPanel("main", changes, "Тест <test@example.org>", busy = true, notice = null, {}, { commits++ }, {})
        }
        waitForIdle()

        onNode(isEditable()).performTextInput("правка")
        waitForIdle()
        onNodeWithText("зафиксировать (2)").performClick()
        onNodeWithText("зафиксировать (2)").performClick()
        waitForIdle()

        assertEquals(0, commits)
    }

    @Test
    fun `the message is cleared after a commit`() = runComposeUiTest {
        // Иначе следующий коммит уедет с прежним текстом — а заметить это можно
        // уже только в истории.
        setContent {
            GitPanel("main", changes, "Тест <test@example.org>", false, null, {}, {}, {})
        }
        waitForIdle()

        onNode(isEditable()).performTextInput("правка ядра")
        waitForIdle()
        onNodeWithText("зафиксировать (2)").performClick()
        waitForIdle()

        onNodeWithText("правка ядра").assertDoesNotExist()
        onNodeWithText("сообщение коммита").assertExists()
    }

    @Test
    fun `files are shown with their status letters and paths`() = runComposeUiTest {
        setContent {
            GitPanel("main", changes, "Тест <test@example.org>", false, null, {}, {}, {})
        }
        waitForIdle()

        onNodeWithText("ветка main").assertExists()
        onNodeWithText("core/src/main/kotlin/Rope.kt").assertExists()
        onNodeWithText("M").assertExists()
        onNodeWithText("?").assertExists()
    }

    @Test
    fun `a tap on a file opens it`() = runComposeUiTest {
        var opened: ChangedFile? = null
        setContent {
            GitPanel("main", changes, "Тест <test@example.org>", false, null, {}, {}, { opened = it })
        }
        waitForIdle()

        onNodeWithText("docs/new.md").performClick()
        waitForIdle()

        assertEquals("docs/new.md", opened?.path)
    }

    @Test
    fun `a notice beats the blocker text`() = runComposeUiTest {
        // Результат последнего коммита важнее подсказки «нечего фиксировать»:
        // именно он отвечает на вопрос «получилось ли».
        setContent {
            GitPanel("main", emptyList(), "Тест <test@example.org>", false, "коммит a1b2c3d", {}, {}, {})
        }
        waitForIdle()

        onNodeWithText("коммит a1b2c3d").assertExists()
        onNodeWithText("нечего фиксировать").assertDoesNotExist()
    }
}
