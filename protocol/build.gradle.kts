plugins {
    kotlin("jvm")
}

// Java 17 bytecode, which is what an Android app compiles against; any JDK from 17 builds it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
    useJUnitPlatform()
    // CI points this at the specification's own vectors; by default the tests read the copy here.
    System.getenv("TERN_COMPANION_VECTORS")?.let { systemProperty("tern.companion.vectors", it) }
    System.getenv("TERN_SHARING_VECTORS")?.let { systemProperty("tern.sharing.vectors", it) }
}
