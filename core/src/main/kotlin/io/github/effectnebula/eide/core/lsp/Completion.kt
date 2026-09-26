package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.text.Replacement
import io.github.effectnebula.eide.core.text.Rope

/**
 * Порядок вариантов: по `sortText`, при равенстве — по подписи.
 *
 * Порядок в массиве ответа спецификация не обещает, а сервер выражает свой
 * замысел именно через `sortText`: pylsp, например, ставит в начало буквы,
 * которые отделяют открытые имена от служебных.
 */
val COMPLETION_ORDER: Comparator<CompletionItem> =
    compareBy<CompletionItem> { it.sortText ?: it.label }.thenBy { it.label }

/**
 * Начало дополняемого слова: назад от курсора по буквам, цифрам и `_`.
 *
 * Буквы — любые, не только латинские: `значение_x` в Python — законное имя, и
 * начинающий пишет именно так. `isLetterOrDigit` это различает.
 */
fun completionWordStart(text: Rope, caret: Int): Int {
    val safe = caret.coerceIn(0, text.length)
    val lineStart = text.lineStart(text.lineOf(safe))
    var start = safe
    while (start > lineStart && text.charAt(start - 1).isIdentifierPart()) start--
    return start
}

/** Уже набранное начало слова перед курсором. */
fun completionPrefix(text: Rope, caret: Int): String {
    val safe = caret.coerceIn(0, text.length)
    return text.substring(completionWordStart(text, safe), safe)
}

/**
 * Варианты, которые подходят к дописанному.
 *
 * Сервер отбирает по тому, что было набрано на момент запроса; человек тем
 * временем печатает дальше. Спрашивать сервер на каждую букву — это очередь
 * запросов и мигающий список, поэтому уже полученное доотбирается здесь.
 * Без учёта регистра: `Pa` должно находить `path`.
 */
fun List<CompletionItem>.matching(prefix: String): List<CompletionItem> {
    if (prefix.isEmpty()) return this
    val fitting = filter { (it.filterText ?: it.label).startsWith(prefix, ignoreCase = true) }

    // Совпавшие с набранным в том же регистре — вперёд, остальное в прежнем
    // порядке. Найдено снимком экрана, а не тестом: на `os.pa` первым стоял
    // `PathLike` — `sortText` сравнивается с учётом регистра, и `P` меньше `p`.
    // А Enter вставляет первый: человек набрал строчное и получил заглавное.
    val (sameCase, otherCase) = fitting.partition { (it.filterText ?: it.label).startsWith(prefix) }
    return sameCase + otherCase
}

/**
 * Правка, которую делает выбор варианта.
 *
 * Если сервер назвал участок — меняем его, но **до курсора, а не до конца
 * названного**: участок считался, когда человек ещё не допечатал, и его правый
 * край остался позади. Если не назвал (pylsp не называет) — меняем набранное
 * начало слова.
 */
fun CompletionItem.edit(text: Rope, caret: Int, encoding: PositionEncoding): Replacement {
    val safe = caret.coerceIn(0, text.length)
    val range = replaces
    if (range != null) {
        val start = text.offsetOf(range.start, encoding)
        if (start <= safe) return Replacement(start, maxOf(safe, text.offsetOf(range.end, encoding)), insertText)
    }
    return Replacement(completionWordStart(text, safe), safe, insertText)
}

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'
