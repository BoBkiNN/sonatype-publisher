package xyz.bobkinn.sonatypepublisher

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.internal.provider.Providers
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import javax.inject.Inject

@Suppress("unused")
enum class PublishingType {
    AUTOMATIC,
    USER_MANAGED
}

abstract class SonatypePublishConfig : Named {

    @get:Input
    abstract val publishingType: Property<PublishingType>

    /**
     * Additional tasks used to build artifacts
     */
    @get:Input
    abstract val additionalTasks: ListProperty<String>

    /**
     * List of hashing algorithm names to include into publication bundle.
     *
     * SHA-1 and MD5 are already included because they are required for central.
     * See [MessageDigest Algorithms section](https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#messagedigest-algorithms)
     * to find out what other algorithms can be used.
     */
    @get:Input
    abstract val additionalAlgorithms: ListProperty<String>

    @get:Internal
    abstract val username: Property<String>

    @get:Internal
    abstract val password: Property<String>

    /**
     * Publication which artifacts will be built and included into publication.<br>
     */
    @get:Internal
    abstract val publication: Property<MavenPublication>
}

@Suppress("unused")
abstract class SonatypePublishExtension @Inject constructor(
    objects: ObjectFactory
) : NamedDomainObjectContainer<SonatypePublishConfig>
by objects.domainObjectContainer(SonatypePublishConfig::class.java) {

    abstract val publishingType: Property<PublishingType>

    abstract val username: Property<String>

    abstract val password: Property<String>

    fun registerMaven(name: String, pub: Provider<MavenPublication>,
                      configuration: (SonatypePublishConfig) -> Unit = {}) {
        register(name) {
            it.publication.set(pub)
            configuration(it)
        }
    }

    fun registerMaven(name: String, pub: MavenPublication,
                      configuration: (SonatypePublishConfig) -> Unit = {}) {
        registerMaven(name, Providers.of(pub), configuration)
    }

    fun registerMaven(pub: NamedDomainObjectProvider<MavenPublication>,
                      configuration: (SonatypePublishConfig) -> Unit = {}) {
        registerMaven(pub.name, pub, configuration)
    }
}
