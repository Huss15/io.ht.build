package io.ht.build.plugin

import io.ht.build.plugin.tasks.DeploySutTask
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import javax.inject.Inject
import kotlin.jvm.java

/**
 * DSL entry point for the HT build plugin, exposed as `sut { ... }`.
 *
 * Example:
 * ```
 * sut {
 *     cluster {
 *         namespace = "htoffice"
 *     }
 *     auxServices {
 *         postgres { enabled = true }
 *     }
 *     registry {
 *         service("backend") { order = 20; file = "k8s/backend.yaml" }
 *     }
 * }
 * ```
 */
abstract class SutExtension
    @javax.inject.Inject
    constructor(
        objects: org.gradle.api.model.ObjectFactory,
    ) {
        @get:org.gradle.api.tasks.Nested
        val cluster: ClusterExtension = objects.newInstance(ClusterExtension::class.java)

        @get:org.gradle.api.tasks.Nested
        val registry: ServiceRegistry = objects.newInstance(ServiceRegistry::class.java)

        @get:org.gradle.api.tasks.Nested
        val auxServices: AuxServicesExtension = objects.newInstance(AuxServicesExtension::class.java)

        @get:org.gradle.api.tasks.Nested
        val images: ImagesExtension = objects.newInstance(ImagesExtension::class.java)

        /**
         * Internal list of aux service manifests populated by the plugin in `afterEvaluate`
         * when entries under [auxServices] are enabled. Consumed by `deployAuxSut`. Not part
         * of the public DSL — aux services are configured via `auxServices { ... }`.
         */
        @get:org.gradle.api.tasks.Internal
        val auxManifests: org.gradle.api.provider.ListProperty<DeploySutTask.ManifestEntry> =
            objects.listProperty(DeploySutTask.ManifestEntry::class.java)

        /**
         * Internal list of user-registered service manifests, materialised in `afterEvaluate`
         * so any per-service `substitutions` have been applied to a rendered copy under
         * `<buildDir>/htbuild/registry/<name>.yaml`. Consumed by `deploySut`, `waitForSut`,
         * `checkSutDependency`, and `sutServices`.
         */
        @get:org.gradle.api.tasks.Internal
        val userManifests: org.gradle.api.provider.ListProperty<DeploySutTask.ManifestEntry> =
            objects.listProperty(DeploySutTask.ManifestEntry::class.java)

        fun cluster(action: org.gradle.api.Action<ClusterExtension>) {
            action.execute(cluster)
        }

        fun registry(action: org.gradle.api.Action<ServiceRegistry>) {
            action.execute(registry)
        }

        fun auxServices(action: org.gradle.api.Action<AuxServicesExtension>) {
            action.execute(auxServices)
        }

        fun images(action: org.gradle.api.Action<ImagesExtension>) {
            action.execute(images)
        }
    }

/** Configuration for the local kind (Kubernetes-in-Docker) cluster used as SUT. */
abstract class ClusterExtension {
    /**
     * Logical name of the SUT cluster — used as the kind cluster name.
     * **Required**: must be set by the consumer (no default).
     */
    abstract val namespace: org.gradle.api.provider.Property<String>

    /**
     * Optional node image override (e.g. `kindest/node:v1.31.0`).
     * When unset, kind picks its bundled default.
     */
    abstract val nodeImage: org.gradle.api.provider.Property<String>

    /**
     * Optional path to a kind cluster config YAML.
     * When unset, a config is auto-generated based on [workers] (control-plane + N workers).
     * When set, this file is used as-is and [workers] is ignored.
     */
    abstract val configFile: org.gradle.api.provider.Property<String>

    /**
     * Number of worker nodes in the auto-generated kind config.
     *
     * Defaults to `1`. A worker is required on recent kind versions because the control-plane
     * node carries the `node-role.kubernetes.io/control-plane:NoSchedule` taint, which prevents
     * ordinary workloads (Postgres, MinIO, your services) from scheduling on it.
     *
     * Set to `0` to run a single-node cluster (control-plane only) — only useful if your
     * manifests carry the matching toleration, or your kind version untaints the control-plane.
     *
     * Ignored when [configFile] is set.
     */
    abstract val workers: org.gradle.api.provider.Property<Int>

    /** Path to the kind binary. Defaults to `kind` (resolved via PATH). */
    abstract val kindBinary: org.gradle.api.provider.Property<String>

    /** Path to the kubectl binary. Defaults to `kubectl` (resolved via PATH). */
    abstract val kubectlBinary: org.gradle.api.provider.Property<String>

    /** Per-manifest timeout (in seconds) for `waitForSut`. Defaults to 300. */
    abstract val rolloutTimeoutSeconds: org.gradle.api.provider.Property<Int>
}
