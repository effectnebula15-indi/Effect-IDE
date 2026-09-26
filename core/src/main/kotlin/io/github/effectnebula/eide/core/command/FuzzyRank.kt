package io.github.effectnebula.eide.core.command

/**
 * Нечёткий поиск по списку команд.
 *
 * Правило одно: буквы запроса должны встретиться в заголовке в том же порядке,
 * не обязательно подряд. «свпр» находит «Сохранить всё и перезапустить».
 *
 * **Почему не просто `contains`.** Палитра нужна затем, чтобы не помнить точных
 * названий: человек набирает три буквы из головы, а не подстроку из середины.
 * Подстрочный поиск для этого бесполезен ровно в тех случаях, ради которых
 * палитру и открывают.
 *
 * **Почему динамика, а не жадный проход.** Жадный берёт первое вхождение каждой
 * буквы и на этом попадается: в «Закрыть все вкладки» запрос «звк» жадно ляжет
 * на «Закрыть  все   вкладки» — З, потом «в» из «все», потом «к» из «вкладки», —
 * то есть на середины слов, хотя рядом лежит разбор по началам слов. Разница
 * видна не в том, найдётся ли команда, а в том, какая окажется первой; а первая
 * — это та, которую нажмут не глядя.
 *
 * Цена: O(длина запроса × длина заголовка) на команду. При двух десятках команд
 * и запросе в пять букв это тысячи операций на нажатие — незаметно. На списке в
 * тысячи строк (файлы проекта) так делать уже нельзя, и это причина, по которой
 * функция живёт здесь, а не объявлена универсальным поиском по чему угодно.
 *
 * **Констант в оценке две, и обе проверены замером, а не вкусом.** Первая
 * редакция содержала пять; каждую по очереди обнулили и посмотрели, изменится ли
 * порядок на полутора десятках настоящих запросов к настоящему списку команд.
 * Три не изменили ничего:
 *
 * - надбавка за длину совпадения не могла влиять в принципе — у всех кандидатов
 *   на один запрос совпавших букв поровну, то есть это была общая добавка ко
 *   всем оценкам сразу;
 * - отдельная плата за сам факт разрыва и за каждый пропущенный знак в нём
 *   свелась к одному «по единице за пропущенный знак» без единой перестановки;
 * - штраф за позднее начало оказался тем же самым «минус индекс».
 *
 * Оставшиеся две перестановки дают: без надбавки за начало слова «зп» поднимает
 * «Запустить» над «Заменить в проекте», без надбавки за буквы подряд «заф»
 * поднимает «Закрыть файл» над «Зафиксировать изменения». Обе перестановки
 * закреплены тестами.
 */
fun rankCommands(commands: List<Command>, query: String): List<CommandMatch> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        // Пустой запрос — весь список в исходном порядке: он осмысленный,
        // а не алфавитный, и перемешивать его нулевыми оценками незачем.
        return commands.map { CommandMatch(it, 0, emptyList()) }
    }

    return commands
        .mapNotNull { command -> matchCommand(command, trimmed) }
        // sortedByDescending устойчив: при равных оценках порядок исходного
        // списка сохраняется. Это не мелочь — иначе одинаково подходящие
        // команды прыгали бы местами между нажатиями.
        .sortedByDescending { it.score }
}

private fun matchCommand(command: Command, query: String): CommandMatch? {
    val title = command.title
    if (query.length > title.length) return null

    val q = query.lowercase()
    val t = title.lowercase()

    // Дешёвая отсечка: если буквы не встречаются даже подпоследовательностью,
    // считать таблицу незачем.
    if (!isSubsequence(q, t)) return null

    // score[j] — лучшая оценка разбора первых i букв запроса, где последняя
    // легла ровно в позицию j заголовка. NONE означает «так не разобрать».
    var previous = IntArray(title.length) { NONE }
    val parents = ArrayList<IntArray>(query.length)

    for (i in q.indices) {
        val current = IntArray(title.length) { NONE }
        val parent = IntArray(title.length) { -1 }

        for (j in i until title.length) {
            if (q[i] != t[j]) continue

            if (i == 0) {
                current[j] = bonusAt(title, j) - j
                continue
            }

            var best = NONE
            var bestFrom = -1
            for (k in i - 1 until j) {
                val before = previous[k]
                if (before == NONE) continue

                // Разрыв стоит по единице за пропущенный знак, и это вся плата
                // за него: отдельная плата за сам факт разрыва ничего не меняла.
                val gap = j - k - 1
                val score = before + when (gap) {
                    0 -> CONSECUTIVE
                    else -> bonusAt(title, j) - gap
                }
                if (score > best) {
                    best = score
                    bestFrom = k
                }
            }
            current[j] = best
            parent[j] = bestFrom
        }

        parents += parent
        previous = current
    }

    var bestEnd = -1
    for (j in title.indices) if (previous[j] != NONE && (bestEnd < 0 || previous[j] > previous[bestEnd])) {
        bestEnd = j
    }
    if (bestEnd < 0) return null

    val positions = ArrayList<Int>(q.length)
    var j = bestEnd
    for (i in q.indices.reversed()) {
        positions += j
        if (i == 0) break
        j = parents[i][j]
        if (j < 0) return null
    }
    positions.reverse()

    return CommandMatch(command, previous[bestEnd], positions)
}

private fun isSubsequence(query: String, title: String): Boolean {
    var index = 0
    for (char in title) {
        if (char == query[index]) {
            index++
            if (index == query.length) return true
        }
    }
    return false
}

/**
 * Надбавка за начало слова.
 *
 * Начало — это первый знак, знак после разделителя и заглавная после строчной
 * (`openFile`). Заглавные буквы кириллицы `isUpperCase` различает так же, как
 * латинские, поэтому отдельной ветки для русского здесь не нужно.
 */
private fun bonusAt(title: String, index: Int): Int {
    if (index == 0) return WORD_START
    val previous = title[index - 1]
    if (!previous.isLetterOrDigit()) return WORD_START
    if (previous.isLowerCase() && title[index].isUpperCase()) return WORD_START
    return 0
}

private const val NONE = Int.MIN_VALUE

/**
 * За букву сразу после предыдущей: подряд лучше, чем вразбивку.
 *
 * Больше [WORD_START] — потому что запрос, совпавший с началом заголовка
 * буква в букву, человек и набирал как начало заголовка.
 */
private const val CONSECUTIVE = 14

/** За начало слова: «свпр» должно ложиться на первые буквы слов. */
private const val WORD_START = 12
