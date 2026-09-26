package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker

/** Графемы по одному char: для этих тестов эмодзи посреди курсора не важны. */
private object CharBreaker : GraphemeBreaker {
    override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
    override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
}

internal fun testEditor(text: String): EditorState = EditorState(Document(Rope.of(text)), CharBreaker)
