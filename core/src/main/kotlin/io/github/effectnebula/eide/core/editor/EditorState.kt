package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.EditKind
import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Replacement
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker

/** Направление перемещения курсора. */
enum class MoveTo {
    Left, Right, Up, Down,
    WordLeft, WordRight,
    LineStart, LineEnd,
    DocumentStart, DocumentEnd,
}

/**
 * Подписчик на изменения редактора: текст, курсоры — что угодно из того,
 * что видно на экране.
 *
 * Ядро не знает про Compose, а Compose не умеет наблюдать за обычными полями.
 * Этот интерфейс — единственный мост между ними, и он же нужен системной
 * клавиатуре: IME обязан узнавать о правках, которые сделал не он.
 */
fun interface EditorListener {
    fun onEditorChanged()
}

/**
 * Состояние редактируемого текста: документ плюс курсоры.
 *
 * Здесь собирается всё остальное ядро — rope, правки, undo, навигация — в тот вид,
 * который дёргает интерфейс. Про интерфейс этот класс не знает ничего, поэтому
 * переживёт смену UI-фреймворка: от него нужны только вызовы и чтение результата.
 */
class EditorState(
    val document: Document = Document(),
    private val graphemes: GraphemeBreaker,
) {
    var carets: CaretSet = CaretSet.single(0)
        private set

    /** Растёт на каждом видимом изменении — и текста, и курсоров. */
    var revision: Long = 0
        private set

    // Список заменяется целиком, а не правится на месте: уведомление случается
    // на каждое нажатие клавиши, подписка — раз в жизни экрана. Дешевле
    // копировать при подписке, чем защищаться от правки списка при обходе.
    private var listeners: List<EditorListener> = emptyList()

    val text: Rope get() = document.text

    private val movement: Movement get() = Movement(document.text, graphemes)

    // --- подписка --------------------------------------------------------------

    fun addListener(listener: EditorListener) {
        listeners = listeners + listener
    }

    fun removeListener(listener: EditorListener) {
        listeners = listeners - listener
    }

    /**
     * Оповещает подписчиков об изменении.
     *
     * Вызывается в конце каждой мутации, и только если что-то действительно
     * поменялось: пустая правка или клонирование курсора в никуда не должны
     * будить ни перерисовку, ни IME.
     */
    private fun notifyChanged() {
        revision++
        for (listener in listeners) listener.onEditorChanged()
    }

    // --- правка ----------------------------------------------------------------

    fun type(input: String) {
        if (input.isEmpty()) return
        applyEdit(carets.typing(input), EditKind.Typing)
    }

    /**
     * Перенос строки с сохранением отступа.
     *
     * Без этого в коде с отступами каждая новая строка начинается от края, и человек
     * жмёт пробелы вручную — на телефоне особенно неприятно.
     */
    fun insertNewline() {
        val replacements = carets.carets.map { caret ->
            val line = document.text.lineOf(caret.start)
            Replacement(caret.start, caret.end, "\n" + indentOf(line))
        }
        applyEdit(EditTransaction(replacements), EditKind.Typing)
    }

    /** Backspace: удаляет выделение, а без него — предыдущую графему. */
    fun deleteBackward() {
        val replacements = carets.carets.mapNotNull { caret ->
            if (!caret.isEmpty) {
                Replacement(caret.start, caret.end, "")
            } else {
                val from = movement.left(caret.collapsed(), keepSelection = false).head
                if (from == caret.head) null else Replacement(from, caret.head, "")
            }
        }
        applyEdit(EditTransaction(replacements), EditKind.Deleting)
    }

    /** Delete: удаляет выделение, а без него — следующую графему. */
    fun deleteForward() {
        val replacements = carets.carets.mapNotNull { caret ->
            if (!caret.isEmpty) {
                Replacement(caret.start, caret.end, "")
            } else {
                val to = movement.right(caret.collapsed(), keepSelection = false).head
                if (to == caret.head) null else Replacement(caret.head, to, "")
            }
        }
        applyEdit(EditTransaction(replacements), EditKind.Deleting)
    }

    fun replaceAll(edit: EditTransaction) {
        applyEdit(edit, EditKind.Other)
    }

    // --- история ---------------------------------------------------------------

    fun undo(): Boolean = step(document.undo() != null)

    fun redo(): Boolean = step(document.redo() != null)

    /**
     * После отката курсор ставится к месту изменения, а не переносится через правку.
     *
     * Человек, нажавший undo, хочет увидеть, что именно откатилось; оставлять курсор
     * там, где он был, значит откатить что-то за кадром.
     */
    private fun step(happened: Boolean): Boolean {
        if (!happened) return false
        val lastChange = document.lastChange
        if (lastChange != null) {
            val target = lastChange.edit.replacements.firstOrNull()
                ?.let { it.start + it.text.length }
                ?: 0
            carets = CaretSet.single(target.coerceIn(0, document.text.length))
        }
        notifyChanged()
        return true
    }

    // --- курсоры ---------------------------------------------------------------

    fun move(to: MoveTo, extend: Boolean = false) {
        val m = movement
        carets = carets.map { caret ->
            when (to) {
                MoveTo.Left -> m.left(caret, extend)
                MoveTo.Right -> m.right(caret, extend)
                MoveTo.Up -> m.up(caret, extend)
                MoveTo.Down -> m.down(caret, extend)
                MoveTo.WordLeft -> m.wordLeft(caret, extend)
                MoveTo.WordRight -> m.wordRight(caret, extend)
                MoveTo.LineStart -> m.lineStart(caret, extend)
                MoveTo.LineEnd -> m.lineEnd(caret, extend)
                MoveTo.DocumentStart -> caret.movedTo(0, extend)
                MoveTo.DocumentEnd -> caret.movedTo(document.text.length, extend)
            }
        }
        notifyChanged()
    }

    fun setCarets(carets: CaretSet) {
        this.carets = carets
        // Новое положение курсора — новое действие: следующая правка не должна
        // приклеиться к тому, что человек набирал в прежнем месте.
        document.breakGrouping()
        notifyChanged()
    }

    fun selectAll() {
        carets = CaretSet.of(listOf(Caret(anchor = 0, head = document.text.length)))
        notifyChanged()
    }

    /** Добавляет курсор строкой ниже последнего — основа мультикурсора. */
    fun addCaretBelow() = addCaretVertically(+1)

    fun addCaretAbove() = addCaretVertically(-1)

    private fun addCaretVertically(delta: Int) {
        val source = if (delta > 0) carets.carets.last() else carets.carets.first()
        val moved = if (delta > 0) {
            movement.down(source, keepSelection = false)
        } else {
            movement.up(source, keepSelection = false)
        }
        // Сравниваем строки, а не офсеты: на последней строке движение вниз
        // уводит курсор в конец документа — офсет меняется, строка нет, и второй
        // курсор оказался бы на той же строке, что и первый.
        val sourceLine = document.text.lineOf(source.head)
        val movedLine = document.text.lineOf(moved.head)
        if (movedLine == sourceLine) return
        carets = CaretSet.of(carets.carets + moved.collapsed())
        notifyChanged()
    }

    // --- внутреннее ------------------------------------------------------------

    private fun applyEdit(edit: EditTransaction, kind: EditKind) {
        if (edit.isEmpty || edit.replacements.all { it.start == it.end && it.text.isEmpty() }) return
        val change = document.apply(edit, kind) ?: return
        carets = carets.afterEdit(change.edit)
        notifyChanged()
    }

    private fun indentOf(line: Int): String {
        val start = document.text.lineStart(line)
        val end = document.text.lineEnd(line)
        var i = start
        while (i < end && document.text.charAt(i).let { it == ' ' || it == '\t' }) i++
        return document.text.substring(start, i)
    }
}
