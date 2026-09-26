package io.github.effectnebula.eide.ui.canvas

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Куда и какого размера рисовать содержимое внутри вьюпорта. */
data class FitResult(val offset: IntOffset, val size: IntSize)

/**
 * Вписывает содержимое во вьюпорт целиком, сохраняя пропорции, и центрирует.
 *
 * Растянуть по экрану было бы проще, но тогда квадрат в программе пользователя
 * перестаёт быть квадратом, и человек ищет ошибку в своём коде.
 *
 * Живёт в `:ui`, хотя пользуется этим пока только Android: арифметика от
 * платформы не зависит, а проверить её можно только здесь — в модуле
 * приложения тестов нет и заводить их ради десяти строк незачем.
 */
fun fitInside(
    contentWidth: Int,
    contentHeight: Int,
    viewportWidth: Float,
    viewportHeight: Float,
): FitResult {
    if (contentWidth <= 0 || contentHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
        return FitResult(IntOffset.Zero, IntSize(0, 0))
    }

    val scale = minOf(viewportWidth / contentWidth, viewportHeight / contentHeight)
    val width = (contentWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (contentHeight * scale).roundToInt().coerceAtLeast(1)

    return FitResult(
        offset = IntOffset(
            x = ((viewportWidth - width) / 2f).roundToInt(),
            y = ((viewportHeight - height) / 2f).roundToInt(),
        ),
        size = IntSize(width, height),
    )
}

/** Точка в координатах содержимого — то есть в тех, в которых рисует программа. */
data class ContentPoint(val x: Int, val y: Int)

/**
 * Обратный пересчёт: точка окна в координаты содержимого.
 *
 * Нужен для ввода. Программа рисует в своей системе координат и про окно ничего
 * не знает — значит пересчитывать должен тот, кто вписывал кадр.
 *
 * Возвращает null для точки вне картинки: событие за её пределами — это не
 * событие с обрезанной координатой, а событие, которого для программы не было.
 * Разница видна на пальце, попавшем в чёрное поле сбоку: программа не должна
 * получить нажатие у самого края.
 */
fun pointInContent(
    fit: FitResult,
    contentWidth: Int,
    contentHeight: Int,
    x: Float,
    y: Float,
): ContentPoint? {
    if (fit.size.width <= 0 || fit.size.height <= 0) return null
    if (contentWidth <= 0 || contentHeight <= 0) return null

    val insideX = x - fit.offset.x
    val insideY = y - fit.offset.y
    if (insideX < 0f || insideY < 0f) return null
    if (insideX >= fit.size.width || insideY >= fit.size.height) return null

    // toInt, а не roundToInt: пиксель занимает полуинтервал, и округление
    // к ближайшему отдаёт последнему пикселю половину ширины соседа.
    val contentX = (insideX / fit.size.width * contentWidth).toInt()
    val contentY = (insideY / fit.size.height * contentHeight).toInt()

    return ContentPoint(
        x = contentX.coerceIn(0, contentWidth - 1),
        y = contentY.coerceIn(0, contentHeight - 1),
    )
}
