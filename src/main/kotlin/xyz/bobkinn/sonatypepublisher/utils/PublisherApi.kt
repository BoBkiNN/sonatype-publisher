package xyz.bobkinn.sonatypepublisher.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.internal.EMPTY_REQUEST
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import org.gradle.api.file.RegularFile
import org.gradle.api.publish.maven.MavenPublication
import xyz.bobkinn.sonatypepublisher.PublishingType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.*

object PublisherApi {
    private const val BASE_URL = "https://central.sonatype.com/api/v1/publisher"

    private fun createHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                },
            )
            .build()
    }

    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun ResponseBody.isJson(): Boolean {
        val type = contentType() ?: return false
        return type.type == "application" && type.subtype == "json"
    }

    data class ErrorResponse(
        val httpStatus: Int,
        val errorCode: Int,
        val message: String
    ) {
        override fun toString(): String {
            return "[HTTP $httpStatus]($errorCode) - $message"
        }
    }

    class PortalApiError : RuntimeException {
        constructor(msg: String, error: ErrorResponse) : super("$msg: $error")
        constructor(msg: String) : super(msg)
        constructor(msg: String, cause: Throwable) : super(msg, cause)
    }

    fun readError(body: ResponseBody): ErrorResponse {
        return try {
            gson.fromJson(body.charStream(), ErrorResponse::class.java)
        } catch (e: JsonParseException) {
            throw IOException("Failed to read json", e)
        } ?: throw IOException("No json can be read")
    }

    fun Response.toPortalApiError(): PortalApiError {
        if (code == 413) return PortalApiError("Payload too large")

        val err = body?.takeIf { it.isJson() }?.let { body ->
            try {
                readError(body)
            } catch (e: IOException) {
                throw PortalApiError("Failed to read error json", e)
            }
        }
        if (err != null) return PortalApiError("API responded with error", err)
        return PortalApiError("API responded with HTTP code $code")
    }

    fun Request.Builder.addAuth(username: String, password: String): Request.Builder {
        require(username.isNotBlank()) { "Sonatype username must not be empty" }
        require(password.isNotBlank()) { "Sonatype password must not be empty" }
        val credentials = "$username:$password"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())
        addHeader("Authorization", "Bearer $encodedCredentials")
        return this
    }

    val MavenPublication.coordinates: String
        get() = listOf(groupId, artifactId, version).joinToString(":")

    fun uploadBundle(file: RegularFile, publishingType: PublishingType,
                     publication: MavenPublication, username: String, password: String): String {
        val name = URLEncoder.encode(publication.coordinates, UTF_8)

        val url = "${BASE_URL}/upload?publishingType=$publishingType&name=$name"

        val body =
            MultipartBody.Builder()
                .addFormDataPart(
                    "bundle",
                    file.asFile.name,
                    file.asFile.asRequestBody("application/zip".toMediaType()),
                )
                .build()

        val request = Request.Builder().post(body)
            .addAuth(username, password)
            .url(url)
            .build()

        val client = createHttpClient()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) {
                throw PortalApiError("Failed to upload bundle", r.toPortalApiError())
            }
            return r.body?.string() ?: throw IOException("Empty body received")
        }
    }

    fun dropDeployment(id: String, username: String, password: String) {
        var url = "$BASE_URL/deployment/$id"
        val request = Request.Builder()
            .delete()
            .addAuth(username, password)
            .url(url)
            .build()
        val client = createHttpClient()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) {
                throw PortalApiError("Failed to drop deployment", r.toPortalApiError())
            }
            return
        }
    }

    @Suppress("unused")
    enum class DeploymentState {
        PENDING,
        VALIDATING,
        PUBLISHING,
        PUBLISHED,
        FAILED
    }

    data class DeploymentStatus(
        val deploymentId: String,
        val deploymentName: String,
        val deploymentState: DeploymentState,
        val errors: Any
    ) {
        val isPublished = deploymentState == DeploymentState.PUBLISHED
    }

    fun getDeploymentStatus(id: String, username: String, password: String): DeploymentStatus? {
        var url = "$BASE_URL/status?id=$id"

        val request = Request.Builder()
            .post(EMPTY_REQUEST)
            .addAuth(username, password)
            .url(url)
            .build()
        val client = createHttpClient()
        client.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) {
                throw PortalApiError("Failed to get deployment status", r.toPortalApiError())
            }
            r.body?.let {b ->
                try {
                    return gson.fromJson(b.charStream(), DeploymentStatus::class.java)
                } catch (e: JsonParseException) {
                    throw PortalApiError("Failed to read returned status", e)
                }
            } ?: throw PortalApiError("No body in response")
        }
    }
}
