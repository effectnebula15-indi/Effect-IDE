package io.github.effectnebula.eide.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.lsp.CompletionItem
import io.github.effectnebula.eide.core.lsp.PositionEncoding
import io.github.effectnebula.eide.core.lsp.completionPrefix
import io.github.effectnebula.eide.core.lsp.completionWordStart
import io.github.effectnebula.eide.core.lsp.edit
import io.github.effectnebula.eide.core.lsp.matching
import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Rope
import java.util.concurrent.CompletableFuture

/**
 * Откуда брать варианты.
 *
 * Редактор не знает ни про LSP, ни про процессы: ему дают текст и позицию,
 * он получает варианты. Сервер на десктопе, сервер на ПК по сети, поддельный
 * источник в тесте — для редактора одно и то же.
 *
 * [encoding] нужен затем, что участок замены сервер называет в своей
 * кодировке столбцов (см. `LspClient.positionEncoding`).
 */
interface CompletionSource {
    val encoding: PositionEncoding
    fun complete(text: Rope, caret: Int): CompletableFuture<List<CompletionItem>>
}

/**
 * Состояние списка автодополнения.
 *
 * Логика — в обычных методах, а не в composable: её проверяют без экрана, а
 * экран только показывает [visible] и [shown].
 *
 * **Опоздавший ответ отбрасывается.** Запрос уходит, человек печатает дальше
 * или уводит курсор на другую строку; ответ, пришедший после, относится к
 * месту, где курсора уже нет. Каждому запросу — поколение, и ответ не своего
 * поколения не показывается.
 */
class CompletionController {

    /** Всё, что прислал сервер на последний запрос. */
    private var received: List<CompletionItem> by mutableStateOf(emptyList())

    /** Где начиналось дополняемое слово в момент запроса. */
    private var anchor = -1
    private var anchorLine = -1
    private var generation = 0
    private var waitingFor = -1

    /**
     * Запрос ушёл, ответа ещё нет.
     *
     * Отдельно от [visible] затем, что Esc обязан отменять и ожидание: иначе
     * человек жмёт Esc, а через мгновение список всё равно выскакивает — у
     * медленного сервера так и бывает. Первая редакция этого не знала.
     */
    val waiting: Boolean get() = waitingFor == generation

    var visible: Boolean by mutableStateOf(false)
        private set

    var selected: Int by mutableIntStateOf(0)
        private set

    /** Что показывать сейчас: присланное, доотобранное по дописанному. */
    var shown: List<CompletionItem> by mutableStateOf(emptyList())
        private set

    /**
     * Спрашивает источник о вариантах в позиции курсора.
     *
     * Возвращает будущее, которое завершится, когда ответ будет разобран, —
     * чтобы тот, кто ждёт на своём потоке, мог дождаться. Применять ответ
     * должен поток интерфейса: [accept] вызывает тот, кто его дождался.
     */
    fun request(state: EditorState, source: CompletionSource): CompletableFuture<List<CompletionItem>?> {
        val caret = state.carets.primary.head
        anchor = completionWordStart(state.text, caret)
        anchorLine = state.text.lineOf(caret)
        val mine = ++generation
        waitingFor = mine
        // null — ответ опоздал: за это время был новый запрос или отмена.
        // Сбой сервера для текущего запроса — пустой ответ: ожидание снимается,
        // и список не открывается. Молча, потому что автодополнение — подсказка,
        // а не операция, о провале которой надо сообщать.
        //
        // Проваленное будущее собирается руками, а не `failedFuture`: тот — Java 9,
        // и на телефоне его нет до API 31. Первая редакция его и позвала.
        val answer = runCatching { source.complete(state.text, caret) }.getOrElse { error ->
            CompletableFuture<List<CompletionItem>>().apply { completeExceptionally(error) }
        }
        return answer.handle { items, _ -> if (mine == generation) items.orEmpty() else null }
    }

    /**
     * Показывает ответ на последний запрос — в потоке интерфейса.
     *
     * Пустой ответ список не открывает: пустая рамка у курсора — это шум,
     * а не «вариантов нет».
     */
    fun show(state: EditorState, items: List<CompletionItem>?) {
        if (items == null) return
        waitingFor = -1
        received = items
        selected = 0
        refresh(state)
    }

    /**
     * Доотбор после каждой правки или движения курсора.
     *
     * Список закрывается сам, если курсор ушёл левее начала слова или на
     * другую строку — дополнять там уже нечего — или если под дописанное
     * не подошёл ни один вариант.
     */
    fun refresh(state: EditorState) {
        val caret = state.carets.primary.head
        if (caret < anchor || state.text.lineOf(caret) != anchorLine) return hide()

        val narrowed = received.matching(completionPrefix(state.text, caret))
        if (narrowed.isEmpty()) return hide()

        shown = narrowed
        selected = selected.coerceIn(0, narrowed.size - 1)
        visible = true
    }

    fun move(delta: Int) {
        if (!visible || shown.isEmpty()) return
        // По кругу, как в палитре: список короткий, упираться в край незачем.
        selected = (selected + delta + shown.size) % shown.size
    }

    /** Вставляет выбранный вариант. Возвращает false, если вставлять нечего. */
    fun accept(state: EditorState, encoding: PositionEncoding, index: Int = selected): Boolean {
        val item = shown.getOrNull(index) ?: return false
        val caret = state.carets.primary.head
        val replacement = item.edit(state.text, caret, encoding)

        // Курсор встаёт за вставленным сам — даже если участок сервера тянулся
        // правее курсора: офсет внутри заменённого уезжает за вставленный текст
        // (`EditTransaction.mapOffset`, правило ядра и проверено там). Первая
        // редакция ставила курсор ещё и руками; мутация показала, что это повтор.
        state.replaceAll(EditTransaction(listOf(replacement)))
        hide()
        return true
    }

    fun hide() {
        visible = false
        received = emptyList()
        shown = emptyList()
        generation++
    }
}
