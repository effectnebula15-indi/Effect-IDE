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
