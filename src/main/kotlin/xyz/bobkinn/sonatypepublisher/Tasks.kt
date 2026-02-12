package xyz.bobkinn.sonatypepublisher

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Provider
import org.gradle.api.publish.internal.PublicationInternal
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.*
import xyz.bobkinn.sonatypepublisher.utils.HashUtils
import xyz.bobkinn.sonatypepublisher.utils.PublisherApi
import xyz.bobkinn.sonatypepublisher.utils.ZipUtils
import java.io.File
import javax.inject.Inject

abstract class BuildPublicationArtifacts
    @Inject
    constructor(
        @Internal val publication: Provider<MavenPublication>,
        @Internal val additionalTasks: List<String>,
    ) : DefaultTask() {
        init {
            val publication = publication.get()
            if (publication is PublicationInternal<*>) {
                val tasks = publication.publishableArtifacts.map {
                    it.buildDependencies.getDependencies(null)
                }
                dependsOn(tasks)
                logger.debug("Publication depends on tasks: {}",
                    tasks.map { it.map { it.path }.toString() })
            }
            dependsOn(*additionalTasks.toTypedArray())

            group = TASKS_GROUP
            description = "Aggregator task to build publication artifacts"
        }

    }

abstract class AggregateFiles
@Inject constructor(
    @Internal val publication: Provider<MavenPublication>,
    @OutputDirectory
    val targetDirectory: Provider<Directory>
) : DefaultTask() {

    init {
        group = TASKS_GROUP
        description = "Aggregate all publishable artifacts into a temporary directory with proper names."
    }

    @TaskAction
    fun action() {
        val folder = targetDirectory.get().asFile
        if (folder.exists()) folder.deleteRecursively()
        folder.mkdirs()

        val publication = publication.get()

        val artifactId = publication.artifactId
        val version = publication.version

        // Copy and rename all publishable artifacts directly into temp dir
        val pub = publication as PublicationInternal<*>
        logger.lifecycle("Aggregating ${pub.publishableArtifacts.size} artifacts" +
                " into ${folder.relativeTo(project.rootDir)}")
        fun processArtifact(file: File, classifier: String?, extension: String) {
            var newName = when (file.name) {
                "module.json" -> "$artifactId-$version.module"
                "module.json.asc" -> "$artifactId-$version.module.asc"
                "pom-default.xml" -> "$artifactId-$version.pom"
                "pom-default.xml.asc" -> "$artifactId-$version.pom.asc"
                else -> file.name
            }
            if (file.name.endsWith(".jar.asc") || file.name.endsWith(".jar")) {
                val cls = classifier?.let { "-$it" } ?: ""
                newName = "$artifactId-$version$cls.${extension}"
//                println("were ${file.name}, become $newName")
            }
            val targetFile = folder.resolve(newName)
            file.copyTo(targetFile, overwrite = true)
            logger.debug("Copied artifact {} to {}", file, targetFile)
        }
        pub.publishableArtifacts.forEach { it ->
//            val producerTasks = it.buildDependencies.getDependencies(null)
//            println("Artifact ${it.file}, prod: $producerTasks")
            it as MavenArtifact
            processArtifact(it.file, it.classifier, it.extension)
        }
    }
}

abstract class ComputeHashes
    @Inject
    constructor(
        @Internal val directory: Provider<Directory>,
        @Internal val additionalAlgorithms: List<String>,
    ) : DefaultTask() {
        init {
            group = TASKS_GROUP
            description = "Compute Hash of all files in a temporary directory."
        }

    companion object {
        val REQUIRED_ALGORITHMS = listOf("MD5", "SHA-1")
    }

        @TaskAction
        fun run() {
            logger.debug("Writing file hashes at {}",
                directory.get().asFile.relativeTo(project.rootDir))
            HashUtils.writesFilesHashes(directory.get().asFile,
                REQUIRED_ALGORITHMS + additionalAlgorithms)
        }
    }

abstract class CreateZip @Inject constructor(
    @get:InputDirectory val fromDirectory: Provider<Directory>,
    @get:OutputFile val zipFile: Provider<RegularFile>
) : DefaultTask() {

    init {
        group = TASKS_GROUP
        description = "Creates a zip from aggregated and processed files"
    }

    @TaskAction
    fun createArchive() {
        val source = fromDirectory.get().asFile
        val target = zipFile.get().asFile

        logger.lifecycle("Creating zip file from: ${source.relativeTo(project.rootDir)}")
        target.parentFile.mkdirs()
        ZipUtils.prepareZipFile(source, target)
        logger.lifecycle("Zip created at ${target.relativeTo(project.rootDir)}")
    }
}

abstract class PublishToSonatypeCentral @Inject constructor(
    @InputFile
    val zipFile: Provider<RegularFile>,
    @Input
    val config: SonatypePublishConfig
) : DefaultTask() {

    init {
        group = TASKS_GROUP
        description = "Publish to New Sonatype Maven Central Repository."
    }

    @TaskAction
    fun uploadZip() {
        logger.lifecycle("Uploading ${config.name} to Sonatype..")
        val id = try {
            PublisherApi.uploadBundle(zipFile.get(), config.publishingType.get(),
                config.publication.get(), config.username.get(), config.password.get())
        } catch (e: PublisherApi.PortalApiError) {
            throw GradleException("Failed to perform upload", e)
        }
        logger.lifecycle("Publication uploaded with deployment id $id")

        logger.debug("Writing deployment status..")
        StoredDeploymentsManager.putCurrent(project, Deployment(id))
        logger.debug("Deployment data updated")
    }
}

// common tasks

abstract class GetDeploymentStatus : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Get deployment status using deploymentId"
    }

    @Input
    var deploymentId: String = project.findProperty("deploymentId")?.toString() ?: ""

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        logger.lifecycle("Getting deployment status for $deploymentId")
        if (deploymentId.isBlank()) throw IllegalArgumentException("No deployment id provided")
        val status = try {
            PublisherApi.getDeploymentStatus(deploymentId, extension.username.get(), extension.password.get())
        } catch (e: PublisherApi.PortalApiError) {
            throw GradleException("Failed to get deployment status", e)
        }
        val json = PublisherApi.gson.toJson(status)
        logger.lifecycle("Got deployment status:\n$json")

        logger.debug("Updating deployment data..")
        if (status != null && status.isPublished) {
            logger.debug("Moving deployment to published list")
            val dep = Deployment(deploymentId, status, System.currentTimeMillis())
            // put to published
            StoredDeploymentsManager.putPublished(project, dep)
            // remove from current
            StoredDeploymentsManager.removeCurrent(project, deploymentId)
        } else if (status != null) {
            logger.debug("Updating current deployments list..")
            // update current
            StoredDeploymentsManager.update(project, deploymentId) {
                it?.update(status) // update status if already listed
            }
        } else {
            logger.debug("Deployment not found, removing it from lists..")
            StoredDeploymentsManager.removeCurrent(project, deploymentId)
            StoredDeploymentsManager.removePublished(project, deploymentId)
        }
    }
}

abstract class DropDeployment : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Drop deployment using deploymentId."
    }

    @Input
    var deploymentId: String = project.findProperty("deploymentId")?.toString() ?: ""

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        logger.lifecycle("Dropping deployment for deploymentId=$deploymentId ...")
        try {
            PublisherApi.dropDeployment(deploymentId, extension.username.get(), extension.password.get())
        } catch (e: PublisherApi.PortalApiError) {
            throw GradleException("Failed to perform drop", e)
        }
        logger.lifecycle("Deployment $deploymentId dropped successfully")

        logger.debug("Removing deployment from current list..")
        StoredDeploymentsManager.removeCurrent(project, deploymentId)
    }
}

private fun getStatus(id: String, extension: SonatypePublishExtension): PublisherApi.DeploymentStatus? {
    return try {
        PublisherApi.getDeploymentStatus(id, extension.username.get(),
            extension.password.get())
    } catch (e: PublisherApi.PortalApiError) {
        throw GradleException("Failed to get deployment status", e)
    }
}

private fun fetchAndUpdateDeployments(dd: DeploymentsData, extension: SonatypePublishExtension) {
    val it = dd.current.values.iterator()
    while (it.hasNext()) {
        val d = it.next()
        val status = getStatus(d.id, extension)
        if (status == null) {
            // removed
            it.remove()
            dd.published.remove(d.id)
        } else if (status.isPublished) {
            // state changed to published
            it.remove()
            dd.published.put(d.id, d)
        } else {
            d.update(status)
        }
    }
}

fun updateGetDeployments(project: Project, logger: Logger): DeploymentsData {
    val extension = project.extensions.getByType(SonatypePublishExtension::class.java)
    val dd = StoredDeploymentsManager.load(project)
    logger.lifecycle("Fetching and updating ${dd.current.size} deployment(s)..")
    fetchAndUpdateDeployments(dd, extension)
    StoredDeploymentsManager.save(project, dd)
    return dd
}

abstract class CheckDeployments : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Update current deployments information and print its statuses." +
                "Pass deploymentId property to specify exact deployment"
    }

    @Input
    var deploymentId: String? = project.findProperty("deploymentId")?.toString()

    private fun logStatus(status: PublisherApi.DeploymentStatus) {
        logger.info("Deployment ${status.deploymentId} - ${status.deploymentState}:")
        logger.info("  Name: ${status.deploymentName}")
        val errorsJson = PublisherApi.gson.toJson(status.errors)
        logger.info("  Errors: $errorsJson")
    }

    private fun logDeployment(dep: Deployment) {
        if (dep.deployment != null) logStatus(dep.deployment)
        else logger.info("Deployment ${dep.id} - UNKNOWN")
    }

    @TaskAction
    fun executeTask() {
        val dd = updateGetDeployments(project, logger)
        deploymentId?.let {
            logger.info("--- Deployment $it status:")
            val dep = dd[it]
            if (dep == null) throw GradleException("No deployment with id $it stored")
            logDeployment(dep)
            return
        }
        if (dd.current.isEmpty()) {
            logger.info("No unreleased deployment IDs stored")
            return
        }
        logger.info("Status of ${dd.current.size} unreleased deployment(s):")
        for (dep in dd.current.values) {
            logDeployment(dep)
        }
    }
}

abstract class DropFailed : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Fetches and updates current deployments and then drops any failed current deployments"
    }

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        val dd = updateGetDeployments(project, logger)
        val username = extension.username.get()
        val password = extension.password.get()

        logger.lifecycle("Dropping failed deployments..")
        var c = 0
        for (dep in dd.current.values) {
            val status = dep.deployment ?: continue
            if (!status.isFailed) continue
            try {
                PublisherApi.dropDeployment(dep.id, username, password)
                c++
            } catch (e: PublisherApi.PortalApiError) {
                throw GradleException("Failed to drop failed deployment ${dep.id}", e)
            }
        }
        logger.lifecycle("Dropped $c failed deployment(s) out of total ${dd.current.size}")
    }
}

abstract class PublishValidatedDeployments : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Fetches and updates current deployments and then publishes any validated current deployments"
    }

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        val dd = updateGetDeployments(project, logger)
        val username = extension.username.get()
        val password = extension.password.get()

        logger.lifecycle("Publishing validated deployments..")
        var c = 0
        for (dep in dd.current.values) {
            val status = dep.deployment ?: continue
            if (!status.isValidated) continue
            try {
                PublisherApi.publishDeployment(dep.id, username, password)
            } catch (e: PublisherApi.PortalApiError) {
                throw GradleException("Failed to publish validated deployment ${dep.id}", e)
            }
            c++
            // change state to publishing
            dep.update(status.copy(deploymentState = PublisherApi.DeploymentState.PUBLISHING))
        }
        logger.lifecycle("Published $c validated deployment(s) out of total ${dd.current.size}. " +
                "See dashboard for status or run checkDeployments task")
        if (c > 0) {
            logger.debug("Saving deployment data after publishes")
            StoredDeploymentsManager.save(project, dd)
        }
    }
}
