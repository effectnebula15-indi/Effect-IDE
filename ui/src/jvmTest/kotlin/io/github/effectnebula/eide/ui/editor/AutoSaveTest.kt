package io.github.effectnebula.eide.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * Когда автосохранение пишет и, что важнее, когда не пишет.
 *
 * Лишняя запись на телефоне — износ флеш-памяти и сбитое время изменения файла.
 * Отложенная запись — потерянный текст. Обе беды растут из одного корня: если
 * считать ревизии редактора, а не версии документа, движение курсора и пишет
 * впустую, и отодвигает настоящую запись.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoSaveTest {

    private val quiet = 1_000L

    @Test
    fun `an edit is written after the pause`() = runTest {
        val revisions = MutableSharedFlow<Long>(replay = 1)
        var version = 0L
        val saved = mutableListOf<Long>()

        val job = launch { saveSignals(revisions, quiet) { version }.collect { saved += it } }
        revisions.emit(0)
        advanceTimeBy(quiet + 1)

        version = 1
        revisions.emit(1)
        advanceTimeBy(quiet + 1)

        assertEquals(listOf(0L, 1L), saved, "правка не дошла до записи")
        job.cancel()
    }

    @Test
    fun `moving the caret writes nothing`() = runTest {
        val revisions = MutableSharedFlow<Long>(replay = 1)
        val version = 7L
        val saved = mutableListOf<Long>()

        val job = launch { saveSignals(revisions, quiet) { version }.collect { saved += it } }
        repeat(5) { index ->
            // Ревизия растёт, версия документа стоит — это и есть движение курсора.
            revisions.emit(index.toLong())
            advanceTimeBy(quiet + 1)
        }

        assertEquals(listOf(7L), saved, "движение курсора дало лишние записи")
        job.cancel()
    }

    @Test
    fun `moving the caret does not postpone writing an edit`() = runTest {
        // Хуже лишней записи: человек напечатал, потом водит курсором — и пауза
        // отсчитывается заново, пока он это делает. Текст в это время не записан.
        val revisions = MutableSharedFlow<Long>(replay = 1)
        var version = 0L
        val saved = mutableListOf<Long>()

        val job = launch { saveSignals(revisions, quiet) { version }.collect { saved += it } }
        revisions.emit(0)
        advanceTimeBy(quiet + 1)
        saved.clear()

        version = 1
        revisions.emit(1)

        // Курсор ходит чаще, чем длится пауза: со счётом по ревизиям запись
        // не случилась бы ни разу.
        repeat(10) { index ->
            advanceTimeBy(quiet / 2)
            revisions.emit(100L + index)
        }

        assertEquals(listOf(1L), saved, "движение курсора отложило запись правки")
        job.cancel()
    }

    @Test
    fun `only the last version of a burst is written`() = runTest {
        // Набор текста — это поток правок; писать файл на каждую букву незачем.
        val revisions = MutableSharedFlow<Long>(replay = 1)
        var version = 0L
        val saved = mutableListOf<Long>()

        val job = launch { saveSignals(revisions, quiet) { version }.collect { saved += it } }
        repeat(5) { index ->
            version = index + 1L
            revisions.emit(version)
            advanceTimeBy(quiet / 4)
        }
        advanceTimeBy(quiet + 1)

        assertEquals(listOf(5L), saved, "запись случилась не один раз за очередь правок")
        job.cancel()
    }
}
