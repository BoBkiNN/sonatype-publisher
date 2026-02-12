# sonatype-publisher

<!--suppress HtmlDeprecatedAttribute -->
<div align="center">

![JitPack](https://img.shields.io/jitpack/version/com.github.BoBkiNN/sonatype-publisher)

</div>

This simple plugin builds and uploads existing maven publication to Maven Central Repository
using [Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/)

> Tested on Gradle 8.14

## Usage:
1. Add and apply plugin
2. Configure extension:
```kotlin
// add imports
import xyz.bobkinn.sonatypepublisher.PublishingType
import xyz.bobkinn.sonatypepublisher.sonatypePublish

sonatypePublish {
    // specify maven central repository token username and password
    username = System.getenv("MAVEN_CENTRAL_USERNAME")
    password = System.getenv("MAVEN_CENTRAL_PASSWORD")
    publishingType = PublishingType.USER_MANAGED // or AUTOMATIC so deployments released when ready

    // using shortcut method to register central publish for maven publication and same name
    registerMaven(publishing.publications.named("main", MavenPublication::class))
}
```
3. Configure your publication with correct POM and signing to match maven central requirements
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

## General Deployment Tasks

These tasks provide **manual control and bulk automation** for Sonatype Portal deployments.
They are **not bound to specific project configurations** — instead they operate using stored deployment IDs and Sonatype credentials defined in your `SonatypePublishExtension`.

All tasks use credentials defined in your plugin extension:
```kotlin
sonatypePublish {
    username.set("your-username")
    password.set("your-password")
}
```

---

### 🔎 `checkDeployments`

Fetches the latest status of stored deployments and prints their current state.

**What it does**

* Updates deployment statuses from Sonatype Portal
* Prints details (state, name, errors if present)
* Can target a specific deployment or all stored ones

**Usage**

```bash
# Check all current deployments
./gradlew checkDeployments

# Check a specific deployment
./gradlew checkDeployments -PdeploymentId=<deploymentId>
```

**Behavior**

* Removes deployments no longer present on the portal
* Moves published deployments to the published list
* Updates statuses for active deployments

---

### 🚀 `publishDeployment`

Publishes a specific deployment using its deployment ID.

**Usage**

```bash
./gradlew publishDeployment -PdeploymentId=<deploymentId>
```

**Behavior**

* Triggers publish through the Sonatype Portal API
* Updates stored deployment state to `PUBLISHING`
* Fails if `deploymentId` is missing or blank

---

### ❌ `dropDeployment`

Drops a specific deployment from the portal.

**Usage**

```bash
./gradlew dropDeployment -PdeploymentId=<deploymentId>
```

**Behavior**

* Calls the Portal API to drop the deployment
* Removes it from the stored current deployment list

---

### 🧹 `dropFailedDeployments`

Fetches latest statuses and automatically drops all failed deployments.

**Usage**

```bash
./gradlew dropFailedDeployments
```

**Behavior**

* Updates deployment status first
* Drops only deployments marked as failed
* Saves updated deployment data afterward

---

### ✅ `publishValidatedDeployments`

Fetches latest statuses and publishes all validated deployments automatically.

**Usage**

```bash
./gradlew publishValidatedDeployments
```

**Behavior**

* Updates deployment status first
* Publishes deployments marked as validated
* Updates their state to `PUBLISHING`
* Saves updated deployment data

---
