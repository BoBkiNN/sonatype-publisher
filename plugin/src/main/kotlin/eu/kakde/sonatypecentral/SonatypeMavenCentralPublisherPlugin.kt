package eu.kakde.sonatypecentral

import eu.kakde.sonatypecentral.SonatypeCentralPublishExtension.Companion.toSonatypeExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication
import java.io.File

const val CUSTOM_TASK_GROUP = "Publish to Sonatype Central"

@Suppress("unused")
class SonatypeMavenCentralPublisherPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Configure Custom Extension
        println("Configuring SonatypeCentralPublishExtension...")
        val customExtension = project.toSonatypeExtension()

        // MAIN EXECUTION
        execution(project, customExtension)
    }
}

private fun execution(
    project: Project,
    extension: SonatypeCentralPublishExtension,
) {

    project.afterEvaluate {
        // Retrieve properties from custom extension
        val additionalTasks = extension.additionalTasks.get()
        val shaAlgorithms = extension.shaAlgorithms.get()
        val mavenPublication = extension.publication.get()
        println("Configuring details - Additional tasks: $additionalTasks, Publication Name - ${mavenPublication.name}")

        registerTasks(
            project = project,
            mavenPublication = mavenPublication,
            additionalTasks = additionalTasks,
            shaAlgorithms = shaAlgorithms,
        )
    }
}

// Register tasks for the plugin
fun registerTasks(
    project: Project,
    mavenPublication: MavenPublication,
    additionalTasks: List<String>,
    shaAlgorithms: List<String>,
) {
    // Generate Maven Artifact task
    project.tasks.register("generateMavenArtifacts", GenerateMavenArtifacts::class.java, mavenPublication, additionalTasks)

    val groupId = mavenPublication.groupId
    val artifactId = mavenPublication.artifactId
    val version = mavenPublication.version

    // Create the necessary directory structure to aggregate publications at a specific location for the Zip task.
    val buildDir = project.layout.buildDirectory.get().asFile.resolve("upload")
    val namespacePath = groupId.replace('.', File.separatorChar)
    val directoryPath = "${buildDir.path}/$namespacePath/$artifactId/$version"
    val aggregateFiles = project.tasks.register("aggregateFiles",
        AggregateFiles::class.java, mavenPublication)
    aggregateFiles.configure {
        it.directoryPath = directoryPath
    }

    // Calculate md5 and sha1 hash of all files in a given directory
    project.tasks.register("computeHash", ComputeHash::class.java, File(directoryPath), shaAlgorithms)

    // Create a zip of all files in a given directory
    val createZip = project.tasks.register("createZip", CreateZip::class.java)
    createZip.configure { it.folderPath = project.layout.buildDirectory.get().asFile.resolve("upload").path }

    // Publish to Sonatype Maven Central Repository
    project.tasks.register("publishToSonatype", PublishToSonatypeCentral::class.java)

    // Get the deployment status of published deployment by deploymentId
    project.tasks.register("getDeploymentStatus", GetDeploymentStatus::class.java)

    // Drop a deployment by deploymentId
    project.tasks.register("dropDeployment", DropDeployment::class.java)
}
