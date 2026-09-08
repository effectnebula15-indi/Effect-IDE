package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.EditKind
import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Replacement

/** Полуоткрытый диапазон `[start, end)` в тексте документа. */
data class TextSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "start $start отрицательный" }
        require(end >= start) { "end $end меньше start $start" }
    }

    val length: Int get() = end - start
    val isEmpty: Boolean get() = start == end
}

/**
 * Ввод через системную клавиатуру.
 *
 * Экранная клавиатура на Android не присылает нажатия клавиш. Она правит текст
 * сама, через `InputConnection`, и мыслит понятиями, которых в редакторе нет:
 *
 * - **одно** выделение в абсолютных офсетах — про мультикурсор она не знает;
 * - **composing region** — черновик слова, который клавиатура вправе переписать
 *   целиком, пока не зафиксирует. Автодополнение, свайп-ввод и исправления
 *   работают именно через него.
 *
 * Здесь живёт вся логика этого разговора, и живёт она в `:core` намеренно:
 * `InputConnection` — Android-класс, проверить его можно только на телефоне,
 * а ошибки в правке текста тихие и находятся спустя недели. Отдельно от
 * Android эта логика проверяется обычными JVM-тестами.
 *
 * Границы округляются к валидным офсетам, а не роняют приложение: значения
 * приходят от чужого кода (клавиатура — отдельный процесс), и он вправе
 * прислать устаревшие после правки, которую сделали мы.
 */
class InputSession(private val state: EditorState) {

    /** Черновик, который клавиатура ещё может переписать. `null` — черновика нет. */
    var composing: TextSpan? = null
        private set

    /** Текущее выделение глазами IME: основной курсор, всегда один. */
    val selection: TextSpan
        get() = state.carets.primary.let { TextSpan(it.start, it.end) }

    private val length: Int get() = state.text.length

    // --- правка ----------------------------------------------------------------

    /**
     * Заменяет черновик (а без него — выделение) на [text] и делает результат
     * новым черновиком.
     */
    fun setComposingText(text: String, newCursorPosition: Int) {
        replaceTarget(text, newCursorPosition, keepComposing = true)
    }

    /**
     * Фиксирует [text]: заменяет черновик или выделение и черновик закрывает.
     *
     * Единственное место, где переживает мультикурсор. Клавиатура, которая
     * коммитит символы сразу, без черновика, — а с `TYPE_TEXT_FLAG_NO_SUGGESTIONS`
     * так делает большинство — попадает во все курсоры, как аппаратная. Как
     * только появляется черновик, IME начинает адресоваться абсолютными
     * офсетами, и остальные курсоры приходится отпустить.
     */
    fun commitText(text: String, newCursorPosition: Int) {
        val plainTyping = composing == null &&
            newCursorPosition == 1 &&
            state.carets.carets.size > 1
        if (plainTyping) {
            state.type(text)
            return
        }
        replaceTarget(text, newCursorPosition, keepComposing = false)
    }

    /** Закрывает черновик, не трогая текст. */
    fun finishComposing() {
        if (composing == null) return
        composing = null
    }

    /** Помечает уже существующий участок как черновик. */
    fun setComposingRegion(start: Int, end: Int) {
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(0, length)
        composing = if (from == to) null else TextSpan(minOf(from, to), maxOf(from, to))
    }

    /**
     * Удаляет [before] символов до выделения и [after] после него.
     *
     * Считается в единицах UTF-16, как в Android. Суррогатную пару пополам не
     * режем: половина пары — не символ, и документ после такого становится
     * невалидным UTF-16.
     */
    fun deleteSurrounding(before: Int, after: Int) {
        val selection = selection
        val simpleBackspace = composing == null &&
            before == 1 && after == 0 &&
            selection.isEmpty &&
            state.carets.carets.size > 1
        if (simpleBackspace) {
            // Мультикурсорный backspace: удаляет графему, а не единицу UTF-16 —
            // это ближе к тому, что человек считает символом.
            state.deleteBackward()
            return
        }

        val from = surrogateSafeStart(selection.start - before.coerceIn(0, selection.start))
        val to = surrogateSafeEnd(selection.end + after.coerceIn(0, length - selection.end))
        if (from == selection.start && to == selection.end) return

        val edit = EditTransaction(
            listOf(
                Replacement(from, selection.start, ""),
                Replacement(selection.end, to, ""),
            )
        )
        val removedBefore = selection.start - from
        val anchor = selection.start - removedBefore
        val head = selection.end - removedBefore

        composing = composing?.let { clampSpanAfterDelete(it, from, selection.start, selection.end, to) }
        state.applyWithSelection(edit, anchor, head, EditKind.Deleting)
    }

    /** То же самое, но в кодовых точках: `deleteSurroundingTextInCodePoints`. */
    fun deleteSurroundingInCodePoints(before: Int, after: Int) {
        val selection = selection
        deleteSurrounding(
            before = selection.start - offsetBackByCodePoints(selection.start, before),
            after = offsetForwardByCodePoints(selection.end, after) - selection.end,
        )
    }

    /** Ставит выделение; `start == end` — просто курсор. */
    fun setSelection(start: Int, end: Int) {
        val anchor = start.coerceIn(0, length)
        val head = end.coerceIn(0, length)
        state.applyWithSelection(EditTransaction(emptyList()), anchor, head)
    }

    // --- чтение ----------------------------------------------------------------

    fun textBeforeCursor(count: Int): String {
        val end = selection.start
        val start = (end - count.coerceAtLeast(0)).coerceAtLeast(0)
        return state.text.substring(start, end)
    }

    fun textAfterCursor(count: Int): String {
        val start = selection.end
        val end = (start + count.coerceAtLeast(0)).coerceAtMost(length)
        return state.text.substring(start, end)
    }

    fun selectedText(): String = selection.let { state.text.substring(it.start, it.end) }

    // --- внутреннее ------------------------------------------------------------

    private fun replaceTarget(text: String, newCursorPosition: Int, keepComposing: Boolean) {
        val target = composing ?: selection
        val start = target.start.coerceIn(0, length)
        val end = target.end.coerceIn(start, length)

        // Правило Android: положительное значение отсчитывается от конца
        // вставленного текста (1 — сразу за ним), неположительное — от начала.
        val insertedEnd = start + text.length
        val cursor = if (newCursorPosition > 0) {
            insertedEnd + newCursorPosition - 1
        } else {
            start + newCursorPosition
        }

        composing = if (keepComposing && text.isNotEmpty()) TextSpan(start, insertedEnd) else null
        state.applyWithSelection(
            EditTransaction(listOf(Replacement(start, end, text))),
            anchor = cursor,
            head = cursor,
        )
    }

    /** Сдвигает границу влево, если она разрезала бы суррогатную пару. */
    private fun surrogateSafeStart(offset: Int): Int {
        if (offset <= 0 || offset >= length) return offset
        val isSplit = state.text.charAt(offset).isLowSurrogate() &&
            state.text.charAt(offset - 1).isHighSurrogate()
        return if (isSplit) offset - 1 else offset
    }

    /** Сдвигает границу вправо, если она разрезала бы суррогатную пару. */
    private fun surrogateSafeEnd(offset: Int): Int {
        if (offset <= 0 || offset >= length) return offset
        val isSplit = state.text.charAt(offset).isLowSurrogate() &&
            state.text.charAt(offset - 1).isHighSurrogate()
        return if (isSplit) offset + 1 else offset
    }

    private fun offsetBackByCodePoints(from: Int, count: Int): Int {
        var offset = from
        repeat(count.coerceAtLeast(0)) {
            if (offset <= 0) return 0
            offset--
            if (offset > 0 &&
                state.text.charAt(offset).isLowSurrogate() &&
                state.text.charAt(offset - 1).isHighSurrogate()
            ) {
                offset--
            }
        }
        return offset
    }

    private fun offsetForwardByCodePoints(from: Int, count: Int): Int {
        var offset = from
        repeat(count.coerceAtLeast(0)) {
            if (offset >= length) return length
            offset++
            if (offset < length &&
                state.text.charAt(offset).isLowSurrogate() &&
                state.text.charAt(offset - 1).isHighSurrogate()
            ) {
                offset++
            }
        }
        return offset
    }

    /**
     * Что станет с черновиком после удаления вокруг выделения.
     *
     * Всё, что от него осталось бы разорванным, проще закрыть: клавиатура
     * переживёт закрытый черновик, а рассинхронизованный — нет.
     */
    private fun clampSpanAfterDelete(
        span: TextSpan,
        beforeFrom: Int,
        beforeTo: Int,
        afterFrom: Int,
        afterTo: Int,
    ): TextSpan? {
        if (span.end <= beforeFrom) return span
        if (span.start >= afterTo) {
            val shift = (beforeTo - beforeFrom) + (afterTo - afterFrom)
            return TextSpan(span.start - shift, span.end - shift)
        }
        return null
    }
}
