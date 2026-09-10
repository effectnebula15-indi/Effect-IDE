package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Replacement

/**
 * Набор курсоров.
 *
 * Всегда упорядочен и без пересечений: два курсора, наехавшие друг на друга,
 * сливаются в один. Иначе набор текста в них продублировался бы, а выделения
 * стали бы неразличимы на экране.
 */
class CaretSet private constructor(val carets: List<Caret>) {

    init {
        require(carets.isNotEmpty()) { "набор курсоров не может быть пустым" }
    }

    val primary: Caret get() = carets.last()

    fun map(transform: (Caret) -> Caret): CaretSet = of(carets.map(transform))

    /** Переносит курсоры через правку документа. */
    fun afterEdit(edit: EditTransaction): CaretSet = of(
        carets.map { caret ->
            Caret(
                anchor = edit.mapOffset(caret.anchor),
                head = edit.mapOffset(caret.head),
                desiredColumn = caret.desiredColumn,
            )
        }
    )

    /**
     * Строит правку, вставляющую [text] в каждый курсор, заменяя его выделение.
     *
     * Курсоры уже упорядочены и не пересекаются, поэтому замены получаются
     * корректной транзакцией без дополнительной сортировки.
     */
    fun typing(text: String): EditTransaction =
        EditTransaction(carets.map { Replacement(it.start, it.end, text) })

    override fun toString(): String = carets.joinToString(prefix = "CaretSet(", postfix = ")")

    companion object {
        fun single(offset: Int): CaretSet = CaretSet(listOf(Caret(offset)))

        fun of(carets: List<Caret>): CaretSet {
            require(carets.isNotEmpty()) { "набор курсоров не может быть пустым" }
            val sorted = carets.sortedWith(compareBy({ it.start }, { it.end }))

            val merged = ArrayList<Caret>(sorted.size)
            for (caret in sorted) {
                val previous = merged.lastOrNull()
                if (previous != null && previous.overlaps(caret)) {
                    // Сливаем, сохраняя направление того курсора, что был правее:
                    // именно им пользователь двигал последним.
                    merged[merged.lastIndex] = mergeCarets(previous, caret)
                } else {
                    merged += caret
                }
            }
            return CaretSet(merged)
        }

        private fun mergeCarets(a: Caret, b: Caret): Caret {
            val start = minOf(a.start, b.start)
            val end = maxOf(a.end, b.end)
            val forward = b.head >= b.anchor
            return if (forward) {
                Caret(anchor = start, head = end, desiredColumn = b.desiredColumn)
            } else {
                Caret(anchor = end, head = start, desiredColumn = b.desiredColumn)
            }
        }
    }
}
