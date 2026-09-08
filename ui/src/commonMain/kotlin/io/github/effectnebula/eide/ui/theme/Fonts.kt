package io.github.effectnebula.eide.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * Шрифт кода.
 *
 * Задаётся снаружи, а не грузится здесь: загрузка шрифта на Android идёт из
 * assets, на десктопе — из ресурсов classpath, и это разные API. Держать в
 * `:ui` две реализации ради двух строчек — дороже, чем передать готовое
 * семейство из точки сборки.
 *
 * Значение по умолчанию — системный моноширинный. Это не заглушка «на потом»:
 * если файл шрифта не прочитался, редактор обязан остаться читаемым, а не
 * пропасть.
 *
 * Тип указан явно: `FontFamily.Monospace` — это `GenericFontFamily`, и без
 * указания локаль сузилась бы до него, а подставить настоящее семейство стало
 * бы нельзя.
 */
val LocalEditorFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
