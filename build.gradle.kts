plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.spotless)
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
    format("xml") {
        target("src/**/*.xml")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

dependencies {
    implementation(platform(libs.openai.client.bom))
    implementation(libs.openai.client)
    implementation(libs.slf4j.simple)
    implementation(libs.telegram.markdownv2.jvm)
    implementation(libs.vendeli.telegram.bot)

    runtimeOnly(libs.ktor.client.okhttp)

    ksp(libs.vendeli.ktnip)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "com.github.shikigami.App"
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
}
