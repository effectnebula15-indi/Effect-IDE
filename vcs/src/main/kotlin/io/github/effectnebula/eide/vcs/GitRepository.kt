package io.github.effectnebula.eide.vcs

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk

/** Состояние файла относительно последнего коммита. */
enum class FileStatus {
    Untracked,
    Added,
    Modified,
    Deleted,
    Conflicted,
}

/** Что показать в гаттере напротив строки. */
enum class LineMark {
    Added,
    Modified,
    /** Ниже этой строки что-то удалено. Помечается граница, а не сами строки: их уже нет. */
    DeletedBelow,
}

/**
 * Результат коммита.
 *
 * [signingRequested] означает, что конфигурация просила подписать коммит, а мы
 * этого не сделали — см. [GitRepository.commit].
 */
data class CommitResult(val id: String, val signingRequested: Boolean)

data class RepositoryStatus(
    val branch: String?,
    val files: Map<String, FileStatus>,
) {
    val isClean: Boolean get() = files.isEmpty()
}

/**
 * Репозиторий git.
 *
 * Интерфейс намеренно узкий (ADR-006): работа JGit на Android не подтверждена на
 * устройстве, и если её придётся заменить, менять надо один модуль, а не редактор.
 */
class GitRepository private constructor(private val git: Git) : AutoCloseable {

    val workTree: File get() = git.repository.workTree

    /** Текущая ветка, либо null в состоянии отсоединённой головы. */
    fun currentBranch(): String? {
        val full = git.repository.fullBranch ?: return null
        return if (full.startsWith(Constants.R_HEADS)) full.removePrefix(Constants.R_HEADS) else null
    }

    fun branches(): List<String> =
        git.branchList().call().map { it.name.removePrefix(Constants.R_HEADS) }

    fun status(): RepositoryStatus {
        val status = git.status().call()
        val files = LinkedHashMap<String, FileStatus>()

        // Порядок важен: конфликт перекрывает всё остальное, поэтому идёт последним.
        status.untracked.forEach { files[it] = FileStatus.Untracked }
        status.added.forEach { files[it] = FileStatus.Added }
        status.modified.forEach { files[it] = FileStatus.Modified }
        status.changed.forEach { files[it] = FileStatus.Modified }
        status.removed.forEach { files[it] = FileStatus.Deleted }
        status.missing.forEach { files[it] = FileStatus.Deleted }
        status.conflicting.forEach { files[it] = FileStatus.Conflicted }

        return RepositoryStatus(currentBranch(), files)
    }

    fun stage(vararg paths: String) {
        val add = git.add()
        paths.forEach { add.addFilepattern(it) }
        add.call()
    }

    /** Добавляет в индекс и удаления тоже — `git add -A` для перечисленных путей. */
    fun stageAll() {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
    }

    /**
     * Создаёт коммит. **Подпись не ставится никогда.**
     *
     * JGit не умеет ssh-подписи, а `commit.gpgsign=true` с `gpg.format=ssh` —
     * распространённая настройка. Если пытаться уважить её, коммит просто падает
     * с невнятным «No signer for ssh signatures», и человек остаётся без коммита.
     *
     * Выбор здесь между «не подписать и сказать» и «не дать закоммитить вовсе».
     * Мы выбираем первое, но молчать об этом нельзя: в репозитории с политикой
     * обязательной подписи неподписанный коммит отклонят на сервере, и узнать
     * об этом лучше сразу. Поэтому [CommitResult.signingRequested] говорит,
     * что настройка просила подпись, и интерфейс обязан это показать.
     */
    fun commit(message: String, authorName: String, authorEmail: String): CommitResult {
        val signingRequested = git.repository.config
            .getBoolean("commit", null, "gpgsign", false)

        val id = git.commit()
            .setMessage(message)
            .setAuthor(authorName, authorEmail)
            .setCommitter(authorName, authorEmail)
            .setSign(false)
            .call()
            .name

        return CommitResult(id, signingRequested)
    }

    /**
     * Пометки для гаттера: чем текущий текст файла отличается от версии в HEAD.
     *
     * Текст передаётся снаружи, а не читается с диска, потому что в редакторе он
     * может быть ещё не сохранён — а гаттер должен показывать то, что человек видит.
     */
    fun gutterMarks(path: String, currentText: String): Map<Int, LineMark> {
        val headBytes = readFromHead(path) ?: return emptyMap()

        val before = RawText(headBytes)
        val after = RawText(currentText.toByteArray(Charsets.UTF_8))
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, before, after)

        val marks = LinkedHashMap<Int, LineMark>()
        for (edit in edits) {
            val addedLines = edit.endB - edit.beginB
            val removedLines = edit.endA - edit.beginA

            when {
                addedLines > 0 && removedLines > 0 ->
                    for (line in edit.beginB until edit.endB) marks[line] = LineMark.Modified
                addedLines > 0 ->
                    for (line in edit.beginB until edit.endB) marks[line] = LineMark.Added
                // Удалённых строк в текущем тексте нет, поэтому помечаем границу.
                else -> marks[(edit.beginB - 1).coerceAtLeast(0)] = LineMark.DeletedBelow
            }
        }
        return marks
    }

    /** Содержимое файла в HEAD, либо null если файла там нет (новый файл). */
    fun readFromHead(path: String): ByteArray? {
        val head: ObjectId = git.repository.resolve(Constants.HEAD) ?: return null
        git.repository.newObjectReader().use { reader ->
            RevWalk(git.repository).use { walk ->
                val tree = walk.parseCommit(head).tree
                TreeWalk.forPath(git.repository, path, tree).use { treeWalk ->
                    if (treeWalk == null) return null
                    return reader.open(treeWalk.getObjectId(0)).bytes
                }
            }
        }
    }

    override fun close() {
        git.close()
    }

    companion object {
        /** Открывает репозиторий, содержащий [directory], либо null если его нет. */
        fun open(directory: File): GitRepository? {
            val gitDir = FileRepositoryBuilder()
                .findGitDir(directory)
                .gitDir ?: return null
            val repository = FileRepositoryBuilder().setGitDir(gitDir).readEnvironment().build()
            return GitRepository(Git(repository))
        }

        /**
         * Имя начальной ветки задаётся явно: у разных версий git и JGit разные
         * умолчания, и тесты, полагающиеся на умолчание, ломаются от обновления.
         */
        fun init(directory: File, initialBranch: String = "main"): GitRepository =
            GitRepository(
                Git.init().setDirectory(directory).setInitialBranch(initialBranch).call()
            )
    }
}
