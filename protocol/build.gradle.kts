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
    // Ed25519 verification, for a firmware image's signature (Signatures.kt). BouncyCastle's
    // own: pure Java, so the same on the JVM the tests run on and on every Android the app does.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
    useJUnitPlatform()
    // CI points this at the specification's own vectors; by default the tests read the copy here.
    System.getenv("TERN_COMPANION_VECTORS")?.let { systemProperty("tern.companion.vectors", it) }
    System.getenv("TERN_SHARING_VECTORS")?.let { systemProperty("tern.sharing.vectors", it) }
    System.getenv("TERN_GROUPS_VECTORS")?.let { systemProperty("tern.groups.vectors", it) }
    System.getenv("TERN_ROUTING_VECTORS")?.let { systemProperty("tern.routing.vectors", it) }
    System.getenv("TERN_MANAGING_VECTORS")?.let { systemProperty("tern.managing.vectors", it) }
}
