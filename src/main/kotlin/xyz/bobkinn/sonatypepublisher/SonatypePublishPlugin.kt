package xyz.bobkinn.sonatypepublisher

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
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

        ext.configureEach {

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

fun publicationVersionDir(pubFolder: Directory, pub: MavenPublication): Directory {
    val groupId = pub.groupId
    val artifactId = pub.artifactId
    val version = pub.version
    val namespacePath = groupId.replace('.', File.separatorChar)
    return pubFolder.dir(AGGREGATE_FOLDER_NAME).dir(namespacePath).dir(artifactId).dir(version)
}

fun getArchiveFile(pubFolder: Directory): RegularFile = pubFolder.file("upload.zip")

fun DirectoryProperty.with(dir: Directory) = apply { set(dir) }

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
    val mavenPublication = config.publication.get()

    val name = config.name.capitalized()
    // Generate Maven Artifact task
    val t1 = project.tasks.register("collect${name}Artifacts",
        GenerateMavenArtifacts::class.java, mavenPublication, additionalTasks)

    // Create the necessary directory structure to aggregate publications at a specific location for the Zip task.

    val pubFolder = mavenPublication.sonatypePublishFolder(project).get()
    val aggregateTarget = publicationVersionDir(pubFolder, mavenPublication)
    val aggregateFiles = project.tasks.register("aggregate${name}Files",
        AggregateFiles::class.java, mavenPublication, aggregateTarget)
    aggregateFiles.configure {
        it.dependsOn(t1)
    }

    // Calculate md5 and sha1 hash of all files in a given directory
    val t3 = project.tasks.register("compute${name}FilesHash",
        ComputeHash::class.java, aggregateTarget, additionalAlgorithms)
    t3.configure { it.dependsOn(aggregateFiles) }

    // Create a zip of all files in a given directory
    val archiveFileProp = project.objects.fileProperty().apply {
        set(getArchiveFile(pubFolder))
    }
    val createZip = project.tasks.register("create${name}Zip", CreateZip::class.java,
        project.objects.directoryProperty().with(pubFolder.dir(AGGREGATE_FOLDER_NAME)), archiveFileProp)
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
