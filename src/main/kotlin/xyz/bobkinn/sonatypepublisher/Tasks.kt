package xyz.bobkinn.sonatypepublisher

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.publish.internal.PublicationInternal
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.*
import xyz.bobkinn.sonatypepublisher.utils.HashUtils
import xyz.bobkinn.sonatypepublisher.utils.PublisherApi
import xyz.bobkinn.sonatypepublisher.utils.ZipUtils
import javax.inject.Inject

abstract class GenerateMavenArtifacts
    @Inject
    constructor(
        @Internal val publication: MavenPublication,
        @Internal val additionalTasks: List<String>,
    ) : DefaultTask() {
        init {
            if (publication is PublicationInternal<*>) {
                val tasks = publication.publishableArtifacts.map {
                    it.buildDependencies.getDependencies(null)
                }
                dependsOn(tasks)
//                println("Generate artifacts depends on $tasks")
            }
            dependsOn(*additionalTasks.toTypedArray())

            group = CUSTOM_TASK_GROUP
            description = "Aggregator tasks to build publication artifacts"
        }

    }

abstract class AggregateFiles
@Inject constructor(
    @Internal val publication: MavenPublication,
    val targetDirectory: Directory
) : DefaultTask() {

    init {
        group = CUSTOM_TASK_GROUP
        description = "Aggregate all publishable artifacts into a temporary directory with proper names."
    }

    @TaskAction
    fun action() {
        val tempDirFile = targetDirectory.asFile
        if (tempDirFile.exists()) tempDirFile.deleteRecursively()
        tempDirFile.mkdirs()

        val artifactId = publication.artifactId
        val version = publication.version

        // Copy and rename all publishable artifacts directly into temp dir
        val pub = publication as PublicationInternal<*>
        println("Aggregating ${pub.publishableArtifacts.size} artifacts into $targetDirectory")
        pub.publishableArtifacts.forEach { it ->
//            val producerTasks = it.buildDependencies.getDependencies(null)
//            println("Artifact ${it.file}, prod: $producerTasks")
            it as MavenArtifact
            val file = it.file
            var newName = when (file.name) {
                "module.json" -> "$artifactId-$version.module"
                "module.json.asc" -> "$artifactId-$version.module.asc"
                "pom-default.xml" -> "$artifactId-$version.pom"
                "pom-default.xml.asc" -> "$artifactId-$version.pom.asc"
                else -> file.name
            }
            if (file.name.endsWith(".jar.asc") || file.name.endsWith(".jar")) {
                val cls = it.classifier?.let { "-$it" } ?: ""
                newName = "$artifactId-$version$cls.${it.extension}"
//                println("were ${file.name}, become $newName")
            }
            val targetFile = tempDirFile.resolve(newName)
            file.copyTo(targetFile, overwrite = true)
//            println("Copied $file to $targetFile")
        }
    }
}

abstract class ComputeHash
    @Inject
    constructor(
        @Internal val directory: Directory,
        @Internal val additionalAlgorithms: List<String>,
    ) : DefaultTask() {
        init {
            group = CUSTOM_TASK_GROUP
            description = "Compute Hash of all files in a temporary directory."
        }

    companion object {
        val REQUIRED_ALGORITHMS = listOf("MD5", "SHA-1")
    }

        @TaskAction
        fun run() {
            HashUtils.writesFilesHashes(directory.asFile,
                REQUIRED_ALGORITHMS + additionalAlgorithms)
        }
    }

abstract class CreateZip @Inject constructor(
    @get:InputDirectory val fromDirectory: DirectoryProperty,
    @get:OutputFile val zipFile: RegularFileProperty
) : DefaultTask() {

    init {
        group = "Custom"
        description = "Create a zip file comprising all files located within a temporary directory."
    }

    @TaskAction
    fun createArchive() {
        val source = fromDirectory.get().asFile
        val target = zipFile.get().asFile

        println("Creating zip file from: $source")
        target.parentFile.mkdirs()

        ZipUtils.prepareZipFile(
            source.path,
            target.path
        )
    }
}

abstract class PublishToSonatypeCentral(
    @InputFile
    val zipFile: RegularFileProperty
) : DefaultTask() {

    init {
        group = CUSTOM_TASK_GROUP
        description = "Publish to New Sonatype Maven Central Repository."
    }

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun uploadZip() {
        val pub = extension.publication.get()
        logger.lifecycle("Uploading publication ${pub.name} to Sonatype..")
        val id = try {
            PublisherApi.uploadBundle(zipFile.get(), extension.publishingType.get(),
                pub, extension.username.get(), extension.password.get())
        } catch (e: PublisherApi.PortalApiError) {
            throw GradleException("Failed to perform upload", e)
        }
        logger.lifecycle("Publication uploaded with deployment id $id")
    }
}

abstract class GetDeploymentStatus : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
        description = "Get deployment status using deploymentId"
    }

    @Input
    var deploymentId: String = project.findProperty("deploymentId")?.toString() ?: ""

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        logger.lifecycle("Getting deployment status for $deploymentId")
        val status = try {
            PublisherApi.getDeploymentStatus(deploymentId, extension.username.get(), extension.password.get())
        } catch (e: PublisherApi.PortalApiError) {
            throw GradleException("Failed to get deployment status", e)
        }
        val json = PublisherApi.gson.toJson(status)
        logger.lifecycle("Got deployment status:\n$json")
    }
}

abstract class DropDeployment : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
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
    }
}
