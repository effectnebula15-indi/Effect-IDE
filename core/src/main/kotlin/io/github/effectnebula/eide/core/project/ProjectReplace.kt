package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.core.editor.SearchQuery
import io.github.effectnebula.eide.core.editor.TextSearch
import java.io.File

/** Файл, в котором что-то заменено. */
data class ReplacedFile(
    val file: File,
    val relativePath: String,
    val count: Int,
    /** Правка легла в открытый буфер — значит её можно откатить в редакторе. */
    val inBuffer: Boolean,
)

/** Файл, в который замена не пошла, и почему. */
data class UnchangedFile(val file: File, val relativePath: String, val reason: String)

data class ProjectReplaceResult(
    val changed: List<ReplacedFile>,
    val problems: List<UnchangedFile>,
    val outcome: SearchOutcome,
    val filesScanned: Int,
    val filesSkipped: Int,
) {
    val replaced: Int get() = changed.sumOf { it.count }
}

/**
 * Замена по всем файлам проекта — на диске.
 *
 * Обход тот же, что у поиска (`ProjectFiles.kt`), и это не экономия строк:
 * замена, зашедшая в файл, куда не заходил поиск, — правка, которую человек не
 * заказывал и не увидит.
 *
 * **Открытых буферов эта половина не касается, и это не забывчивость.**
 * `EditorState` не потокобезопасен, а замену по проекту хочется вести в фоне:
 * править буфер оттуда — гонка с отрисовкой. Поэтому порядок такой, и другого
 * нет: в потоке интерфейса сохранить буферы (`Workspace.saveModified`), в фоне
 * заменить на диске вот этим классом, вернуться в поток интерфейса и повторить
 * ту же замену в буферах через [replayInBuffers]. Тогда и гонки нет, и правка
 * откатывается Ctrl+Z, как всякая другая.
 *
 * Пропущенный первый шаг стоит дорого: несохранённые правки человека окажутся
 * затёрты его же заменой. Отдельного предохранителя от этого здесь нет —
 * порядок держит тот, кто зовёт.
 *
 * **Отката всей замены целиком нет.** Для закрытых файлов IDE не хранит истории,
 * и заводить её ради одной операции — это отдельная подсистема. Страховка здесь
 * называется git, она в проекте есть, и это честнее, чем обещать undo, который
 * переживёт перезапуск.
 *
 * **Атомарности тоже нет.** Сбой записи на середине оставляет часть файлов
 * заменёнными; список изменённых возвращается, чтобы было что показать.
 */
class ProjectReplace(private val tree: ProjectTree) {

    fun replace(
        query: SearchQuery,
        replacement: String,
        maxFileBytes: Long = ProjectSearch.MAX_FILE_BYTES,
        isCancelled: () -> Boolean = { false },
    ): ProjectReplaceResult {
        val changed = ArrayList<ReplacedFile>()
        val problems = ArrayList<UnchangedFile>()

        if (query.isEmpty) {
            return ProjectReplaceResult(changed, problems, SearchOutcome.Complete, 0, 0)
        }

        val search = TextSearch(query)
        val regex = search.regex
            ?: return ProjectReplaceResult(changed, problems, SearchOutcome.Complete, 0, 0)

        val walk = walkSources(tree, maxFileBytes, isCancelled) { file, path, text ->
            // Дешёвая проверка по уже прочитанному тексту: файл без совпадений
            // не нужно ни перечитывать через `TextFiles`, ни писать обратно.
            if (regex.containsMatchIn(text)) {
                replaceInFile(search, replacement, file, path, changed, problems)
            }
            true
        }

        val outcome = when (walk.stop) {
            WalkStop.Cancelled -> SearchOutcome.Cancelled
            else -> SearchOutcome.Complete
        }
        return ProjectReplaceResult(changed, problems, outcome, walk.scanned, walk.skipped)
    }

    private fun replaceInFile(
        search: TextSearch,
        replacement: String,
        file: File,
        path: String,
        changed: MutableList<ReplacedFile>,
        problems: MutableList<UnchangedFile>,
    ) {
        // Перечитываем через TextFiles, а не берём текст обхода: там переносы
        // не приведены к `\n` и кодировка не разобрана, а писать файл надо
        // ровно в том виде, в каком он был.
        val loaded = runCatching { TextFiles.load(file) }.getOrElse {
            problems += UnchangedFile(file, path, "не прочитался: ${it.message}")
            return
        }
        if (loaded.isReadOnly) {
            problems += UnchangedFile(file, path, reasonOf(loaded.readOnlyReason))
            return
        }

        val edit = search.replaceAll(loaded.text, replacement)
        if (edit.isEmpty) return

        val result = runCatching { TextFiles.save(file, edit.applyTo(loaded.text), loaded.format) }
        if (result.isFailure) {
            problems += UnchangedFile(file, path, "не записался: ${result.exceptionOrNull()?.message}")
            return
        }
        changed += ReplacedFile(file, path, edit.replacements.size, inBuffer = false)
    }

    private fun reasonOf(reason: ReadOnlyReason?): String = when (reason) {
        ReadOnlyReason.TooLarge -> "слишком большой"
        ReadOnlyReason.Binary -> "не текст"
        ReadOnlyReason.UnknownEncoding -> "кодировка не разобрана"
        null -> "только чтение"
    }
}

/**
 * Повторяет замену в открытых буферах и записывает их.
 *
 * Второй шаг порядка, описанного у [ProjectReplace]: зовётся в потоке
 * интерфейса, потому что правит `EditorState`. Считает по тексту буфера, а не
 * по диску: к этому моменту они совпадают (буферы сохранялись перед заменой),
 * но верить в это на слово незачем — то же выражение по тому же тексту даёт тот
 * же результат.
 *
 * Записывает следом сам: без этого буфер, совпадающий с диском до последнего
 * знака, показывал бы звёздочку «изменён» — глубина отката-то выросла.
 *
 * Границы у этого шага те же, что у обхода, и держать их приходится вручную:
 * буфер знает про свой файл, но не про то, заходил ли туда поиск. Пропускаются
 * файлы вне дерева проекта (открыть можно что угодно, а «заменить по проекту»
 * значит по проекту) и файлы крупнее порога обхода. Иначе замена трогала бы то,
 * чего человек в списке совпадений не видел.
 */
fun replayInBuffers(
    workspace: Workspace,
    query: SearchQuery,
    replacement: String,
    maxFileBytes: Long = ProjectSearch.MAX_FILE_BYTES,
): List<ReplacedFile> {
    val search = TextSearch(query)
    if (search.regex == null) return emptyList()

    val replayed = ArrayList<ReplacedFile>()
    for (open in workspace.files) {
        if (open.isReadOnly) continue

        val path = workspace.tree.relativePath(open.file) ?: continue
        if (open.file.length() > maxFileBytes) continue

        val edit = search.replaceAll(open.state.text, replacement)
        if (edit.isEmpty) continue

        open.state.replaceAll(edit)
        runCatching { workspace.save(open) }

        replayed += ReplacedFile(open.file, path, edit.replacements.size, inBuffer = true)
    }
    return replayed
}
