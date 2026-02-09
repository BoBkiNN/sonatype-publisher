package eu.kakde.sonatypecentral

import org.gradle.api.Project
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

open class SonatypeCentralPublishExtension

    @Inject
    constructor(objectFactory: ObjectFactory) {
        val publishingType: Property<PublishingType> = objectFactory.property(PublishingType::class.java)
        val additionalTasks: ListProperty<String> = objectFactory.listProperty(String::class.java)
        val shaAlgorithms: ListProperty<String> = objectFactory.listProperty(String::class.java)

        val username: Property<String> = objectFactory.property(String::class.java)
        val password: Property<String> = objectFactory.property(String::class.java)

        val publication: Property<MavenPublication> = objectFactory.property(MavenPublication::class.java)

        companion object {
            internal fun Project.toSonatypeExtension(): SonatypeCentralPublishExtension =
                extensions.create("sonatypeCentralPublishExtension", SonatypeCentralPublishExtension::class.java)
        }
    }
