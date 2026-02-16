package xyz.bobkinn.sonatypepublisher

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import xyz.bobkinn.sonatypepublisher.utils.PublisherApi

// general tasks isn't bound to configs

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
            // return updated deployment
            it.updated(st.copy(deploymentState = PublisherApi.DeploymentState.PUBLISHING))
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
    val updated = mutableListOf<Deployment>()
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
            val nd = d.updated(status)
            dd.published.put(d.id, nd)
        } else {
            // update status
            val upd = d.updated(status)
            updated.add(upd)
        }
    }
    for (d in updated) {
        dd.current[d.id] = d
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
    @get:Input
    @get:Optional
    abstract val deploymentId: Property<String>

    init {
        group = TASKS_GROUP
        description = "Update current deployments information and print its statuses." +
                "Pass deploymentId property to specify exact deployment"
        deploymentId.convention(
            project.providers.gradleProperty("deploymentId")
        )
    }

    private fun logStatus(status: PublisherApi.DeploymentStatus) {
        logger.lifecycle("Deployment ${status.deploymentId} - ${status.deploymentState}:")
        logger.lifecycle("  Name: ${status.deploymentName}")

        if (status.errors is Map<*, *> && status.errors.isEmpty()) return
        val errorsJson = PublisherApi.GSON.toJson(status.errors)
        logger.lifecycle("  Errors: $errorsJson")
    }

    private fun logDeployment(dep: Deployment) {
        if (dep.deployment != null) logStatus(dep.deployment)
        else logger.lifecycle("Deployment ${dep.id} - UNKNOWN")
    }

    private val thisProject = project

    @TaskAction
    fun executeTask() {
        val deploymentId = deploymentId.orNull
        val dd = updateGetDeployments(thisProject, logger, deploymentId)
        deploymentId?.let {
            if (it.isBlank()) throw GradleException("Passed deploymentId property is blank")
            logger.lifecycle("--- Deployment $it status:")
            val dep = dd[it]
            if (dep == null) throw GradleException("No deployment with id $it stored")
            logDeployment(dep)
            return
        }
        if (dd.current.isEmpty()) {
            logger.lifecycle("No unreleased deployment IDs stored")
            return
        }
        logger.lifecycle("Status of ${dd.current.size} unreleased deployment(s):")
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
    private val thisProject = project

    @TaskAction
    fun executeTask() {
        val dd = updateGetDeployments(thisProject, logger)
        val username = extension.username.get()
        val password = extension.password.get()

        logger.lifecycle("Dropping failed deployments..")
        var c = 0
        val it = dd.current.values.iterator()
        while (it.hasNext()) {
            val dep = it.next()
            val status = dep.deployment ?: continue
            if (!status.isFailed) continue
            try {
                PublisherApi.dropDeployment(dep.id, username, password)
            } catch (e: PublisherApi.PortalApiError) {
                throw GradleException("Failed to drop failed deployment ${dep.id}", e)
            }
            c++
            it.remove()
        }
        logger.lifecycle("Dropped $c failed deployment(s) out of total ${dd.current.size}")
        if (c > 0) {
            logger.debug("Saving deployment data after dropping failed")
            StoredDeploymentsManager.save(thisProject, dd)
        }
    }
}

abstract class PublishValidatedDeployments : DefaultTask() {
    init {
        group = TASKS_GROUP
        description = "Fetches and updates current deployments and then publishes any validated current deployments"
    }

    private val extension = project.extensions.getByType(SonatypePublishExtension::class.java)
    private val thisProject = project

    @TaskAction
    fun executeTask() {
        val dd = updateGetDeployments(thisProject, logger)
        val username = extension.username.get()
        val password = extension.password.get()

        logger.lifecycle("Publishing validated deployments..")
        var c = 0
        val updated = mutableListOf<Deployment>()
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
            val u = dep.updated(status.copy(deploymentState = PublisherApi.DeploymentState.PUBLISHING))
            updated.add(u)
        }
        // replace updated
        for (d in updated) {
            dd.current[d.id] = d
        }
        logger.lifecycle("Published $c validated deployment(s) out of total ${dd.current.size}. " +
                "See dashboard for status or run checkDeployments task")
        if (c > 0) {
            logger.debug("Saving deployment data after publishes")
            StoredDeploymentsManager.save(thisProject, dd)
        }
    }
}