package io.ht.build.plugin

import org.gradle.api.provider.Property
import javax.inject.Inject
import kotlin.jvm.java

/**
 * Auxiliary services bundled with the plugin and exposed under
 * `sut { auxServices { ... } }`.
 *
 * Each aux service can be toggled with `enabled = true/false`, optionally
 * overriding its default deployment `order`. Enabled aux services are
 * auto-registered into the [io.ht.build.plugin.ServiceRegistry] using manifests shipped as
 * plugin resources.
 *
 * Example:
 * ```
 * sut {
 *     auxServices {
 *         postgres { enabled = true }                       // order 1 (default)
 *         minio    { enabled = true; bucket = "my-bucket" } // order 2 (default)
 *         observability { enabled = true }                  // order 3 (default)
 *     }
 * }
 * ```
 */
abstract class AuxServicesExtension
    @javax.inject.Inject
    constructor(
        objects: org.gradle.api.model.ObjectFactory,
    ) {
        val postgres: PostgresAux = objects.newInstance(PostgresAux::class.java)
        val minio: MinioAux = objects.newInstance(MinioAux::class.java)
        val observability: ObservabilityAux = objects.newInstance(ObservabilityAux::class.java)

        fun postgres(configure: PostgresAux.() -> Unit) {
            postgres.configure()
        }

        fun minio(configure: MinioAux.() -> Unit) {
            minio.configure()
        }

        fun observability(configure: ObservabilityAux.() -> Unit) {
            observability.configure()
        }
    }

/** Configuration for the bundled Postgres aux service (single-replica dev deployment). */
abstract class PostgresAux {
    /** Toggle deployment. Defaults to `false`. */
    abstract val enabled: org.gradle.api.provider.Property<Boolean>

    /** Deployment order in the registry. Defaults to 1. */
    abstract val order: org.gradle.api.provider.Property<Int>

    companion object {
        const val NAME: String = "postgres"
        const val DEFAULT_ORDER: Int = 1
        const val RESOURCE_PATH: String = "/io/ht/build/plugin/aux/postgres.yaml"
    }
}

/** Configuration for the bundled MinIO aux service (single-replica dev deployment). */
abstract class MinioAux {
    /** Toggle deployment. Defaults to `false`. */
    abstract val enabled: org.gradle.api.provider.Property<Boolean>

    /** Deployment order in the registry. Defaults to 2. */
    abstract val order: org.gradle.api.provider.Property<Int>

    /** Bucket created on startup. REQUIRED — has no default; must be set when enabled. */
    abstract val bucket: org.gradle.api.provider.Property<String>

    companion object {
        const val NAME: String = "minio"
        const val DEFAULT_ORDER: Int = 2
        const val RESOURCE_PATH: String = "/io/ht/build/plugin/aux/minio.yaml"
        const val BUCKET_PLACEHOLDER: String = "__BUCKET__"
    }
}

/** Configuration for the bundled Grafana OTel-LGTM aux service (single-replica dev deployment). */
abstract class ObservabilityAux {
    /** Toggle deployment. Defaults to `false`. */
    abstract val enabled: org.gradle.api.provider.Property<Boolean>

    /** Deployment order in the registry. Defaults to 3. */
    abstract val order: org.gradle.api.provider.Property<Int>

    companion object {
        const val NAME: String = "observability"
        const val DEFAULT_ORDER: Int = 3
        const val RESOURCE_PATH: String = "/io/ht/build/plugin/aux/observability.yaml"
    }
}
