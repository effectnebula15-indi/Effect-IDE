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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.SearchQuery
import io.github.effectnebula.eide.core.project.ProjectMatch
import io.github.effectnebula.eide.core.project.ProjectSearch
import io.github.effectnebula.eide.core.project.ProjectSearchResult
import io.github.effectnebula.eide.core.project.ProjectTree
import io.github.effectnebula.eide.core.project.SearchOutcome
import io.github.effectnebula.eide.ui.theme.Eide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
) {
    var pattern by remember { mutableStateOf(initialPattern) }
    var caseSensitive by remember { mutableStateOf(false) }
    var isRegex by remember { mutableStateOf(false) }

    val query = SearchQuery(pattern, isRegex = isRegex, caseSensitive = caseSensitive)
    val result by searchProject(tree, query, dispatcher)

    Column(modifier.fillMaxSize().background(Eide.colors.background)) {
        Row(
            Modifier.fillMaxWidth().background(Eide.colors.panel).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = pattern,
                onValueChange = { pattern = it },
                singleLine = true,
                modifier = Modifier.weight(1f).background(Eide.colors.background).padding(6.dp),
                textStyle = TextStyle(color = Eide.colors.text, fontSize = 13.sp),
                cursorBrush = SolidColor(Eide.colors.caret),
            )
            Toggle("Aa", caseSensitive) { caseSensitive = !caseSensitive }
            Toggle(".*", isRegex) { isRegex = !isRegex }
        }

        Status(pattern, result)

        LazyColumn(Modifier.fillMaxSize()) {
            items(result?.matches.orEmpty()) { match ->
                MatchRow(match) { onOpen(match) }
            }
        }
    }
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
): State<ProjectSearchResult?> = produceState<ProjectSearchResult?>(null, tree, query, dispatcher) {
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
private fun Status(pattern: String, result: ProjectSearchResult?) {
    val text = when {
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
