package io.github.effectnebula.eide.ui.editor

/**
 * Кэш разметки строк.
 *
 * Разметка (шейпинг) — самая дорогая часть отрисовки текста, и без кэша она
 * повторяется для каждой видимой строки на каждом кадре. Это и есть главный
 * подозреваемый в риске R3.
 *
 * Ключ — сам текст строки, а не её номер. Так разметка переживает вставку строки
 * выше по файлу: номера сдвинулись, содержимое нет.
 *
 * Вытеснение двухпоколенное: когда молодое поколение переполняется, оно становится
 * старым, а новое заводится пустым. Это грубее LRU, но у него нет операций, зависящих
 * от размера кэша, — а значит нет и всплеска на отдельном кадре, ради которого
 * всё и затевалось.
 */
internal class LineLayoutCache<V>(private val capacity: Int = 256) {

    private var young = HashMap<String, V>(capacity)
    private var old = HashMap<String, V>(capacity)

    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    fun get(line: String, measure: (String) -> V): V {
        young[line]?.let {
            hits++
            return it
        }
        old[line]?.let {
            // Ещё нужна — переводим в молодое поколение, чтобы не потерять на следующей смене.
            hits++
            put(line, it)
            return it
        }
        misses++
        return measure(line).also { put(line, it) }
    }

    fun clear() {
        young = HashMap(capacity)
        old = HashMap(capacity)
        hits = 0
        misses = 0
    }

    /** Сколько записей сейчас хранится — для тестов и диагностики. */
    val size: Int get() = young.size + old.size

    private fun put(line: String, layout: V) {
        if (young.size >= capacity) {
            old = young
            young = HashMap(capacity)
        }
        young[line] = layout
    }
}
