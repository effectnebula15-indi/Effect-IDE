package io.github.effectnebula.eide.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Разбор `-Deide.open`.
 *
 * Проверка выглядит мелочью, но именно эта склейка стоила часа: приложение
 * с абсолютным путём молча зависало, потому что искало файл по
 * `/проект/абсолютный/путь`, а сообщить об этом было некому.
 */
class OpenTargetTest {

    private val root = File("/проект")

    @Test
    fun `a relative path is resolved against the project`() {
        assertEquals(File("/проект/src/main.py"), openTarget(root, "src/main.py"))
    }

    @Test
    fun `an absolute path stays as it is`() {
        assertEquals(File("/tmp/чужое/main.py"), openTarget(root, "/tmp/чужое/main.py"))
    }
}
