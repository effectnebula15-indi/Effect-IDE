package io.github.effectnebula.eide.core.editor

/**
 * Курсор с выделением.
 *
 * [anchor] — место, где выделение началось, [head] — где сейчас курсор. Они не
 * взаимозаменяемы: выделение, растянутое влево, при продолжении вправо должно
 * схлопываться от того же якоря, а не от левого края.
 *
 * [desiredColumn] помнит колонку, с которой началось вертикальное движение.
 * Без этого проход вниз через короткую строку и обратно вверх возвращает курсор
 * не туда, откуда он начал, — классическая раздражающая ошибка редакторов.
 */
data class Caret(
    val anchor: Int,
    val head: Int = anchor,
    val desiredColumn: Int? = null,
) {
    init {
        require(anchor >= 0) { "anchor $anchor отрицательный" }
        require(head >= 0) { "head $head отрицательный" }
    }

    val start: Int get() = minOf(anchor, head)
    val end: Int get() = maxOf(anchor, head)
    val isEmpty: Boolean get() = anchor == head

    /** Схлопывает выделение в позицию курсора. */
    fun collapsed(): Caret = Caret(head, head, desiredColumn)

    fun movedTo(offset: Int, keepSelection: Boolean, desiredColumn: Int? = null): Caret =
        Caret(
            anchor = if (keepSelection) anchor else offset,
            head = offset,
            desiredColumn = desiredColumn,
        )

    fun overlaps(other: Caret): Boolean = start <= other.end && other.start <= end
}
