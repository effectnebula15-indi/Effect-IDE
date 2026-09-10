package io.github.effectnebula.eide.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Вписывание кадра и обратный пересчёт точки.
 *
 * Прямой и обратный ход обязаны сходиться: если они разойдутся, палец в
 * программе будет попадать не туда, куда его поставил человек, и искать
 * причину он будет в своём коде.
 */
class FitTest {

    @Test
    fun `content keeps its proportions inside a wide viewport`() {
        val fit = fitInside(480, 800, viewportWidth = 1000f, viewportHeight = 800f)

        assertEquals(480, fit.size.width)
        assertEquals(800, fit.size.height)
        // По центру: чёрные поля слева и справа одинаковые.
        assertEquals(260, fit.offset.x)
        assertEquals(0, fit.offset.y)
    }

    @Test
    fun `a point in the black bars belongs to no one`() {
        val fit = fitInside(480, 800, 1000f, 800f)

        assertNull(pointInContent(fit, 480, 800, x = 10f, y = 400f), "поле слева отдали программе")
        assertNull(pointInContent(fit, 480, 800, x = 990f, y = 400f), "поле справа отдали программе")
    }

    @Test
    fun `corners map to corners`() {
        val fit = fitInside(480, 800, 1000f, 800f)

        assertEquals(ContentPoint(0, 0), pointInContent(fit, 480, 800, x = 260f, y = 0f))
        assertEquals(
            ContentPoint(479, 799),
            pointInContent(fit, 480, 800, x = 260f + 479.9f, y = 799.9f),
            "правый нижний угол не попал в последний пиксель",
        )
    }

    @Test
    fun `the round trip agrees at every pixel`() {
        // Прямой ход масштабирует, обратный делит. Расхождение накапливается
        // именно на краях пикселей, поэтому проверяется каждый.
        val fit = fitInside(64, 48, viewportWidth = 640f, viewportHeight = 480f)
        val scale = fit.size.width / 64f

        for (pixel in 0 until 64) {
            // Середина пикселя обязана попасть в него же.
            val middle = fit.offset.x + (pixel + 0.5f) * scale
            assertEquals(
                pixel,
                pointInContent(fit, 64, 48, x = middle, y = fit.offset.y + 1f)?.x,
                "середина пикселя $pixel попала не туда",
            )
        }
    }

    @Test
    fun `an empty viewport gives nothing`() {
        val fit = fitInside(480, 800, 0f, 0f)

        assertEquals(0, fit.size.width)
        assertNull(pointInContent(fit, 480, 800, x = 0f, y = 0f))
    }
}
