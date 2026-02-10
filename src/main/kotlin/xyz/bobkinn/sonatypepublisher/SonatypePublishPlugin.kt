package xyz.bobkinn.sonatypepublisher

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.configurationcache.extensions.capitalized
import xyz.bobkinn.sonatypepublisher.SonatypePublishExtension.Companion.toSonatypeExtension
import java.io.File

const val CUSTOM_TASK_GROUP = "sonatypePublish"

@Suppress("unused")
class SonatypePublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Configure Custom Extension
        val customExtension = project.toSonatypeExtension()

        // MAIN EXECUTION
        execution(project, customExtension)
    }
}

private fun execution(
    project: Project,
    extension: SonatypePublishExtension,
) {

    project.afterEvaluate {
        // Retrieve properties from custom extension
        val additionalTasks = extension.additionalTasks.get()
        val additionalAlgorithms = extension.additionalAlgorithms.get()
        val mavenPublication = extension.publication.get()
//        println("Configuring details - Additional tasks: $additionalTasks, Publication Name - ${mavenPublication.name}")

        registerTasks(
            project = project,
            mavenPublication = mavenPublication,
            additionalTasks = additionalTasks,
            additionalAlgorithms = additionalAlgorithms,
        )
    }
}

fun MavenPublication.sonatypePublishFolder(project: Project): Provider<Directory> = project.layout.buildDirectory.dir("sonatypePublish")
    .map { it.dir(name) }

fun publicationVersionDir(pubFolder: Directory, pub: MavenPublication): Directory {
    val groupId = pub.groupId
    val artifactId = pub.artifactId
    val version = pub.version
    val namespacePath = groupId.replace('.', File.separatorChar)
    return pubFolder.dir("aggregate").dir(namespacePath).dir(artifactId).dir(version)
}

fun getArchiveFile(pubFolder: Directory): RegularFile = pubFolder.file("upload.zip")

fun DirectoryProperty.with(dir: Directory) = apply { set(dir) }

// Register tasks for the plugin
fun registerTasks(
    project: Project,
    mavenPublication: MavenPublication,
    additionalTasks: List<String>,
    additionalAlgorithms: List<String>,
) {
    val pubName = mavenPublication.name.capitalized()
    // Generate Maven Artifact task
    val t1 = project.tasks.register("collect${pubName}Artifacts",
        GenerateMavenArtifacts::class.java, mavenPublication, additionalTasks)

    // Create the necessary directory structure to aggregate publications at a specific location for the Zip task.

    val pubFolder = mavenPublication.sonatypePublishFolder(project).get()
    val aggregateTarget = publicationVersionDir(pubFolder, mavenPublication)
    val aggregateFiles = project.tasks.register("aggregate${pubName}Files",
        AggregateFiles::class.java, mavenPublication, aggregateTarget)
    aggregateFiles.configure {
        it.dependsOn(t1)
    }

    // Calculate md5 and sha1 hash of all files in a given directory
    val t3 = project.tasks.register("compute${pubName}FilesHash",
        ComputeHash::class.java, aggregateTarget, additionalAlgorithms)
    t3.configure { it.dependsOn(aggregateFiles) }

    // Create a zip of all files in a given directory
    val archiveFileProp = project.objects.fileProperty().apply {
        set(getArchiveFile(aggregateTarget))
    }
    val createZip = project.tasks.register("create${pubName}Zip", CreateZip::class.java,
        project.objects.directoryProperty().with(aggregateTarget), archiveFileProp)
    createZip.configure {
        it.dependsOn(t3)
    }

    // Publish to Sonatype Maven Central Repository
    project.tasks.register("publish${pubName}ToSonatype", PublishToSonatypeCentral::class.java, archiveFileProp)
        .configure {
        it.dependsOn(createZip)
    }

    // Get the deployment status of published deployment by deploymentId
    project.tasks.register("getDeploymentStatus", GetDeploymentStatus::class.java)

    // Drop a deployment by deploymentId
    project.tasks.register("dropDeployment", DropDeployment::class.java)
}
