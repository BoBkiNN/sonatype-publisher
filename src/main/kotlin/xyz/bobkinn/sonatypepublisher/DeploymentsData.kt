package xyz.bobkinn.sonatypepublisher

import com.google.gson.Gson
import com.google.gson.JsonParseException
import org.gradle.api.Project
import xyz.bobkinn.sonatypepublisher.utils.PublisherApi
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

fun formatMillis(millis: Long): String {
    val instant = Instant.ofEpochMilli(millis)
    val formatter = DateTimeFormatter.ISO_INSTANT // ISO 8601
    return formatter.format(instant)
}

fun timestamp() = formatMillis(System.currentTimeMillis())

data class Deployment(
    val id: String,
    val deployment: PublisherApi.DeploymentStatus?,
    val timestamp: String
) {
    constructor(id: String, deployment: PublisherApi.DeploymentStatus?, timestamp: Long)
            : this(id, deployment, formatMillis(timestamp))

    constructor(id: String) : this(id, null, System.currentTimeMillis())

    fun update(data: PublisherApi.DeploymentStatus): Deployment {
        return copy(deployment = data, timestamp = timestamp())
    }
}

data class DeploymentsData(
    val published: MutableMap<String, Deployment>,
    val current: MutableMap<String, Deployment>
) {
    constructor() : this(HashMap(), HashMap())

    operator fun get(id: String) = current[id] ?: published[id]
}

object StoredDeploymentsManager {
    val GSON = Gson()

    const val FILE_NAME = "deployments.json"

    private fun getFile(project: Project): File {
        return project.rootProject.layout.buildDirectory
            .dir(PLUGIN_FOLDER_NAME).get().file(FILE_NAME).asFile
    }

    fun load(project: Project): DeploymentsData {
        val file = getFile(project)
        if (file.exists().not()) return DeploymentsData()
        return try {
            file.reader().use {
                GSON.fromJson(it, DeploymentsData::class.java)
            }
        } catch (e: JsonParseException) {
            throw IOException("Failed to read deployments data from $file", e)
        }
    }

    fun save(project: Project, data: DeploymentsData) {
        val file = getFile(project)
        return try {
            file.writer().use {
                GSON.toJson(data, it)
            }
        } catch (e: JsonParseException) {
            throw IOException("Failed to save deployments data to $file", e)
        }
    }

    fun removeCurrent(project: Project, id: String) {
        val d = load(project)
        if (d.current.remove(id) != null) save(project, d)
    }

    fun putCurrent(project: Project, deployment: Deployment) {
        val d = load(project)
        d.current.put(deployment.id, deployment)
        save(project, d)
    }

    fun update(project: Project, id: String, action: (Deployment?) -> Deployment?) {
        val d = load(project)
        val dp = d.current[id]
        val ndp = action(dp)
        if (ndp == null) d.current.remove(id)
        else d.current[id] = ndp
        save(project, d)
    }
}
