import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer

/**
 * Сверка скомпилированных классов с сигнатурами Android API 27 — нашего minSdk.
 *
 * Для модулей, где плагин animal-sniffer сам заводит задачи, этого не нужно
 * (JVM-модули — конвенция eide.android-jvm). Здесь — для остальных: KMP-модуля
 * и Android-модулей, где плагин задачи заводит неверно или не заводит вовсе.
 *
 * Зачем, если есть lint: **lint этого не ловит**, проверено опытом. `ProcessHandle`
 * (Java 9, на Android отсутствует вовсе) проходил полный build и в общем коде
 * `:ui`, и в Android-библиотеке `:platform:android`, в том числе при явном
 * `lintDebug`. `CompletableFuture.failedFuture` (Java 9, на Android с API 31)
 * тоже — а он был написан на самом деле, в автодополнении.
 *
 * Цена: проверка не понимает охраны вида `if (SDK_INT >= 28)`. Правильно
 * охраняемый вызов нового API она сочтёт ошибкой — такой вызов придётся
 * вынести и пометить явно. Пока таких нет.
 */
fun Project.registerAndroidApiCheck(
    classesDir: String,
    compileTask: String,
    classpathConfiguration: String,
    sourceDirs: List<String>,
) {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val signatureVersion = catalog.findVersion("androidSignature").get().requiredVersion
    val toolVersion = catalog.findVersion("animalsnifferTool").get().requiredVersion

    val signatures = configurations.create("androidApiSignature") {
        isCanBeConsumed = false
        isTransitive = false
    }
    val tool = configurations.create("animalsnifferTool") { isCanBeConsumed = false }
    dependencies.add(signatures.name, "net.sf.androidscents.signature:android-api-level-27:$signatureVersion@signature")
    dependencies.add(tool.name, "org.codehaus.mojo:animal-sniffer:$toolVersion")

    val check = tasks.register<AnimalSniffer>("animalsnifferAndroid") {
        description = "Сверяет классы Android-цели с сигнатурами Android API 27"
        group = "verification"
        dependsOn(compileTask)
        source = fileTree(layout.buildDirectory.dir(classesDir))
        // Классы зависимостей нужны, чтобы ссылки на Compose и androidx не
        // считались «неизвестными». Путь зависимостей состоит из AAR, которые
        // проверка прочесть не может, — берётся вид, где AGP достал из них jar.
        classpath = configurations.getByName(classpathConfiguration).incoming.artifactView {
            attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
        }.files
        animalsnifferSignatures = signatures
        animalsnifferClasspath = tool
        // Исходники нужны отчёту: по ним он называет файл и строку, а не класс.
        sourcesDirs = files(sourceDirs)
        reports.text.required.set(true)
        reports.text.outputLocation.set(layout.buildDirectory.file("reports/animalsniffer/android.text"))
        reports.csv.required.set(false)
        reports.csv.outputLocation.set(layout.buildDirectory.file("reports/animalsniffer/android.csv"))
    }

    tasks.named("check") { dependsOn(check) }
}
