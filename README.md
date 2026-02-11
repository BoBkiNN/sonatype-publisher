# Simple gradle plugin to publish maven publication to Sonatype Central Portal

This plugin builds and uploads existing maven publication to Maven Central Repository
using [Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/)

## Usage:
1. Add and apply plugin
2. Configure extension:
```kotlin
extensions.configure(SonatypePublishExtension::class) {
    username = System.getenv("MAVEN_CENTRAL_USERNAME")
    password = System.getenv("MAVEN_CENTRAL_PASSWORD")
    publishingType = PublishingType.USER_MANAGED

    registerMaven(publishing.publications.named("main", MavenPublication::class))
}
```
3. Configure your publication with correct POM and setup signing
4. Run task `publish<publication name>ToSonatype`

## Adding to project:

**Java 17 or later required**<br>
Setup JitPack plugin repository in `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        maven("https://jitpack.io") // add JitPack repository
        gradlePluginPortal()
    }

    // optional resolution strategy to use correct id
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.bobkinn.sonatype-publisher") {
                useModule("com.github.BoBkiNN:sonatype-maven-central-publisher:${requested.version}")
            }
        }
    }
}
```

Add and apply plugin in `build.gradle.kts`:
```kotlin
plugins {
    id("io.github.bobkinn.sonatype-publisher") version "2.0.1"
}
```

## About fork:
Based on [sonatype-maven-central-publisher](https://github.com/ani2fun/sonatype-maven-central-publisher),
I have introduced critical changes, code improvements and fixes, 
targeting different goal to work with existing publications.

Fixed issues:
- [#4 - windows support](https://github.com/ani2fun/sonatype-maven-central-publisher/issues/4)
- [#3 - Usage of addLast](https://github.com/ani2fun/sonatype-maven-central-publisher/issues/3)

There are also more plans for plugin in [TODO file](/TODO.md)
