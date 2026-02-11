package xyz.bobkinn.sonatypepublisher

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication
import javax.inject.Inject

@Suppress("unused")
enum class PublishingType {
    AUTOMATIC,
    USER_MANAGED
}

abstract class SonatypePublishConfig @Inject constructor(
    objects: ObjectFactory,
    val name: String
) {

    val publishingType: Property<PublishingType> =
        objects.property(PublishingType::class.java)

    val additionalTasks: ListProperty<String> =
        objects.listProperty(String::class.java)

    val additionalAlgorithms: ListProperty<String> =
        objects.listProperty(String::class.java)

    val username: Property<String> =
        objects.property(String::class.java)

    val password: Property<String> =
        objects.property(String::class.java)

    val publication: Property<MavenPublication> =
        objects.property(MavenPublication::class.java)
}

abstract class SonatypePublishExtension @Inject constructor(
    objects: ObjectFactory
) : NamedDomainObjectContainer<SonatypePublishConfig>
by objects.domainObjectContainer(SonatypePublishConfig::class.java) {

    val publishingType: Property<PublishingType> =
        objects.property(PublishingType::class.java)

    val username: Property<String> =
        objects.property(String::class.java)

    val password: Property<String> =
        objects.property(String::class.java)

    @Suppress("unused")
    fun registerMaven(pub: MavenPublication) {
        register(pub.name) {
            it.publication.set(pub)
        }
    }
}
