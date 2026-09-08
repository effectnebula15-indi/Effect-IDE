package io.github.effectnebula.eide.ui.canvas

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Вписывание канвы в экран — арифметика, в которой живут ошибки на единицу
 * и перепутанные оси. На экране это выглядит как «квадрат почему-то
 * прямоугольный», и объяснение ищут в коде программы, а не здесь.
 */
class FitTest {

    @Test
    fun `content fills the viewport when proportions match`() {
        val fit = fitInside(480, 800, 960f, 1600f)

        assertEquals(IntSize(960, 1600), fit.size)
        assertEquals(IntOffset(0, 0), fit.offset)
    }

    @Test
    fun `wide viewport leaves fields on the sides`() {
        val fit = fitInside(480, 800, 2000f, 1600f)

        assertEquals(IntSize(960, 1600), fit.size, "масштаб выбран не по узкой стороне")
        assertEquals(IntOffset(520, 0), fit.offset, "содержимое не по центру")
    }

    @Test
    fun `tall viewport leaves fields above and below`() {
        val fit = fitInside(480, 800, 960f, 2000f)

        assertEquals(IntSize(960, 1600), fit.size)
        assertEquals(IntOffset(0, 200), fit.offset)
    }

    @Test
    fun `proportions are preserved on any viewport`() {
        val source = 480f / 800f

        for (width in listOf(100f, 333f, 1080f, 4000f)) {
            for (height in listOf(100f, 640f, 1777f, 3000f)) {
                val fit = fitInside(480, 800, width, height)
                val ratio = fit.size.width.toFloat() / fit.size.height
                // Округление до целых пикселей — единственный источник расхождения.
                assertTrue(
                    kotlin.math.abs(ratio - source) < 0.02f,
                    "пропорции поехали на $width×$height: $ratio вместо $source",
                )
            }
        }
    }

    @Test
    fun `content never sticks out of the viewport`() {
        for (width in listOf(1f, 17f, 1080f)) {
            for (height in listOf(1f, 17f, 1920f)) {
                val fit = fitInside(480, 800, width, height)
                assertTrue(fit.size.width <= width + 1, "шире вьюпорта на $width×$height")
                assertTrue(fit.size.height <= height + 1, "выше вьюпорта на $width×$height")
                assertTrue(fit.offset.x >= 0 && fit.offset.y >= 0, "смещение отрицательное")
            }
        }
    }

    @Test
    fun `degenerate sizes give nothing to draw instead of dividing by zero`() {
        assertEquals(IntSize(0, 0), fitInside(0, 800, 100f, 100f).size)
        assertEquals(IntSize(0, 0), fitInside(480, 0, 100f, 100f).size)
        assertEquals(IntSize(0, 0), fitInside(480, 800, 0f, 100f).size)
        assertEquals(IntSize(0, 0), fitInside(480, 800, 100f, 0f).size)
    }
}
