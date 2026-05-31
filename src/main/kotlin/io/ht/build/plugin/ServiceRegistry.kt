package io.ht.build.plugin

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Nested
import javax.inject.Inject

/**
 * Registry of services to be deployed into the SUT cluster.
 *
 * Exposed under `sut { registry { ... } }`. Each entry is a [ServiceDefinition]
 * identified by a unique name and carrying an `order` (deployment priority)
 * and a `file` (path to the YAML manifest).
 *
 * Example:
 * ```
 * sut {
 *     registry {
 *         service("postgres") {
 *             order = 10
 *             file  = "k8s/postgres.yaml"
 *         }
 *         service("backend") {
 *             order = 20
 *             file  = "k8s/backend.yaml"
 *         }
 *     }
 * }
 * ```
 *
 * Use [ordered] at task execution time to retrieve services sorted by `order`.
 */
abstract class ServiceRegistry
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        @get:Nested
        val services: NamedDomainObjectContainer<ServiceDefinition> =
            objects.domainObjectContainer(ServiceDefinition::class.java)

        /**
         * Register (or reconfigure) a service by [name].
         * Fails fast if [name] is blank.
         */
        fun service(
            name: String,
            configure: ServiceDefinition.() -> Unit,
        ): ServiceDefinition {
            require(name.isNotBlank()) { "Service name must not be blank." }
            val definition = services.maybeCreate(name)
            definition.configure()
            return definition
        }

        /**
         * Snapshot of all registered services sorted by ascending [ServiceDefinition.order].
         * Throws if any registered service is missing `order` or `file`.
         */
        fun ordered(): List<ServiceDefinition> {
            val snapshot = services.toList()
            val missing =
                snapshot.filter {
                    !it.order.isPresent ||
                        !it.file.isPresent ||
                        it.file.get().isBlank()
                }
            require(missing.isEmpty()) {
                "Incomplete service definitions (each must declare both 'order' and 'file'): " +
                    missing.joinToString(", ") { it.name }
            }
            return snapshot.sortedBy { it.order.get() }
        }
    }
