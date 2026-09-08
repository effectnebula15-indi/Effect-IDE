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
