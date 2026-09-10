package io.github.effectnebula.eide.core.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Нечёткий поиск по командам.
 *
 * Проверяется не «нашлось ли» — с этим справится и подстрока, — а **порядок**.
 * Палитра открывается затем, чтобы нажать Enter на первой строке не глядя;
 * цена ошибки здесь не «команды нет в списке», а «выполнилась не та».
 *
 * **Первая редакция этих тестов не проверяла ничего.** Список команд был
 * подобран так, что под каждый запрос подходила ровно одна команда: порядок
 * из одного элемента верен при любой сортировке. Мутации это и показали —
 * пережили и обнулённые надбавки, и сортировку задом наперёд. Поэтому здесь
 * список настоящий, а каждое утверждение — про две команды, а не про одну.
 */
class FuzzyRankTest {

    private val commands = listOf(
        Command("file.save", "Сохранить файл"),
        Command("file.saveAll", "Сохранить всё"),
        Command("file.close", "Закрыть файл"),
        Command("tabs.closeAll", "Закрыть все вкладки"),
        Command("search.find", "Найти в файле"),
        Command("search.project", "Найти в проекте"),
        Command("search.replace", "Заменить в проекте"),
        Command("run.start", "Запустить"),
        Command("run.stop", "Остановить"),
        Command("view.zoomIn", "Увеличить шрифт"),
        Command("view.zoomOut", "Уменьшить шрифт"),
        Command("fold.all", "Свернуть все блоки"),
        Command("fold.none", "Развернуть все блоки"),
        Command("git.commit", "Зафиксировать изменения"),
        Command("git.branch", "Переключить ветку"),
    )

    private fun titles(query: String) = rankCommands(commands, query).map { it.command.title }

    /** Порядок двух команд между собой: остальные подошедшие не мешают. */
    private fun order(query: String, first: String, second: String) {
        val ranked = titles(query)
        val a = ranked.indexOf(first)
        val b = ranked.indexOf(second)
        assertTrue(a >= 0, "«$first» не нашлась по запросу «$query»")
        assertTrue(b >= 0, "«$second» не нашлась по запросу «$query»")
        assertTrue(a < b, "по запросу «$query» ожидалось «$first» раньше «$second», вышло $ranked")
    }

    @Test
    fun `an empty query keeps the list as it was written`() {
        // Порядок списка осмысленный, а не алфавитный: перемешивать его
        // нулевыми оценками — значит потерять смысл, вложенный в порядок.
        assertEquals(commands.map { it.title }, titles(""))
    }

    @Test
    fun `word starts beat the same letters in the middle of words`() {
        // «зп»: в «Заменить в проекте» обе буквы начинают слова, в «Запустить»
        // вторая стоит внутри слова. Без надбавки за начало слова порядок
        // переворачивается — проверено мутацией.
        order("зп", "Заменить в проекте", "Запустить")
        order("зф", "Закрыть файл", "Зафиксировать изменения")
    }

    @Test
    fun `letters in a row beat letters scattered over word starts`() {
        // «заф» — это начало слова «Зафиксировать» буква в букву. В «Закрыть
        // файл» те же буквы разложены по началам двух слов. Набравший «заф»
        // набирал начало слова, а не аббревиатуру.
        order("заф", "Зафиксировать изменения", "Закрыть файл")
    }

    @Test
    fun `a smaller gap beats a bigger one`() {
        // «рв»: в «Закрыть все вкладки» между буквами четыре знака, в
        // «Переключить ветку» — восемь. Обе команды проигрывают «Развернуть все
        // блоки», где буквы стоят удачнее, — сравниваются здесь именно эти две.
        order("рв", "Закрыть все вкладки", "Переключить ветку")
    }

    @Test
    fun `an earlier match beats a later one`() {
        // Одна буква «в» есть почти везде. Раньше — лучше: в «Найти в файле»
        // это отдельное слово на седьмой позиции, в «Сохранить всё» — на
        // десятой. Без штрафа за позднее начало порядок переворачивается.
        order("в", "Найти в файле", "Сохранить всё")
    }

    @Test
    fun `letters need not be adjacent`() {
        assertEquals("Закрыть все вкладки", titles("звк").first())
    }

    @Test
    fun `case does not matter`() {
        assertEquals(titles("СОХРАНИТЬ"), titles("сохранить"))
    }

    @Test
    fun `commands without the letters drop out`() {
        assertTrue(titles("щщщ").isEmpty())
        assertTrue(titles("сохранить папку").isEmpty())
    }

    @Test
    fun `equal scores keep the order of the source list`() {
        // «уш» ложится на «Увеличить шрифт» и «Уменьшить шрифт» одинаково.
        // Прыгающие местами команды — это нажатие не туда после того, как
        // человек уже прицелился.
        val ranked = titles("уш")
        assertEquals(listOf("Увеличить шрифт", "Уменьшить шрифт"), ranked)
    }

    @Test
    fun `positions point at the letters that matched`() {
        val match = rankCommands(listOf(Command("x", "Закрыть все вкладки")), "звк").single()

        assertEquals(listOf('З', 'в', 'к'), match.positions.map { match.command.title[it] })
        // Начала слов, а не первые попавшиеся буквы. Жадный проход взял бы
        // первое «в» — из «все», позиция 8, — и первое «к» после него: вышло бы
        // [0, 8, 13], то есть буква из середины «вкладки». Динамика выбирает
        // [0, 12, 13]: «в» — начало «вкладки», «к» — следом за ней подряд.
        assertEquals(listOf(0, 12, 13), match.positions)
    }

    @Test
    fun `a query longer than the title matches nothing`() {
        assertTrue(rankCommands(listOf(Command("x", "Ок")), "окей").isEmpty())
    }
}
