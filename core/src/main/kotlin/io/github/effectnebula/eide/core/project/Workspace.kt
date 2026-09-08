package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.platform.GraphemeBreaker
import java.io.File

/**
 * Открытый файл: его состояние в редакторе и то, что о нём известно с диска.
 *
 * Изменённость считается по глубине истории отката, а не по версии документа
 * и не по ревизии редактора. У обеих беда: ревизия растёт от движения курсора
 * (звёздочка после простого тыка в текст), а версия растёт и на откате —
 * то есть файл оставался бы «изменённым» после возврата к исходному тексту.
 * Глубина возвращается к прежнему значению вместе с текстом.
 */
class OpenFile internal constructor(
    val file: File,
    val format: TextFileFormat,
    val state: EditorState,
    val readOnlyReason: ReadOnlyReason?,
) {
    internal var savedDepth: Int = state.document.undoDepth

    val isModified: Boolean get() = state.document.undoDepth != savedDepth
    val isReadOnly: Boolean get() = readOnlyReason != null
    val name: String get() = file.name
}

/**
 * Открытые файлы проекта.
 *
 * Текст каждого файла живёт здесь столько, сколько файл открыт: переключение
 * между файлами не должно терять ни несохранённые правки, ни положение
 * курсора. Это ровно то, из-за чего редактор с одним буфером перестаёт быть
 * редактором, как только файлов становится два.
 *
 * Ограничение, названное честно: файлы держатся в памяти целиком (ADR-005,
 * потолок 10 МБ на файл). Десяток открытых больших файлов — это десятки
 * мегабайт, и вытеснения давно не открывавшихся здесь нет.
 */
class Workspace(
    val tree: ProjectTree,
    private val graphemes: GraphemeBreaker,
) {
    private val opened = LinkedHashMap<String, OpenFile>()

    /** В порядке открытия — это же порядок вкладок. */
    val files: List<OpenFile> get() = opened.values.toList()

    var active: OpenFile? = null
        private set

    val hasUnsaved: Boolean get() = opened.values.any { it.isModified }

    /**
     * Открывает файл или делает активным уже открытый.
     *
     * Повторное открытие не перечитывает диск: несохранённые правки важнее
     * свежести, а «файл изменился снаружи» — отдельный разговор, которого без
     * слежения за файловой системой пока не завести.
     */
    fun open(file: File): OpenFile {
        val key = keyOf(file)
        opened[key]?.let {
            active = it
            return it
        }

        val loaded = TextFiles.load(file)
        val opened = OpenFile(
            file = file,
            format = loaded.format,
            state = EditorState(Document(loaded.text), graphemes),
            readOnlyReason = loaded.readOnlyReason,
        )
        this.opened[key] = opened
        active = opened
        return opened
    }

    /** Делает активным уже открытый файл. Возвращает `false`, если он не открыт. */
    fun activate(file: File): Boolean {
        val opened = opened[keyOf(file)] ?: return false
        active = opened
        return true
    }

    /**
     * Закрывает файл. Несохранённые правки при этом теряются — спрашивать
     * должен интерфейс, здесь для этого нет ни языка, ни места.
     */
    fun close(file: File): Boolean {
        val key = keyOf(file)
        val removed = opened.remove(key) ?: return false

        if (active === removed) {
            // Активным становится сосед, а не «ничего»: пустой экран после
            // закрытия вкладки — лишний шаг для человека.
            active = opened.values.lastOrNull()
        }
        return true
    }

    /** Пишет файл на диск в том формате, в каком он открывался. */
    fun save(target: OpenFile) {
        if (target.isReadOnly) return
        TextFiles.save(target.file, target.state.text, target.format)
        target.savedDepth = target.state.document.undoDepth
    }

    /** Возвращает число записанных файлов: ноль означает, что писать было нечего. */
    fun saveModified(): Int {
        var written = 0
        for (open in opened.values) {
            if (open.isModified && !open.isReadOnly) {
                save(open)
                written++
            }
        }
        return written
    }

    /**
     * Ключ — канонический путь, а не сам `File`.
     *
     * `File` сравнивается по строке пути, поэтому `./main.py` и `main.py` были
     * бы разными файлами, и один и тот же текст открылся бы дважды.
     */
    private fun keyOf(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}
