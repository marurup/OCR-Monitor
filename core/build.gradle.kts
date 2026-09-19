import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// core/ holds no Android imports. Everything here is plain Kotlin so the
// reading pipeline stays testable on the JVM and portable if a second
// platform ever earns its place. See docs/DESIGN.md section 3.
//
// Targets Java 17 rather than pinning a toolchain: the Android build consumes
// this module's bytecode, and D8 does not accept class files newer than 17.
// Using jvmTarget instead of jvmToolchain lets the module build on any JDK 17
// or later without needing that exact version installed.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

dependencies {
    testImplementation(libs.junit)
}
