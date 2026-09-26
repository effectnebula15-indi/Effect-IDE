package io.github.effectnebula.eide.ui.search

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.effectnebula.eide.core.project.ProjectMatch
import io.github.effectnebula.eide.core.project.ProjectTree
import io.github.effectnebula.eide.core.project.Workspace
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Панель поиска по проекту.
 *
 * Проверяется дорога от обхода дерева до экрана: сам обход покрыт тестами
 * `:core`. Поиск считается на `Dispatchers.Unconfined` — тогда он исполняется
 * прямо в корутине теста, и ждать чужого потока не приходится.
 *
 * **Чего этот тест не ловит: слишком долгую паузу.** Часы в `runComposeUiTest`
 * виртуальные, и `delay` любой длины проходит мгновенно — секунда ожидания,
 * которая была здесь в первой редакции, тестом выглядела бы так же, как
 * четверть секунды. Увидеть её удалось только на снимке экрана.
 *
 * Имена файлов латиницей: причина та же, что в `ProjectSearchTest` — ловушка
 * `sun.jnu.encoding`. Содержимое кириллическое, оно едет байтами UTF-8.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectSearchPanelTest {

    private object Breaker : GraphemeBreaker {
        override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
        override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
    }

    private fun project(vararg files: Pair<String, String>): ProjectTree {
        val root = Files.createTempDirectory("eide-panel").toFile().apply { deleteOnExit() }
        for ((path, text) in files) {
            val file = File(root, path)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        return ProjectTree(root)
    }

    /**
     * Дожидается ответа поиска.
     *
     * Часы в тесте виртуальные и сами не идут: `waitForIdle` докручивает
     * пересборку, но паузу перед поиском не проматывает — ожидание для него
     * не работа. Поэтому время двигается руками, с запасом над паузой.
     */
    private fun ComposeUiTest.awaitSearch() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    @Test
    fun `matches reach the screen`() = runComposeUiTest {
        val tree = project(
            "first.py" to "иголка в первом файле\nсено\n",
            "nested/second.py" to "сено\nиголка во втором\n",
        )

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
            )
        }
        awaitSearch()

        onNodeWithText("иголка в первом файле").assertExists()
        onNodeWithText("иголка во втором").assertExists()
        // Номер строки с единицей, а не с нуля, как в ядре.
        onNodeWithText("nested/second.py:2").assertExists()
        onNodeWithText("совпадений: 2, просмотрено 2").assertExists()
    }

    @Test
    fun `an empty query searches nothing`() = runComposeUiTest {
        val tree = project("first.py" to "иголка\n")

        setContent {
            ProjectSearchPanel(tree = tree, onOpen = {}, dispatcher = Dispatchers.Unconfined)
        }
        waitForIdle()

        onNodeWithText("поиск по всем файлам проекта").assertExists()
        onNodeWithText("иголка").assertDoesNotExist()
    }

    @Test
    fun `clicking a row hands over the match`() = runComposeUiTest {
        val tree = project("first.py" to "первая\nиголка\n")
        var opened: ProjectMatch? = null

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = { opened = it },
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
            )
        }
        awaitSearch()

        // По пути, а не по тексту: «иголка» стоит ещё и в строке запроса.
        onNodeWithText("first.py:2").performClick()
        waitForIdle()

        assertEquals(1, opened?.line)
        assertEquals("first.py", opened?.relativePath)
    }

    @Test
    fun `skipped files are named in the status line`() = runComposeUiTest {
        val tree = project("first.py" to "иголка\n")
        // Нулевой байт — признак двоичного файла: такой поиск не читает.
        File(tree.root, "blob.bin").writeBytes(byteArrayOf(0, 1, 2))

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
            )
        }
        awaitSearch()

        // Пропущенное сказано вслух: иначе «совпадений: 1» читается как
        // «во всём проекте одно», а на деле часть проекта не смотрели.
        onNodeWithText("совпадений: 1, просмотрено 1, пропущено 1").assertExists()
    }

    @Test
    fun `case sensitivity is a toggle, not a guess`() = runComposeUiTest {
        val tree = project("first.py" to "Иголка большая\nиголка малая\n")

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
            )
        }
        awaitSearch()

        // По умолчанию регистр не важен — находятся обе строки.
        onNodeWithText("совпадений: 2, просмотрено 1").assertExists()

        onNodeWithText("Aa").performClick()
        awaitSearch()

        onNodeWithText("совпадений: 1, просмотрено 1").assertExists()
        onNodeWithText("иголка малая").assertExists()
        onNodeWithText("Иголка большая").assertDoesNotExist()
    }

    // --- замена ----------------------------------------------------------------

    @Test
    fun `replace writes the files and says how many`() = runComposeUiTest {
        val tree = project(
            "first.py" to "иголка\n",
            "nested/second.py" to "иголка и ещё иголка\n",
        )
        val workspace = Workspace(tree, Breaker)

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
                workspace = workspace,
            )
        }
        awaitSearch()

        onNodeWithText("замена").performClick()
        waitForIdle()
        onNodeWithText("заменить всё (3)").performClick()
        waitForIdle()
        onNodeWithText("точно? (3)").performClick()
        awaitSearch()

        onNodeWithText("заменено 3 в 2 файлах").assertExists()
        assertEquals("\n", File(tree.root, "first.py").readText(), "замена на пустое — тоже замена")
    }

    @Test
    fun `replace does not lose unsaved edits in an open buffer`() = runComposeUiTest {
        // Ровно так это ломается в редакторах, которые правят диск напрямую:
        // человек набрал текст, не сохранил, нажал «заменить всё» — и правок нет.
        val tree = project("main.py" to "иголка\n")
        val workspace = Workspace(tree, Breaker)
        val open = workspace.open(File(tree.root, "main.py"))
        open.state.type("не сохранено\n")

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
                workspace = workspace,
            )
        }
        awaitSearch()

        onNodeWithText("замена").performClick()
        waitForIdle()
        onNodeWithText("заменить всё (1)").performClick()
        waitForIdle()
        onNodeWithText("точно? (1)").performClick()
        awaitSearch()

        val text = open.state.text.let { it.substring(0, it.length) }
        assertEquals("не сохранено\n\n", text)
        assertEquals(text, File(tree.root, "main.py").readText())
    }

    @Test
    fun `without a workspace there is no replace at all`() = runComposeUiTest {
        // Замена, не знающая про открытые файлы, затирает несохранённое. Пусть
        // лучше кнопки не будет вовсе, чем будет опасная.
        val tree = project("main.py" to "иголка\n")

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
            )
        }
        awaitSearch()

        onNodeWithText("замена").assertDoesNotExist()
    }


    @Test
    fun `the report counts what was replaced, not what the stale disk had`() = runComposeUiTest {
        // Буфер убрал одно из двух совпадений и не сохранён. Если не записать
        // буферы перед заменой, замена пройдёт по устаревшему диску, насчитает
        // два, а буфер потом ляжет сверху с одним — и отчёт соврёт.
        val tree = project("main.py" to "иголка\nиголка\n")
        val workspace = Workspace(tree, Breaker)
        val open = workspace.open(File(tree.root, "main.py"))
        open.state.selectAll()
        open.state.type("иголка\n")

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
                workspace = workspace,
            )
        }
        awaitSearch()

        onNodeWithText("замена").performClick()
        waitForIdle()
        onNodeWithText("заменить всё (2)").performClick()
        waitForIdle()
        onNodeWithText("точно? (2)").performClick()
        awaitSearch()

        onNodeWithText("заменено 1 в 1 файлах").assertExists()
        assertEquals("\n", File(tree.root, "main.py").readText())
    }

    @Test
    fun `the list is searched again after a replacement`() = runComposeUiTest {
        // Список после замены показывает то, чего в файлах уже нет: ткнуть в
        // такую строку — значит открыть файл на строке, где ничего не найдено.
        val tree = project("main.py" to "иголка\n")
        val workspace = Workspace(tree, Breaker)

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
                workspace = workspace,
            )
        }
        awaitSearch()
        onNodeWithText("main.py:1").assertExists()

        onNodeWithText("замена").performClick()
        waitForIdle()
        onNodeWithText("заменить всё (1)").performClick()
        waitForIdle()
        onNodeWithText("точно? (1)").performClick()
        awaitSearch()

        onNodeWithText("main.py:1").assertDoesNotExist()
    }


    @Test
    fun `one press does not replace anything`() = runComposeUiTest {
        // Кнопка стоит вплотную к полю ввода, а замена по проекту необратима
        // для закрытых файлов. Первое нажатие обязано только взвести.
        val tree = project("main.py" to "иголка\n")
        val workspace = Workspace(tree, Breaker)

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
                workspace = workspace,
            )
        }
        awaitSearch()

        onNodeWithText("замена").performClick()
        waitForIdle()
        onNodeWithText("заменить всё (1)").performClick()
        awaitSearch()

        assertEquals("иголка\n", File(tree.root, "main.py").readText())
        onNodeWithText("точно? (1)").assertExists()
    }

    @Test
    fun `editing the replacement disarms the button`() = runComposeUiTest {
        // Взвели, потом передумали и дописали текст замены — второе нажатие
        // не должно оказаться подтверждением прежнего намерения.
        val tree = project("main.py" to "иголка\n")
        val workspace = Workspace(tree, Breaker)

        setContent {
            ProjectSearchPanel(
                tree = tree,
                onOpen = {},
                initialPattern = "иголка",
                dispatcher = Dispatchers.Unconfined,
                workspace = workspace,
            )
        }
        awaitSearch()

        onNodeWithText("замена").performClick()
        waitForIdle()
        onNodeWithText("заменить всё (1)").performClick()
        waitForIdle()

        onAllNodes(isEditable())[1].performTextInput("гвоздь")
        waitForIdle()

        onNodeWithText("заменить всё (1)").assertExists()
        assertEquals("иголка\n", File(tree.root, "main.py").readText())
    }

}
