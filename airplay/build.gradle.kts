plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Android is the only consumer, and it runs from API 26. Pinning javac to the
// Java 8 API surface makes a call to a newer JDK method (InputStream.readAllBytes,
// List.of, ...) a compile error here rather than a NoSuchMethodError on a TV:
// these are plain JVM modules, so Android Lint never inspects them.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

dependencies {
    api(libs.slf4j.api)

    implementation(libs.netty.codec.http)
    implementation(libs.jmdns)
    implementation(libs.dd.plist)
    implementation(libs.eddsa)
    implementation(libs.curve25519)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
