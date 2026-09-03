plugins {
    id("eide.compose-jvm-app")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":platform:api"))
    implementation(project(":platform:desktop"))
    implementation(project(":runner"))

    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "io.github.effectnebula.eide.app.MainKt"
    }
}

// Пробрасываем свойство замера в JVM приложения: -Deide.benchmarkSeconds=15
// на командной строке Gradle иначе достанется самому Gradle, а не программе.
tasks.withType<JavaExec>().configureEach {
    System.getProperty("eide.benchmarkSeconds")?.let {
        systemProperty("eide.benchmarkSeconds", it)
    }
    // На машине без GPU (например, под Xvfb) Skiko не поднимет GL-контекст.
    System.getProperty("skiko.renderApi")?.let { systemProperty("skiko.renderApi", it) }
    // Иначе вывод замера приезжает вопросительными знаками там, где кириллица.
    systemProperty("file.encoding", "UTF-8")
    systemProperty("stdout.encoding", "UTF-8")
}
