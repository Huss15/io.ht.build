package io.ht.build.plugin

import org.gradle.api.Named
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Definition of a single service to be deployed into the SUT cluster.
 *
 * Registered via [io.ht.build.plugin.ServiceRegistry.service]:
 * ```
 * registry {
 *     service("postgres") {
 *         order = 10
 *         file  = "k8s/postgres.yaml"
 *         substitutions.put("IMAGE_TAG", imageTag)
 *     }
 * }
 * ```
 */
abstract class ServiceDefinition
    @javax.inject.Inject
    constructor(
        private val serviceName: String,
    ) : org.gradle.api.Named {
        override fun getName(): String = serviceName

        /**
         * Deployment order. Lower values are applied first.
         * Required.
         */
        abstract val order: org.gradle.api.provider.Property<Int>

        /**
         * Path to the Kubernetes YAML manifest for this service,
         * resolved relative to the project directory if not absolute.
         * Required.
         */
        abstract val file: org.gradle.api.provider.Property<String>

        /**
         * Optional literal-string substitutions applied to the manifest content before
         * `kubectl apply`. Each entry replaces every occurrence of the key with the value.
         *
         * When non-empty, the plugin materialises a rendered copy to
         * `<buildDir>/htbuild/registry/<name>.yaml` and passes that to kubectl. When empty,
         * the original file is used unchanged.
         *
         * Convention: use sentinel placeholders such as `__IMAGE_TAG__` so the templated
         * YAML still parses cleanly as YAML and your IDE can lint it.
         */
        abstract val substitutions: org.gradle.api.provider.MapProperty<String, String>
    }
