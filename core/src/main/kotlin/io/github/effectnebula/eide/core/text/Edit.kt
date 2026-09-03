package io.github.effectnebula.eide.core.text

/**
 * Замена участка текста: диапазон `[start, end)` заменяется на [text].
 *
 * Вставка — пустой диапазон, удаление — пустой текст.
 */
data class Replacement(val start: Int, val end: Int, val text: String) {
    init {
        require(start >= 0) { "start $start отрицательный" }
        require(end >= start) { "end $end меньше start $start" }
    }

    /** Насколько изменится длина документа от этой замены. */
    val lengthDelta: Int get() = text.length - (end - start)
}

/**
 * Правка документа как единое целое.
 *
 * Транзакция, а не одиночная замена, потому что множественные курсоры делают
 * несколько замен, которые обязаны попасть в одно действие undo. Разложить их
 * на отдельные правки нельзя: после первой же сместятся офсеты остальных.
 *
 * Замены хранятся упорядоченными по возрастанию и не пересекаются — это
 * проверяется при создании, а не подразумевается.
 */
class EditTransaction(replacements: List<Replacement>) {

    val replacements: List<Replacement> = replacements.sortedBy { it.start }

    init {
        for (i in 1 until this.replacements.size) {
            val previous = this.replacements[i - 1]
            val current = this.replacements[i]
            require(previous.end <= current.start) {
                "замены пересекаются: [${previous.start}, ${previous.end}) и [${current.start}, ${current.end})"
            }
        }
    }

    val isEmpty: Boolean get() = replacements.isEmpty()

    /** Суммарное изменение длины документа. */
    val lengthDelta: Int get() = replacements.sumOf { it.lengthDelta }

    /**
     * Применяет правку.
     *
     * Идём с конца: тогда офсеты ещё не применённых замен остаются верными и
     * пересчитывать их не нужно.
     */
    fun applyTo(rope: Rope): Rope {
        var result = rope
        for (replacement in replacements.asReversed()) {
            result = result.replace(replacement.start, replacement.end, replacement.text)
        }
        return result
    }

    /**
     * Обратная правка: применённая к результату, вернёт исходный текст.
     *
     * Именно так работает undo — мы не храним копии документа, а храним, что
     * нужно сделать, чтобы откатиться.
     */
    fun invert(before: Rope): EditTransaction {
        var shift = 0
        val inverted = ArrayList<Replacement>(replacements.size)
        for (replacement in replacements) {
            val removed = before.substring(replacement.start, replacement.end)
            val newStart = replacement.start + shift
            inverted += Replacement(newStart, newStart + replacement.text.length, removed)
            shift += replacement.lengthDelta
        }
        return EditTransaction(inverted)
    }

    /**
     * Переносит офсет из «до правки» в «после правки».
     *
     * Офсет внутри заменённого участка схлопывается к его началу: где именно
     * внутри удалённого текста стоял курсор, после удаления смысла не имеет.
     */
    fun mapOffset(offset: Int): Int {
        var result = offset
        for (replacement in replacements) {
            if (replacement.start >= offset) break
            result += if (replacement.end <= offset) {
                replacement.lengthDelta
            } else {
                // Офсет попал внутрь замены.
                replacement.start + replacement.text.length - offset
            }
        }
        return result
    }

    companion object {
        fun of(vararg replacements: Replacement): EditTransaction =
            EditTransaction(replacements.toList())

        fun insert(offset: Int, text: String): EditTransaction =
            EditTransaction(listOf(Replacement(offset, offset, text)))

        fun delete(start: Int, end: Int): EditTransaction =
            EditTransaction(listOf(Replacement(start, end, "")))

        fun replace(start: Int, end: Int, text: String): EditTransaction =
            EditTransaction(listOf(Replacement(start, end, text)))
    }
}
