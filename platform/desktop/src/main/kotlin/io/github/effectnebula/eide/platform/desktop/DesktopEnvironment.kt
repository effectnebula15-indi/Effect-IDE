package io.github.effectnebula.eide.platform.desktop

/**
 * Проверки окружения, без которых редактор будет вести себя необъяснимо.
 */
object DesktopEnvironment {

    /**
     * Кодировка, которой JVM переводит имена файлов в байты операционной системы.
     *
     * Это НЕ `file.encoding`. С Java 18 `file.encoding` по умолчанию UTF-8, а имена
     * файлов по-прежнему кодируются по локали: при `LANG=POSIX` получается ASCII.
     */
    val fileNameEncoding: String =
        System.getProperty("sun.jnu.encoding") ?: System.getProperty("native.encoding") ?: "неизвестна"

    val fileNamesAreUtf8: Boolean =
        fileNameEncoding.replace("-", "").equals("UTF8", ignoreCase = true)

    /**
     * Предупреждение, если имена файлов не в UTF-8, иначе null.
     *
     * Проблема настоящая и тихая: при неюникодной локали JVM записывает имя
     * «заметки.txt» на диск как «????????.txt». Файл создаётся, но открыть его по
     * исходному имени уже нельзя, а пользователь видит только вопросительные знаки.
     *
     * На Android этого не бывает — там имена всегда UTF-8. Это чисто настольная беда,
     * и лечится она запуском JVM с юникодной локалью (`LANG=C.UTF-8`), а не из кода:
     * `sun.jnu.encoding` читается при старте JVM и после этого не меняется.
     */
    fun fileNameWarning(): String? = if (fileNamesAreUtf8) null else
        "Имена файлов кодируются как $fileNameEncoding, а не UTF-8. " +
            "Файлы с не-латинскими именами будут открываться неправильно. " +
            "Запустите приложение с локалью UTF-8, например LANG=C.UTF-8."
}
