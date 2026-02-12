package xyz.bobkinn.sonatypepublisher

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.configurationcache.extensions.capitalized
import java.io.File

const val TASKS_GROUP = "sonatype publishing"

const val EXTENSION_NAME = "sonatypePublishing"

@Suppress("unused")
class SonatypePublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create(
            EXTENSION_NAME,
            SonatypePublishExtension::class.java
        )
        registerCommonTasks(project)

        ext.all {
            it.publishingType.convention(ext.publishingType)
            it.username.convention(ext.username)
            it.password.convention(ext.password)

            registerTasksPipeline(project, it)
        }
    }
}

fun registerCommonTasks(project: Project) {
    project.tasks.register("getDeploymentStatus", GetDeploymentStatus::class.java)
    project.tasks.register("dropDeployment", DropDeployment::class.java)
    project.tasks.register("checkDeployments", CheckDeployments::class.java)
    project.tasks.register("dropFailedDeployments", DropFailed::class.java)
    project.tasks.register("publishValidatedDeployments", PublishValidatedDeployments::class.java)
}

const val PLUGIN_FOLDER_NAME = "sonatypePublish"
const val AGGREGATE_FOLDER_NAME = "aggregate"
const val UPLOAD_ZIP_NAME = "upload.zip"

fun Provider<Directory>.resolveDir(name: String): Provider<Directory> = map { it.dir(name) }

fun publicationVersionDir(aggregateFolder: Directory, pub: MavenPublication): Directory {
    val groupId = pub.groupId
    val artifactId = pub.artifactId
    val version = pub.version
    val namespacePath = groupId.replace('.', File.separatorChar)
    return aggregateFolder.dir(namespacePath).dir(artifactId).dir(version)
}

fun registerTasksPipeline(
    project: Project,
    config: SonatypePublishConfig
) {
    val additionalTasks = config.additionalTasks.get()
    val additionalAlgorithms = config.additionalAlgorithms.get()
    val pub = config.publication
    val name = config.name.capitalized()

    val buildArtifacts = project.tasks.register("build${name}Artifacts",
        BuildPublicationArtifacts::class.java, pub, additionalTasks)

    val pubFolder = project.layout.buildDirectory
        .dir(PLUGIN_FOLDER_NAME).resolveDir(name)
    val aggregateFolder = pubFolder.resolveDir(AGGREGATE_FOLDER_NAME)

    val filesFolder = aggregateFolder.flatMap { d ->
        pub.map {p ->
            publicationVersionDir(d, p)
        }
    }

    val aggregateFiles = project.tasks.register("aggregate${name}Files",
        AggregateFiles::class.java, pub, filesFolder)
    aggregateFiles.configure { it.dependsOn(buildArtifacts) }

    // Calculate md5 and sha1 hash of all files in a given directory
    val computeHashes = project.tasks.register("compute${name}FileHashes",
        ComputeHashes::class.java, filesFolder, additionalAlgorithms)
    computeHashes.configure { it.dependsOn(aggregateFiles) }

    // Create a zip of all files in a given directory
    val archiveFile = pubFolder.map { it.file(UPLOAD_ZIP_NAME) }
    val createZip = project.tasks.register("create${name}Zip", CreateZip::class.java,
        aggregateFolder, archiveFile)
    createZip.configure { it.dependsOn(computeHashes) }

    // Publish to Sonatype Maven Central Repository
    project.tasks.register("publish${name}ToSonatype", PublishToSonatypeCentral::class.java,
        archiveFile, config)
        .configure { it.dependsOn(createZip) }
}
