package xyz.bobkinn.sonatypepublisher

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.configurationcache.extensions.capitalized
import java.io.File

const val TASKS_GROUP = "sonatypePublish"

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

fun MavenPublication.sonatypePublishFolder(project: Project): Provider<Directory> = project.layout.buildDirectory.dir("sonatypePublish")
    .map { it.dir(name) }

const val AGGREGATE_FOLDER_NAME = "aggregate"

fun publicationVersionDir(aggregateFolder: Directory, pub: MavenPublication): Directory {
    val groupId = pub.groupId
    val artifactId = pub.artifactId
    val version = pub.version
    val namespacePath = groupId.replace('.', File.separatorChar)
    return aggregateFolder.dir(namespacePath).dir(artifactId).dir(version)
}

fun Provider<Directory>.resolveDir(name: String): Provider<Directory> = map { it.dir(name) }

fun getArchiveFile(pubFolder: Directory): RegularFile = pubFolder.file("upload.zip")

fun registerCommonTasks(project: Project) {
    // Get the deployment status of published deployment by deploymentId
    project.tasks.register("getDeploymentStatus", GetDeploymentStatus::class.java)

    // Drop a deployment by deploymentId
    project.tasks.register("dropDeployment", DropDeployment::class.java)
}

// Register tasks for the plugin
fun registerTasksPipeline(
    project: Project,
    config: SonatypePublishConfig
) {
    val additionalTasks = config.additionalTasks.get()
    val additionalAlgorithms = config.additionalAlgorithms.get()
    val pub = config.publication

    val name = config.name.capitalized()
    // Generate Maven Artifact task
    val t1 = project.tasks.register("collect${name}Artifacts",
        GenerateMavenArtifacts::class.java, pub, additionalTasks)

    // Create the necessary directory structure to aggregate publications at a specific location for the Zip task.

    val pubFolder = pub.flatMap { it.sonatypePublishFolder(project) }
    val aggregateFolder = pubFolder.resolveDir(AGGREGATE_FOLDER_NAME)

    val filesFolder = aggregateFolder.flatMap { d ->
        pub.map {m ->
            publicationVersionDir(d, m)
        }
    }

    val aggregateFiles = project.tasks.register("aggregate${name}Files",
        AggregateFiles::class.java, pub, filesFolder)
    aggregateFiles.configure {
        it.dependsOn(t1)
    }

    // Calculate md5 and sha1 hash of all files in a given directory
    val t3 = project.tasks.register("compute${name}FilesHash",
        ComputeHash::class.java, filesFolder, additionalAlgorithms)
    t3.configure { it.dependsOn(aggregateFiles) }

    // Create a zip of all files in a given directory
    val archiveFileProp = pubFolder.map { getArchiveFile(it) }
    val createZip = project.tasks.register("create${name}Zip", CreateZip::class.java,
        aggregateFolder, archiveFileProp)
    createZip.configure {
        it.dependsOn(t3)
    }

    // Publish to Sonatype Maven Central Repository
    project.tasks.register("publish${name}ToSonatype", PublishToSonatypeCentral::class.java,
        archiveFileProp, config)
        .configure {
        it.dependsOn(createZip)
    }
}
