package io.github.effectnebula.eide.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.SearchQuery
import io.github.effectnebula.eide.core.project.ProjectMatch
import io.github.effectnebula.eide.core.project.ProjectSearch
import io.github.effectnebula.eide.core.project.ProjectReplace
import io.github.effectnebula.eide.core.project.ProjectSearchResult
import io.github.effectnebula.eide.core.project.ProjectTree
import io.github.effectnebula.eide.core.project.Workspace
import io.github.effectnebula.eide.core.project.replayInBuffers
import io.github.effectnebula.eide.core.project.SearchOutcome
import io.github.effectnebula.eide.ui.theme.Eide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Поиск по всем файлам проекта.
 *
 * Отдельная панель, а не строка поиска в файле: у результата здесь список,
 * а не «дальше — назад», и открывать он должен чужие файлы.
 *
 * Список ленивый (`LazyColumn`), в отличие от редактора: строк тут сотни, а не
 * миллионы, и каждая — отдельный кликабельный узел. Это ровно тот случай, для
 * которого готовый список и сделан, и писать свой было бы упрямством.
 */
@Composable
fun ProjectSearchPanel(
    tree: ProjectTree,
    onOpen: (ProjectMatch) -> Unit,
    modifier: Modifier = Modifier,
    initialPattern: String = "",
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Открытые буферы. Без них показывается только поиск: замена, не знающая
     * про открытые файлы, затирает несохранённые правки — пусть лучше кнопки
     * не будет вовсе, чем будет опасная.
     */
    workspace: Workspace? = null,
) {
    var pattern by remember { mutableStateOf(initialPattern) }
    var caseSensitive by remember { mutableStateOf(false) }
    var isRegex by remember { mutableStateOf(false) }

    var replacement by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }

    /*
     * Первое нажатие взводит, второе заменяет.
     *
     * Замена по проекту для закрытых файлов необратима (откат есть только у
     * открытых буферов), а кнопка стоит вплотную к полю ввода — на телефоне это
     * промах пальцем ценой в весь проект. Диалог сюда не ставится: он требует
     * своего окна, которого в этом интерфейсе пока нет, а второе нажатие стоит
     * ровно столько же внимания.
     */
    var armed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    // Список после замены устарел: найденного там уже нет. Счётчик — ключ
    // поиска, поэтому обход просто повторяется.
    var reloads by remember { mutableStateOf(0) }

    val query = SearchQuery(pattern, isRegex = isRegex, caseSensitive = caseSensitive)
    val result by searchProject(tree, query, dispatcher, reloads)
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().background(Eide.colors.background)) {
        Row(
            Modifier.fillMaxWidth().background(Eide.colors.panel).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Отчёт о замене живёт до следующего запроса: он про то, что уже
            // сделано, и подменять им состояние нового поиска нельзя.
            QueryField(pattern, Modifier.weight(1f)) {
                pattern = it
                report = null
                armed = false
            }
            Toggle("Aa", caseSensitive) { caseSensitive = !caseSensitive }
            Toggle(".*", isRegex) { isRegex = !isRegex }
            if (workspace != null) {
                Toggle("замена", showReplace) { showReplace = !showReplace }
            }
        }

        if (workspace != null && showReplace) {
            val found = result?.matches?.size ?: 0
            Row(
                Modifier.fillMaxWidth().background(Eide.colors.panel)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueryField(replacement, Modifier.weight(1f)) {
                    replacement = it
                    armed = false
                }
                Toggle(
                    label = when {
                        replacing -> "заменяю…"
                        armed -> "точно? ($found)"
                        else -> "заменить всё ($found)"
                    },
                    active = found > 0 && !replacing,
                ) {
                    if (found == 0 || replacing) return@Toggle
                    if (!armed) {
                        armed = true
                        return@Toggle
                    }

                    armed = false
                    replacing = true
                    scope.launch {
                        report = replaceEverywhere(workspace, tree, query, replacement, dispatcher)
                        replacing = false
                        reloads++
                    }
                }
            }
        }

        Status(pattern, result, report)

        LazyColumn(Modifier.fillMaxSize()) {
            items(result?.matches.orEmpty()) { match ->
                MatchRow(match) { onOpen(match) }
            }
        }
    }
}

/**
 * Замена в правильном порядке: сохранить буферы, заменить на диске, повторить
 * в буферах.
 *
 * Порядок держится здесь, а не в ядре, потому что он про потоки: диск читается
 * и пишется в фоне, а `EditorState` можно трогать только в потоке интерфейса
 * (разбор — в `core-project-search.md`). Функция `suspend`, но не помечена
 * никаким диспетчером: зовётся из потока интерфейса и туда же возвращается.
 */
private suspend fun replaceEverywhere(
    workspace: Workspace,
    tree: ProjectTree,
    query: SearchQuery,
    replacement: String,
    dispatcher: CoroutineDispatcher,
): String {
    workspace.saveModified()

    val onDisk = withContext(dispatcher) { ProjectReplace(tree).replace(query, replacement) }
    replayInBuffers(workspace, query, replacement)

    return buildString {
        append("заменено ${onDisk.replaced} в ${onDisk.changed.size} файлах")
        // Файлы, куда замена не пошла, называются вслух: молчание здесь означало
        // бы «всё заменено», а это неправда.
        if (onDisk.problems.isNotEmpty()) {
            append("; не тронуты: ")
            append(onDisk.problems.joinToString { "${it.relativePath} (${it.reason})" })
        }
    }
}

@Composable
private fun QueryField(value: String, modifier: Modifier, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        // Ширина приходит снаружи: поле стоит в Row вместе с кнопками, и
        // `weight` — модификатор этого Row, а не поля. Взятый здесь
        // `fillMaxWidth` вытолкнул кнопки за край экрана, и тесты нашли это
        // раньше, чем я успел посмотреть на снимок.
        modifier = modifier.background(Eide.colors.background).padding(6.dp),
        textStyle = TextStyle(color = Eide.colors.text, fontSize = 13.sp),
        cursorBrush = SolidColor(Eide.colors.caret),
    )
}

/**
 * Считает поиск в фоне, отменяя предыдущий.
 *
 * Пауза своя, а не как у автосохранения. Сначала я взял её оттуда — просто
 * потому, что константа была под рукой, — и панель целую секунду показывала
 * «ищем…». Секунда там про запись на диск, здесь про отзывчивость: человек
 * печатает запрос и ждёт ответа, а не отправляет его.
 *
 * Паузу держит обычный `delay`, а не оператор потока: запрос — ключ
 * `produceState`, и каждая буква перезапускает эту корутину целиком. Отсчёт
 * начинается заново сам собой, а `debounce` поверх этого был бы украшением,
 * которое читателя только запутает.
 *
 * Отмена доходит до самого обхода: `ProjectSearch` спрашивает `isActive` и
 * прекращает работу на ближайшем файле, а не досчитывает впустую. `coroutineContext`
 * в этой лямбде — не тот, что из `kotlin.coroutines`: тот суспендный и в обычной
 * лямбде не компилируется. Здесь он берётся у `CoroutineScope`, который даёт
 * `withContext`, то есть у той самой корутины, что считает поиск.
 */
@Composable
private fun searchProject(
    tree: ProjectTree,
    query: SearchQuery,
    dispatcher: CoroutineDispatcher,
    reloads: Int,
): State<ProjectSearchResult?> = produceState<ProjectSearchResult?>(null, tree, query, dispatcher, reloads) {
    if (query.isEmpty) {
        value = null
        return@produceState
    }

    delay(QUIET_MILLIS)
    value = withContext(dispatcher) {
        ProjectSearch(tree).search(query, isCancelled = { !coroutineContext.isActive })
    }
}

@Composable
private fun Status(pattern: String, result: ProjectSearchResult?, report: String? = null) {
    val text = when {
        report != null -> report
        pattern.isEmpty() -> "поиск по всем файлам проекта"
        result == null -> "ищем…"
        result.matches.isEmpty() -> "нет совпадений, просмотрено ${result.filesScanned}"
        else -> buildString {
            append("совпадений: ${result.matches.size}")
            if (result.outcome == SearchOutcome.LimitReached) append(" (показаны не все)")
            append(", просмотрено ${result.filesScanned}")
            // Пропущенное называется вслух: молча укоротить список — значит соврать.
            if (result.filesSkipped > 0) append(", пропущено ${result.filesSkipped}")
        }
    }

    BasicText(
        text = text,
        modifier = Modifier.fillMaxWidth().background(Eide.colors.border)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp),
    )
}

@Composable
private fun MatchRow(match: ProjectMatch, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        // Путь и номер строки сверху мелким, сама строка — крупным: глазами
        // ищут по содержимому, а не по имени файла.
        BasicText(
            text = "${match.relativePath}:${match.line + 1}",
            style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp),
        )
        BasicText(
            text = match.preview,
            style = TextStyle(color = Eide.colors.text, fontSize = 12.sp, fontFamily = Eide.editorFont),
        )
    }
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) Eide.colors.accent else Eide.colors.border)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        BasicText(label, style = TextStyle(color = Eide.colors.text, fontSize = 12.sp))
    }
}

/**
 * Пауза после последней нажатой клавиши.
 *
 * Четверть секунды: меньше — обход дерева на каждую букву, больше — заметное
 * ожидание. Обход при этом отменяемый, поэтому лишний запуск стоит недорого.
 */
private const val QUIET_MILLIS = 250L
