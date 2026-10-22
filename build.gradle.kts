import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 2025.1 bundles Kotlin stdlib 2.1; don't use newer stdlib APIs -> https://jb.gg/intellij-platform-kotlin-stdlib
        // The "API version 2.1 is deprecated" warning is expected until sinceBuild moves past 251.
        apiVersion = KotlinVersion.KOTLIN_2_1
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Compile against the oldest supported platform so newer-only APIs fail the build.
        intellijIdeaCommunity("2025.1.7.2")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Oldest supported build plus the latest release of IDEs with different language support.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
            create(IntelliJPlatformType.WebStorm, "2026.2.3")
            create(IntelliJPlatformType.PyCharm, "2026.2.3")
            create(IntelliJPlatformType.GoLand, "2026.2.3")
            create(IntelliJPlatformType.AndroidStudio, "2026.2.1.8")
        }
    }
}

// Manual testing in other IDEs, e.g. `./gradlew runWebStorm` (Java/Kotlin files open as plain text there).
intellijPlatformTesting {
    runIde {
        register("runIdeaLatest") {
            type = IntelliJPlatformType.IntellijIdea
            version = "2026.2.3"
        }
        register("runWebStorm") {
            type = IntelliJPlatformType.WebStorm
            version = "2026.2.3"
        }
        register("runPyCharm") {
            type = IntelliJPlatformType.PyCharm
            version = "2026.2.3"
        }
        register("runAndroidStudio") {
            type = IntelliJPlatformType.AndroidStudio
            version = "2026.2.1.8"
        }
    }
}
