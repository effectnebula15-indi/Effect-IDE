package io.github.effectnebula.eide.core.text

/**
 * Характер правки. Нужен только для группировки undo: подряд идущий набор
 * символов должен откатываться одним нажатием, а не по букве.
 */
enum class EditKind {
    /** Пользователь печатает. */
    Typing,

    /** Пользователь стирает. */
    Deleting,

    /** Всё остальное: вставка из буфера, форматирование, замена по проекту. */
    Other,
}

/**
 * Что изменилось в документе — чтобы подписчики не сравнивали тексты целиком.
 *
 * Правок — список, а не одна: откат группы применяет их несколько подряд, по
 * одной на каждое слово набора. Когда здесь лежала только последняя, откат
 * набранного «abc» выглядел для подписчика как удаление одной буквы, и всё, что
 * переносит офсеты через правку, уезжало на две позиции.
 */
data class DocumentChange(
    /** Правки в порядке применения; офсеты каждой — в тексте после предыдущей. */
    val edits: List<EditTransaction>,
    val versionBefore: Long,
    val versionAfter: Long,
) {
    /** Переносит офсет через весь шаг — по правилам [EditTransaction.mapOffset]. */
    fun mapOffset(offset: Int): Int = edits.fold(offset) { at, edit -> edit.mapOffset(at) }

    /**
     * Самое раннее место в итоговом тексте, где что-то поменялось; null — нигде.
     *
     * Минимум по всем правкам, а не начало последней: при откате стирания
     * последней применяется самая правая, и она может оказаться строкой ниже.
     * Переносить начала через последующие правки не нужно: правка сдвигает только
     * то, что не левее её собственного начала, и минимум от этого не меняется.
     */
    fun firstChangedOffset(): Int? = edits.mapNotNull { it.replacements.firstOrNull()?.start }.minOrNull()
}

/**
 * Текст документа вместе с историей правок.
 *
 * Сам текст неизменяем (`Rope`), меняется только ссылка на него. Благодаря этому
 * снимок для фонового парсера стоит O(1), а история хранит не копии документа,
 * а обратные правки (ADR-005).
 */
class Document(initial: Rope = Rope.EMPTY) {

    var text: Rope = initial
        private set

    /** Растёт на каждой правке, включая откаты. Нужен для инвалидации кэшей. */
    var version: Long = 0
        private set

    /**
     * Последнее применённое изменение, включая откат.
     *
     * Нужно тем, кто должен отреагировать на правку, но не участвовал в ней:
     * например, поставить курсор к месту, которое только что откатили.
     */
    var lastChange: DocumentChange? = null
        private set

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    private var nextGroup: Long = 1
    private var lastKind: EditKind = EditKind.Other
    private var lastEditAtMs: Long = Long.MIN_VALUE
    private var lastEditEnd: Int = -1

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Сколько правок лежит в истории отката. Это положение на ленте времени.
     *
     * В отличие от [version], которая только растёт, глубина возвращается к
     * прежнему значению после отката — и вместе с ней возвращается текст.
     * Отсюда способ узнать «текст такой же, каким его записали на диск»:
     * сравнить глубину, а не сличать мегабайты содержимого.
     *
     * Работает потому, что лента линейна: новая правка стирает всё, что было
     * повторено, и одна и та же глубина всегда означает одно и то же
     * состояние текста.
     */
    val undoDepth: Int get() = undoStack.size

    fun apply(
        edit: EditTransaction,
        kind: EditKind = EditKind.Other,
        nowMs: Long = System.currentTimeMillis(),
    ): DocumentChange? {
        if (edit.isEmpty) return null

        val before = text
        val inverse = edit.invert(before)
        val versionBefore = version

        text = edit.applyTo(before)
        version++

        undoStack.addLast(Entry(inverse = inverse, redo = edit, group = groupFor(edit, kind, nowMs)))
        redoStack.clear()

        lastKind = kind
        lastEditAtMs = nowMs
        lastEditEnd = edit.replacements.lastOrNull()
            ?.let { it.start + it.text.length }
            ?: -1

        return DocumentChange(listOf(edit), versionBefore, version).also { lastChange = it }
    }

    /** Откатывает последнюю группу правок целиком. */
    fun undo(): DocumentChange? = step(undoStack, redoStack) { it.inverse }

    /** Повторяет последнюю откатанную группу. */
    fun redo(): DocumentChange? = step(redoStack, undoStack) { it.redo }

    private fun step(
        from: ArrayDeque<Entry>,
        to: ArrayDeque<Entry>,
        pick: (Entry) -> EditTransaction,
    ): DocumentChange? {
        val top = from.lastOrNull() ?: return null
        val group = top.group
        val versionBefore = version

        val applied = ArrayList<EditTransaction>()
        while (true) {
            val entry = from.lastOrNull() ?: break
            if (entry.group != group) break
            from.removeLast()

            val transaction = pick(entry)
            text = transaction.applyTo(text)
            applied += transaction
            to.addLast(entry)
        }

        version++
        // Следующая правка после отката не должна приклеиться к прежней группе.
        breakGrouping()

        return DocumentChange(applied, versionBefore, version).also { lastChange = it }
    }

    /** Принудительно закрывает текущую группу: следующая правка начнёт новую. */
    fun breakGrouping() {
        lastKind = EditKind.Other
        lastEditAtMs = Long.MIN_VALUE
        lastEditEnd = -1
        nextGroup++
    }

    private fun groupFor(edit: EditTransaction, kind: EditKind, nowMs: Long): Long {
        val continues = kind != EditKind.Other &&
            kind == lastKind &&
            nowMs - lastEditAtMs <= GROUPING_WINDOW_MS &&
            isContiguous(edit, kind)

        if (!continues) nextGroup++
        return nextGroup
    }

    /**
     * Правка продолжает предыдущую, если начинается там, где та закончилась.
     *
     * Без этой проверки набор в одном месте и набор в другом склеились бы в одно
     * действие undo только потому, что случились подряд.
     */
    private fun isContiguous(edit: EditTransaction, kind: EditKind): Boolean {
        if (edit.replacements.size != 1) return false
        val replacement = edit.replacements.single()
        return when (kind) {
            EditKind.Typing -> replacement.start == lastEditEnd
            EditKind.Deleting -> replacement.end == lastEditEnd || replacement.start == lastEditEnd
            EditKind.Other -> false
        }
    }

    private class Entry(
        val inverse: EditTransaction,
        val redo: EditTransaction,
        val group: Long,
    )

    companion object {
        /**
         * Пауза, после которой набор считается новым действием.
         *
         * Полсекунды — компромисс: меньше рвёт быстрый набор на куски, больше
         * склеивает в одно undo то, что пользователь считает разными правками.
         */
        const val GROUPING_WINDOW_MS: Long = 500
    }
}
