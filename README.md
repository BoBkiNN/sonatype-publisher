# Simple gradle plugin to publish maven publication to Sonatype Central Portal

## Usage:
1. Add and apply plugin
2. Configure extension:
```kotlin
extensions.configure(SonatypeCentralPublishExtension::class) {
        this.publication.set(publishing.publications["main"] as MavenPublication)
        username = System.getenv("MAVEN_CENTRAL_USERNAME")
        password = System.getenv("MAVEN_CENTRAL_PASSWORD")
        publishingType = PublishingType.USER_MANAGED
    }
```
3. Configure your publication with correct POM and setup signing
4. Run task `publish<publication name>ToSonatype`

## Adding to project:

Setup JitPack
`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
    }

    // optional resolution strategy to use correct id
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "xyz.bobkinn.sonatype-publisher") {
                useModule("com.github.BoBkiNN:sonatype-maven-central-publisher:${requested.version}")
            }
        }
    }
}
```

`build.gradle.kts`:
```kotlin
plugins {
    id("xyz.bobkinn.sonatype-publisher") version "1.2.5"
}
```