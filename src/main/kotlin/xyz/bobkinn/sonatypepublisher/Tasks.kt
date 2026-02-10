package xyz.bobkinn.sonatypepublisher

import com.google.gson.GsonBuilder
import xyz.bobkinn.sonatypepublisher.utils.Endpoints
import xyz.bobkinn.sonatypepublisher.utils.HashComputation
import xyz.bobkinn.sonatypepublisher.utils.ZipUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.publish.internal.PublicationInternal
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.*
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
        val requiredAlgorithms = listOf("MD5", "SHA-1")
    }

        @TaskAction
        fun run() {
            HashComputation.computeAndSaveDirectoryHashes(directory.asFile,
                requiredAlgorithms + additionalAlgorithms)
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

    @Throws(IOException::class, URISyntaxException::class)
    @TaskAction
    fun uploadZip() {
        val pub = extension.publication.get()
        println("Uploading publication ${pub.name} to Sonatype..")
        val username = extension.username.get()
        val password = extension.password.get()
        require(username.isNotBlank()) { "Sonatype username must not be empty" }
        require(password.isNotBlank()) { "Sonatype password must not be empty" }

        val groupId = pub.groupId
        val artifactId = pub.artifactId
        val version = pub.version
        val publishingType = extension.publishingType.get().name
        val name = URLEncoder.encode("$groupId:$artifactId:$version", UTF_8)

        val credentials = "$username:$password"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())
        val url = "${Endpoints.UPLOAD}?publishingType=$publishingType&name=$name"

        val body =
            MultipartBody.Builder()
                .addFormDataPart(
                    "bundle",
                    "upload.zip",
                    zipFile.get().asFile.asRequestBody("application/zip".toMediaType()),
                )
                .build()

        val request =
            Request.Builder()
                .post(body)
                .addHeader("Authorization", "UserToken $encodedCredentials")
                .url(url)
                .build()

        val client = createHttpClient()
        val response = client.newCall(request).execute()
        handleResponse(
            response,
            successMessage = "Published to Maven central. Deployment ID:",
            failureMessage = "Cannot publish to Maven Central.",
        )
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
        println("Executing 'getDeploymentStatus' task... With parameter deploymentId=$deploymentId")
        val username = extension.username.get()
        val password = extension.password.get()
        require(username.isNotBlank()) { "Sonatype username must not be empty" }
        require(password.isNotBlank()) { "Sonatype password must not be empty" }
        val credentials = "$username:$password"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())

        val requestBody = "".toRequestBody("application/json".toMediaType())

        val request =
            Request.Builder()
                .post(requestBody)
                .addHeader("Authorization", "UserToken $encodedCredentials")
                .url("${Endpoints.STATUS}?id=$deploymentId")
                .build()

        val client = createHttpClient()
        val response = client.newCall(request).execute()
        handleResponse(
            response,
            successMessage = "Deployment Status:",
            failureMessage = "Failed to get deployment status.",
        )
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
        println("Executing 'dropDeployment' task... Dropping deployment for deploymentId=$deploymentId")
        val username = extension.username.get()
        val password = extension.password.get()
        require(username.isNotBlank()) { "Sonatype username must not be empty" }
        require(password.isNotBlank()) { "Sonatype password must not be empty" }
        val credentials = "$username:$password"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())

        val request =
            Request.Builder()
                .delete()
                .addHeader("Authorization", "UserToken $encodedCredentials")
                .url("${Endpoints.DEPLOYMENT}/$deploymentId")
                .build()

        val client = createHttpClient()
        val response = client.newCall(request).execute()
        handleResponse(response, "Deployment Dropped Successfully for Deployment ID: $deploymentId", "Failed to drop the deployment.")
    }
}

private fun createHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            },
        )
        .build()
}

private fun handleResponse(
    response: Response,
    successMessage: String,
    failureMessage: String,
) {
    val responseBody = response.body?.string()
    val gson = GsonBuilder().setPrettyPrinting().create()
    if (!response.isSuccessful) {
        val statusCode = response.code
        val errorMessage = gson.fromJson(responseBody, ErrorMessage::class.java) ?: ErrorMessage(Error("Unknown Error: $responseBody"))
        println("$failureMessage\nHTTP Status Code: $statusCode\nError Message: ${errorMessage.error.message}")
    } else {
        val jsonObject = gson.fromJson(responseBody, Any::class.java)
        val prettyJsonString = gson.toJson(jsonObject)
        println("$successMessage\n$prettyJsonString")
    }
}

data class ErrorMessage(val error: Error)

data class Error(val message: String)
