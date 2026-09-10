package io.github.effectnebula.eide.core.text

/**
 * Персистентный rope — неизменяемое сбалансированное дерево чанков текста.
 *
 * Зачем именно персистентный (ADR-005): фоновому потоку подсветки и LSP нужен снимок
 * документа, пока пользователь продолжает печатать. Здесь снимок стоит O(1) — это
 * ссылка на прежний корень; правка создаёт новые узлы только вдоль одного пути,
 * остальное дерево общее.
 *
 * Единица позиции — UTF-16 code unit, как в [String] и в протоколе LSP. Хранить UTF-8
 * было бы экономнее по памяти, но отрисовка идёт 60 раз в секунду и требует [String],
 * а парсинг — редко и в фоне; оптимизируем горячий путь.
 *
 * Офсеты не должны разрезать суррогатную пару: снаружи их даёт редактор, который
 * двигает курсор по графемам. Внутренние разрезы (склейка и балансировка) пару
 * не разрывают — см. [safeSplitPoint].
 */
class Rope private constructor(internal val root: Node) {

    /** Длина в UTF-16 code units. */
    val length: Int get() = root.length

    /** Количество строк: число переносов плюс один. Пустой текст — одна пустая строка. */
    val lineCount: Int get() = root.newlines + 1

    fun isEmpty(): Boolean = length == 0

    fun charAt(index: Int): Char {
        require(index in 0 until length) { "index $index вне [0, $length)" }
        var node = root
        var i = index
        while (true) {
            when (node) {
                is Leaf -> return node.text[i]
                is Branch -> if (i < node.left.length) {
                    node = node.left
                } else {
                    i -= node.left.length
                    node = node.right
                }
            }
        }
    }

    fun substring(start: Int, end: Int): String {
        requireRange(start, end)
        if (start == end) return ""
        val sb = StringBuilder(end - start)
        appendTo(sb, start, end)
        return sb.toString()
    }

    /** Дописывает срез в [sb] без промежуточных строк — так рисуются видимые строки. */
    fun appendTo(sb: StringBuilder, start: Int, end: Int) {
        requireRange(start, end)
        appendNode(root, start, end, sb)
    }

    /** Обходит чанки, покрывающие весь текст. Нужен для записи файла без склейки в одну строку. */
    fun forEachChunk(action: (String) -> Unit) {
        fun walk(n: Node) {
            when (n) {
                is Leaf -> if (n.text.isNotEmpty()) action(n.text)
                is Branch -> {
                    walk(n.left)
                    walk(n.right)
                }
            }
        }
        walk(root)
    }

    fun insert(offset: Int, text: String): Rope {
        require(offset in 0..length) { "offset $offset вне [0, $length]" }
        if (text.isEmpty()) return this
        val (left, right) = split(root, offset)
        return Rope(join(join(left, buildTree(text)), right))
    }

    fun delete(start: Int, end: Int): Rope {
        requireRange(start, end)
        if (start == end) return this
        val (left, rest) = split(root, start)
        val (_, right) = split(rest, end - start)
        return Rope(join(left, right))
    }

    fun replace(start: Int, end: Int, text: String): Rope = delete(start, end).insert(start, text)

    /** Номер строки (с нуля), в которой лежит [offset]. */
    fun lineOf(offset: Int): Int {
        require(offset in 0..length) { "offset $offset вне [0, $length]" }
        var node = root
        var at = offset
        var count = 0
        while (true) {
            when (node) {
                is Leaf -> return count + countNewlines(node.text, at)
                is Branch -> if (at <= node.left.length) {
                    node = node.left
                } else {
                    count += node.left.newlines
                    at -= node.left.length
                    node = node.right
                }
            }
        }
    }

    /** Офсет начала строки [line]. */
    fun lineStart(line: Int): Int {
        require(line in 0 until lineCount) { "строка $line вне [0, $lineCount)" }
        if (line == 0) return 0
        var node = root
        var k = line // ищем позицию сразу за k-м переносом
        var offset = 0
        while (true) {
            when (node) {
                is Leaf -> return offset + indexAfterNthNewline(node.text, k)
                is Branch -> if (node.left.newlines >= k) {
                    node = node.left
                } else {
                    k -= node.left.newlines
                    offset += node.left.length
                    node = node.right
                }
            }
        }
    }

    /**
     * Офсет конца строки [line], не включая перенос (и предшествующий ему `\r`, если файл
     * с виндовыми переносами).
     */
    fun lineEnd(line: Int): Int {
        require(line in 0 until lineCount) { "строка $line вне [0, $lineCount)" }
        if (line == lineCount - 1) return length
        val newlineAt = lineStart(line + 1) - 1
        return if (newlineAt > 0 && charAt(newlineAt - 1) == '\r') newlineAt - 1 else newlineAt
    }

    override fun toString(): String {
        val sb = StringBuilder(length)
        forEachChunk { sb.append(it) }
        return sb.toString()
    }

    private fun requireRange(start: Int, end: Int) {
        require(start in 0..length) { "start $start вне [0, $length]" }
        require(end in start..length) { "end $end вне [$start, $length]" }
    }

    // --- дерево ------------------------------------------------------------------

    internal sealed class Node {
        abstract val length: Int
        abstract val newlines: Int
        abstract val height: Int
    }

    internal class Leaf(val text: String) : Node() {
        override val length: Int = text.length
        override val newlines: Int = countNewlines(text, text.length)
        override val height: Int get() = 0
    }

    internal class Branch(val left: Node, val right: Node) : Node() {
        override val length: Int = left.length + right.length
        override val newlines: Int = left.newlines + right.newlines
        override val height: Int = 1 + maxOf(left.height, right.height)
    }

    companion object {
        /**
         * Максимальный размер листа. Меньше — глубже дерево и больше узлов;
         * больше — дороже правка внутри листа (копируется весь чанк).
         * Порядок величины выбран так, чтобы копирование листа было незаметным.
         */
        internal const val MAX_LEAF = 1024

        private val EMPTY_LEAF = Leaf("")

        val EMPTY: Rope = Rope(EMPTY_LEAF)

        fun of(text: String): Rope = if (text.isEmpty()) EMPTY else Rope(buildTree(text))

        private fun countNewlines(text: String, until: Int): Int {
            var n = 0
            for (i in 0 until until) if (text[i] == '\n') n++
            return n
        }

        private fun indexAfterNthNewline(text: String, n: Int): Int {
            var left = n
            for (i in text.indices) {
                if (text[i] == '\n' && --left == 0) return i + 1
            }
            error("в чанке меньше $n переносов — дерево рассогласовано")
        }

        /** Не даёт разрезать суррогатную пару. */
        private fun safeSplitPoint(text: String, at: Int): Int =
            if (at in 1 until text.length &&
                text[at - 1].isHighSurrogate() && text[at].isLowSurrogate()
            ) at + 1 else at

        /** Строит сбалансированное дерево из строки, нарезая её на листья. */
        private fun buildTree(text: String): Node {
            if (text.length <= MAX_LEAF) return Leaf(text)
            val leaves = ArrayList<Node>(text.length / MAX_LEAF + 1)
            var i = 0
            while (i < text.length) {
                val end = safeSplitPoint(text, minOf(i + MAX_LEAF, text.length))
                leaves += Leaf(text.substring(i, end))
                i = end
            }
            return merge(leaves, 0, leaves.size)
        }

        private fun merge(nodes: List<Node>, from: Int, to: Int): Node = when (to - from) {
            1 -> nodes[from]
            else -> {
                val mid = (from + to) / 2
                Branch(merge(nodes, from, mid), merge(nodes, mid, to))
            }
        }

        /**
         * Склейка двух деревьев с восстановлением AVL-баланса.
         * Спускаемся по правому (или левому) хребту до поддерева сравнимой высоты,
         * склеиваем там и на обратном пути чиним баланс поворотами.
         */
        internal fun join(l: Node, r: Node): Node = when {
            l.length == 0 -> r
            r.length == 0 -> l
            l is Leaf && r is Leaf && l.length + r.length <= MAX_LEAF -> Leaf(l.text + r.text)
            l.height > r.height + 1 -> {
                l as Branch
                balance(l.left, join(l.right, r))
            }
            r.height > l.height + 1 -> {
                r as Branch
                balance(join(l, r.left), r.right)
            }
            else -> Branch(l, r)
        }

        /** Чинит перекос ровно на две единицы — больше после [join] не бывает. */
        private fun balance(l: Node, r: Node): Node = when {
            l.height > r.height + 1 -> {
                l as Branch
                if (l.left.height >= l.right.height) {
                    Branch(l.left, Branch(l.right, r))
                } else {
                    val lr = l.right as Branch
                    Branch(Branch(l.left, lr.left), Branch(lr.right, r))
                }
            }
            r.height > l.height + 1 -> {
                r as Branch
                if (r.right.height >= r.left.height) {
                    Branch(Branch(l, r.left), r.right)
                } else {
                    val rl = r.left as Branch
                    Branch(Branch(l, rl.left), Branch(rl.right, r.right))
                }
            }
            else -> Branch(l, r)
        }

        private fun split(n: Node, at: Int): Pair<Node, Node> = when (n) {
            is Leaf -> Leaf(n.text.substring(0, at)) to Leaf(n.text.substring(at))
            is Branch -> if (at <= n.left.length) {
                val (a, b) = split(n.left, at)
                a to join(b, n.right)
            } else {
                val (a, b) = split(n.right, at - n.left.length)
                join(n.left, a) to b
            }
        }

        private fun appendNode(n: Node, start: Int, end: Int, sb: StringBuilder) {
            when (n) {
                is Leaf -> sb.append(n.text, start, end)
                is Branch -> {
                    val leftLen = n.left.length
                    if (start < leftLen) appendNode(n.left, start, minOf(end, leftLen), sb)
                    if (end > leftLen) {
                        appendNode(n.right, maxOf(start - leftLen, 0), end - leftLen, sb)
                    }
                }
            }
        }
    }
}
