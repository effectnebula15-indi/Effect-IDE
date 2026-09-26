package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.editor.EditorListener
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Rope

/** Ошибка, привязанная к офсетам текущего текста, а не к строкам и столбцам сервера. */
data class DiagnosticMark(
    val start: Int,
    val end: Int,
    val severity: Severity,
    val message: String,
    val source: String?,
)

/** Кому сообщать, что пометки сменились. */
fun interface DiagnosticsListener {
    fun onDiagnosticsChanged()
}

/**
 * Ошибки одного открытого файла, которые едут вместе с текстом.
 *
 * **Принимаются только ошибки для текущей версии.** Сервер считает долго
 * (pylsp — полсекунды после последней правки, замерено), и пока он думал,
 * человек мог допечатать: позиции из ответа тогда относятся к тексту, которого
 * уже нет. Такой ответ выбрасывается целиком — свежий придёт после следующей
 * паузы в наборе. Цена: пока печатают без пауз, новые ошибки не появляются,
 * а исправленные не исчезают. Так же ведёт себя и сам сервер со своей задержкой.
 *
 * **Уже показанные пометки переносятся через каждую правку.** Иначе всё, что
 * ниже места набора, подчёркивалось бы не там с первой же буквы. Пометка, чей
 * текст стёрли или заменили целиком, пропадает: ошибки в тексте, которого нет,
 * не бывает, а про новый текст сервер ещё не сказал.
 *
 * **Пропущенная правка — сброс, а не догадка.** Если версия документа ушла
 * дальше, чем на последнюю правку (правили документ мимо [EditorState]), куда
 * переехали пометки, узнать уже нельзя. Лучше не показать ошибку, чем показать
 * её не на том месте.
 *
 * Поток один — поток интерфейса: и правки, и [publish] приходят оттуда.
 * Подписка на правки — с рождения: пометки, пропустившие правку, врут.
 */
class DiagnosticMarks(private val state: EditorState) : AutoCloseable {

    var marks: List<DiagnosticMark> = emptyList()
        private set

    /** Растёт на каждой смене пометок — для перерисовки. */
    var revision: Long = 0
        private set

    private var seenVersion = state.document.version
    private var listeners: List<DiagnosticsListener> = emptyList()

    private val onEdit = EditorListener(::follow)

    init {
        state.addListener(onEdit)
    }

    fun addListener(listener: DiagnosticsListener) {
        listeners = listeners + listener
    }

    fun removeListener(listener: DiagnosticsListener) {
        listeners = listeners - listener
    }

    /**
     * Ответ сервера для версии документа [version].
     *
     * Возвращает `false`, если ответ устарел и выброшен.
     */
    fun publish(version: Long, diagnostics: List<Diagnostic>, encoding: PositionEncoding): Boolean {
        if (version != state.document.version) return false
        val text = state.text
        marks = diagnostics.map { it.toMark(text, encoding) }
        // Версию запоминаем и здесь: правка могла пройти мимо редактора без
        // уведомления, и тогда следующая правка сверялась бы со старой версией.
        seenVersion = version
        changed()
        return true
    }

    /**
     * Самая серьёзная ошибка в точке [offset] — для строки состояния.
     *
     * Конец включается: курсор сразу за ошибочным словом — это место, где
     * человек только что допечатал, и сообщение нужно именно там.
     */
    fun at(offset: Int): DiagnosticMark? =
        marks.filter { offset in it.start..it.end }.minByOrNull { it.severity.ordinal }

    fun count(severity: Severity): Int = marks.count { it.severity == severity }

    override fun close() {
        state.removeListener(onEdit)
    }

    private fun follow() {
        val document = state.document
        // Движение курсора: текст тот же, пометкам делать нечего.
        if (document.version == seenVersion) return

        val change = document.lastChange
        val continuous = change != null &&
            change.versionBefore == seenVersion &&
            change.versionAfter == document.version
        seenVersion = document.version
        if (marks.isEmpty()) return

        marks = if (continuous) {
            marks.mapNotNull { mark ->
                val start = change!!.mapOffset(mark.start)
                val end = change.mapOffset(mark.end)
                // Схлопнулась непустая — её текст стёрли или заменили. Пустая
                // с самого начала (ошибка «в точке») живёт дальше.
                if (start == end && mark.start != mark.end) null else mark.copy(start = start, end = end)
            }
        } else {
            emptyList()
        }
        changed()
    }

    private fun changed() {
        revision++
        for (listener in listeners) listener.onDiagnosticsChanged()
    }
}

/**
 * Перевод из строк и столбцов сервера в офсеты.
 *
 * Пустой диапазон расширяется до одного символа — вперёд, а в конце строки
 * назад: подчёркивание нулевой ширины не видно, а сервер ставит такие
 * («ожидался отступ» в конце строки). Пустым остаётся только диапазон на
 * пустой строке — расширять там некуда.
 */
private fun Diagnostic.toMark(text: Rope, encoding: PositionEncoding): DiagnosticMark {
    var start = text.offsetOf(range.start, encoding)
    var end = text.offsetOf(range.end, encoding).coerceAtLeast(start)
    if (start == end) {
        val line = text.lineOf(start)
        // По кодовой точке, а не по char: половина эмодзи — не символ.
        when {
            end < text.lineEnd(line) ->
                end += if (Character.isHighSurrogate(text.charAt(end)) && end + 1 < text.lineEnd(line)) 2 else 1
            start > text.lineStart(line) ->
                start -= if (Character.isLowSurrogate(text.charAt(start - 1)) && start - 1 > text.lineStart(line)) 2 else 1
        }
    }
    return DiagnosticMark(start, end, severity, message, source)
}
