import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jvm)
    `java-gradle-plugin`
    `maven-publish`
    `version-catalog`
    id("com.gradle.plugin-publish") version "1.2.1"
}

group = "xyz.bobkinn"
version = rootProject.findProperty("version") as String

repositories {
    mavenCentral()
    gradlePluginPortal()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    withJavadocJar()
    withSourcesJar()
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.gson)
    implementation(libs.okio)
    implementation(libs.okhttp)
    implementation(libs.okhttpLoggingInterceptor)
}

testing {
    suites {
        @Suppress("UnstableApiUsage")
        getByName("test", JvmTestSuite::class) {
            @Suppress("UnstableApiUsage")
            useKotlinTest(libs.versions.kotlinVersion.get())
        }
    }
}

gradlePlugin {
    website = "https://github.com/BoBkiNN/sonatype-maven-central-publisher.git"
    vcsUrl = "https://github.com/BoBkiNN/sonatype-maven-central-publisher.git"

    plugins.create("sonatype-publisher") {
        id = "xyz.bobkinn.sonatype-publisher"
        implementationClass = "xyz.bobkinn.sonatypepublisher.SonatypePublishPlugin"
        displayName = "Sonatype Publisher"
        description = "Simple gradle plugin for building and uploading bundles to the Sonatype Maven Central Repository."
        tags = listOf("maven", "maven-central", "publish", "sonatype")
    }
}

publishing.repositories {
    mavenLocal()
}

publishing.publications.create("main", MavenPublication::class) {
    from(components["java"])
}
