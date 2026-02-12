package xyz.bobkinn.sonatypepublisher

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
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
    ) : DefaultTask() {

        @get:Internal
        abstract val additionalTasks: ListProperty<String>

        init {
            val publication = publication.get()
            if (publication is PublicationInternal<*>) {
                // using PublicationInternal#publishableArtifacts to obtain real artifacts list
                val tasks = publication.publishableArtifacts.map {
                    it.buildDependencies.getDependencies(null)
                }.flatten()
                dependsOn(tasks)
                logger.debug("Publication depends on tasks: {}", tasks.map { it.path })
            }
            dependsOn(*additionalTasks.get().toTypedArray())

            group = TASKS_GROUP
            description = "Aggregator task to build publication artifacts"
        }

    }

abstract class AggregateFiles : DefaultTask() {

    @get:OutputDirectory
    abstract val targetDirectory: DirectoryProperty

    @get:Internal
    abstract val publication: Property<MavenPublication>

    private val rootDir = project.rootDir

    init {
        group = TASKS_GROUP
        description = "Aggregate all publishable artifacts into a temporary directory with proper names."
    }

    @TaskAction
    fun action() {
        val folder = targetDirectory.get().asFile
        if (folder.exists() && !folder.deleteRecursively()) {
            throw GradleException("Failed to clean directory $folder")
        }
        folder.mkdirs()

        val publication = publication.get()

        val artifactId = publication.artifactId
        val version = publication.version

        // Copy and rename all publishable artifacts directly into temp dir
        val pub = publication as PublicationInternal<*>
        logger.lifecycle("Aggregating ${pub.publishableArtifacts.size} artifacts" +
                " into ${folder.relativeTo(rootDir)}")
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
        pub.publishableArtifacts.filterIsInstance<MavenArtifact>().forEach { it ->
//            val producerTasks = it.buildDependencies.getDependencies(null)
//            println("Artifact ${it.file}, prod: $producerTasks")
            processArtifact(it.file, it.classifier, it.extension)
        }
    }
}

@CacheableTask
abstract class ComputeHashes : DefaultTask() {

    init {
        group = TASKS_GROUP
        description = "Compute Hash of all files in a temporary directory."
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directory: DirectoryProperty

    @get:Input
    abstract val additionalAlgorithms: ListProperty<String>

    private val rootDir = project.rootDir

    companion object {
        val REQUIRED_ALGORITHMS = listOf("MD5", "SHA-1")
    }

        @TaskAction
        fun run() {
            logger.debug("Writing file hashes at {}",
                directory.get().asFile.relativeTo(rootDir))
            HashUtils.writesFilesHashes(directory.get().asFile,
                REQUIRED_ALGORITHMS + additionalAlgorithms.get())
        }
    }

@CacheableTask
abstract class CreateZip : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fromDirectory: DirectoryProperty

    @get:OutputFile
    abstract val zipFile: RegularFileProperty

    private val rootDir = project.rootDir

    init {
        group = TASKS_GROUP
        description = "Creates a zip from aggregated and processed files"
    }

    @TaskAction
    fun createArchive() {
        val source = fromDirectory.get().asFile
        val target = zipFile.get().asFile

        logger.lifecycle("Creating zip file from: ${source.relativeTo(rootDir)}")
        target.parentFile.mkdirs()
        ZipUtils.prepareZipFile(source, target)
        logger.lifecycle("Zip created at ${target.relativeTo(rootDir)}")
    }
}

abstract class PublishToSonatypeCentral : DefaultTask() {

    @get:InputFile
    abstract val zipFile: RegularFileProperty

    @get:Nested
    abstract val config: Property<SonatypePublishConfig>

    init {
        group = TASKS_GROUP
        description = "Publish to New Sonatype Maven Central Repository."
    }

    private val thisProject = project

    @TaskAction
    fun uploadZip() {
        val config = config.get()
        logger.lifecycle("Uploading ${config.name} to Sonatype..")
        val id = try {
            PublisherApi.uploadBundle(zipFile.get(), config.publishingType.get(),
                config.publication.get(), config.username.get(), config.password.get())
        } catch (e: PublisherApi.PortalApiError) {
            throw GradleException("Failed to perform upload", e)
        }
        logger.lifecycle("Publication uploaded with deployment id $id")

        logger.debug("Writing deployment status..")
        StoredDeploymentsManager.putCurrent(thisProject, Deployment(id))
        logger.debug("Deployment data updated")
    }
}

// common tasks

abstract class DropDeployment : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Drop deployment using deploymentId."
    }

    @Input
    val deploymentId: String = project.findProperty("deploymentId")?.toString() ?: ""

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        if (deploymentId.isBlank()) throw IllegalArgumentException("Blank or no deploymentId is passed")
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

abstract class PublishDeployment : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Publish deployment using deploymentId."
    }

    @Input
    val deploymentId: String = project.findProperty("deploymentId")?.toString() ?: ""

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        if (deploymentId.isBlank()) throw IllegalArgumentException("Blank or no deploymentId is passed")
        logger.lifecycle("Publishing deployment with deploymentId=$deploymentId ...")
        try {
            PublisherApi.publishDeployment(deploymentId, extension.username.get(), extension.password.get())
        } catch (e: PublisherApi.PortalApiError) {
            throw GradleException("Failed to perform publish", e)
        }
        logger.lifecycle("Deployment $deploymentId is now publishing")

        logger.debug("Switching deployment state to publishing")
        StoredDeploymentsManager.update(project, deploymentId) {
            if (it == null) return@update null
            val st = it.deployment ?: return@update null
            it.update(st.copy(deploymentState = PublisherApi.DeploymentState.PUBLISHING))
            it
        }
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

private fun fetchAndUpdateDeployments(dd: DeploymentsData, extension: SonatypePublishExtension,
                                      onlyId: String? = null) {
    val it = dd.current.values.iterator()
    while (it.hasNext()) {
        val d = it.next()
        if (onlyId != null && d.id != onlyId) continue
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

fun updateGetDeployments(project: Project, logger: Logger, onlyId: String? = null): DeploymentsData {
    val extension = project.extensions.getByType(SonatypePublishExtension::class.java)
    val dd = StoredDeploymentsManager.load(project)
    if (onlyId != null) logger.lifecycle("Fetching and updating deployment $onlyId..")
    else logger.lifecycle("Fetching and updating ${dd.current.size} deployment(s)..")
    fetchAndUpdateDeployments(dd, extension, onlyId)
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
    val deploymentId: String? = project.findProperty("deploymentId")?.toString()

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
        val dd = updateGetDeployments(project, logger, deploymentId)
        deploymentId?.let {
            if (it.isBlank()) throw GradleException("Passed deploymentId property is blank")
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

abstract class DropFailedDeployments : DefaultTask() {
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
