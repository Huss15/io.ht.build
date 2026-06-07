package io.ht.build.plugin

import io.ht.build.plugin.tasks.CheckSutDependencyTask
import io.ht.build.plugin.tasks.DeploySutTask
import io.ht.build.plugin.tasks.LoadSutImagesTask
import io.ht.build.plugin.tasks.StartSutTask
import io.ht.build.plugin.tasks.StopSutTask
import io.ht.build.plugin.tasks.WaitForSutTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Root plugin for HT Spring Boot projects.
 *
 * Registers:
 *  - `sut` DSL extension (with `cluster`, `registry`, `auxServices`, `images`)
 *  - `checkSutDependency` task — validates host/cluster preconditions
 *  - `startSut` task           — creates a local kind cluster
 *  - `deployAuxSut` task       — deploys aux services (postgres, minio, …)
 *  - `buildSutImages` task     — fans out to consumer-declared image-build task paths
 *  - `loadSutImages` task      — `kind load docker-image` for each declared image
 *  - `deploySut` task          — applies user-registered manifests, waits per-resource
 *  - `waitForSut` task         — re-verifies rollout readiness
 *  - `stopSut` task            — deletes the kind cluster
 *  - `sutServices` lifecycle task — lists all aux + user services in deployment order
 *
 * Bundled auxiliary services (e.g. `postgres`, `minio`) are materialized into
 * `<buildDir>/htbuild/aux/` and auto-registered when enabled.
 */
class BuildPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<SutExtension>(EXTENSION_NAME)

        // Defaults — `namespace` is intentionally NOT defaulted: the consumer must provide it.
        extension.cluster.kindBinary.convention("kind")
        extension.cluster.kubectlBinary.convention("kubectl")
        extension.cluster.rolloutTimeoutSeconds.convention(WaitForSutTask.DEFAULT_TIMEOUT_SECONDS)
        extension.cluster.workers.convention(StartSutTask.DEFAULT_WORKERS)

        // Aux service defaults
        extension.auxServices.postgres.enabled
            .convention(false)
        extension.auxServices.postgres.order
            .convention(PostgresAux.DEFAULT_ORDER)
        extension.auxServices.minio.enabled
            .convention(false)
        extension.auxServices.minio.order
            .convention(MinioAux.DEFAULT_ORDER)
        // NOTE: minio.bucket has NO convention — consumer must set it when enabled.
        extension.auxServices.observability.enabled
            .convention(false)
        extension.auxServices.observability.order
            .convention(ObservabilityAux.DEFAULT_ORDER)

        project.tasks.register<CheckSutDependencyTask>(CheckSutDependencyTask.NAME) {
            group = SUT_GROUP
            description = "Validates SUT preconditions (kind, kubectl, Docker, manifests, config) " +
                "before any external state is mutated. Runs before startSut."
            clusterName.set(extension.cluster.namespace)
            kindBinary.set(extension.cluster.kindBinary)
            kubectlBinary.set(extension.cluster.kubectlBinary)
            configFile.set(extension.cluster.configFile)
            manifests.set(
                project.provider {
                    extension.auxManifests.getOrElse(emptyList()) +
                        extension.userManifests.getOrElse(emptyList())
                },
            )
        }

        project.tasks.register<StartSutTask>(StartSutTask.NAME) {
            group = SUT_GROUP
            description = "Creates the local kind cluster used as System Under Test (idempotent)."
            dependsOn(CheckSutDependencyTask.NAME)
            clusterName.set(extension.cluster.namespace)
            nodeImage.set(extension.cluster.nodeImage)
            configFile.set(extension.cluster.configFile)
            workers.set(extension.cluster.workers)
            generatedConfigDir.set(project.layout.buildDirectory.dir("htbuild/kind"))
            kindBinary.set(extension.cluster.kindBinary)
        }

        project.tasks.register<DeploySutTask>(DEPLOY_AUX_SUT_TASK) {
            group = SUT_GROUP
            description = "Applies all enabled auxiliary service manifests (postgres, minio, …) to the kind " +
                "cluster, waiting for each rollout to become ready. Depends on startSut. Runs " +
                "before image build/load so the warm cluster overlaps with the slow build."
            dependsOn(StartSutTask.NAME)
            clusterName.set(extension.cluster.namespace)
            kubectlBinary.set(extension.cluster.kubectlBinary)
            timeoutSeconds.set(extension.cluster.rolloutTimeoutSeconds)
            manifests.set(extension.auxManifests)
        }

        project.tasks.register<DeploySutTask>(DeploySutTask.NAME) {
            group = SUT_GROUP
            description = "Applies all user-registered SUT service manifests (in order) to the kind cluster, " +
                "waiting for each rollout to become ready. Depends on loadSutImages so any " +
                "locally-built images referenced by the manifests are already present in the " +
                "kind nodes."
            dependsOn(LoadSutImagesTask.NAME)
            clusterName.set(extension.cluster.namespace)
            kubectlBinary.set(extension.cluster.kubectlBinary)
            timeoutSeconds.set(extension.cluster.rolloutTimeoutSeconds)
            // userManifests is populated in afterEvaluate so per-service `substitutions`
            // have been rendered into a copy under <buildDir>/htbuild/registry/.
            manifests.set(extension.userManifests)
        }

        project.tasks.register<StopSutTask>(StopSutTask.NAME) {
            group = SUT_GROUP
            description = "Deletes the local kind cluster used as System Under Test (idempotent)."
            clusterName.set(extension.cluster.namespace)
            kindBinary.set(extension.cluster.kindBinary)
        }

        project.tasks.register<WaitForSutTask>(WaitForSutTask.NAME) {
            group = SUT_GROUP
            description = "Re-verifies that all SUT manifests (aux + user) have rolled out. Depends on deploySut."
            dependsOn(DeploySutTask.NAME)
            clusterName.set(extension.cluster.namespace)
            kubectlBinary.set(extension.cluster.kubectlBinary)
            timeoutSeconds.set(extension.cluster.rolloutTimeoutSeconds)
            manifests.set(
                project.provider {
                    extension.auxManifests.getOrElse(emptyList()) +
                        extension.userManifests.getOrElse(emptyList())
                },
            )
        }

        project.tasks.register("sutServices") {
            group = SUT_GROUP
            description = "Lists all SUT services (aux + user) in deployment order."
            doLast {
                val aux = extension.auxManifests.getOrElse(emptyList())
                val user = extension.userManifests.getOrElse(emptyList())
                if (aux.isEmpty() && user.isEmpty()) {
                    logger.lifecycle("[sutServices] no services registered.")
                } else {
                    if (aux.isNotEmpty()) {
                        logger.lifecycle("[sutServices] {} aux service(s):", aux.size)
                        aux.forEach { e ->
                            logger.lifecycle("  - {} (order={}, file={})", e.name, e.order, e.path)
                        }
                    }
                    if (user.isNotEmpty()) {
                        logger.lifecycle("[sutServices] {} user service(s):", user.size)
                        user.forEach { e ->
                            logger.lifecycle("  - {} (order={}, file={})", e.name, e.order, e.path)
                        }
                    }
                }
            }
        }

        project.tasks.register(BUILD_SUT_IMAGES_TASK) {
            group = SUT_GROUP
            description = "Builds consumer container images via the task paths declared in " +
                "`sut { images { buildTasks.add(...) } }`. Depends on deployAuxSut so the " +
                "cluster and aux services come up while the (often slow) image build runs. " +
                "Followed by loadSutImages and then deploySut."
            dependsOn(DEPLOY_AUX_SUT_TASK)
        }

        project.tasks.register<LoadSutImagesTask>(LoadSutImagesTask.NAME) {
            group = SUT_GROUP
            description = "Loads images declared in `sut { images { loadImages.add(...) } }` into the kind " +
                "cluster via `kind load docker-image`. Depends on buildSutImages and precedes " +
                "deploySut so user manifests can reference locally-built images."
            dependsOn(BUILD_SUT_IMAGES_TASK)
            clusterName.set(extension.cluster.namespace)
            kindBinary.set(extension.cluster.kindBinary)
            images.set(extension.images.loadImages)
        }

        // Wire user-declared image-build tasks (e.g. ":service:bootBuildImage") in afterEvaluate
        // so the ListProperty values are visible. Use `mustRunAfter(deployAuxSut)` so each
        // individual image build also waits for the aux cluster to be up if the user invokes
        // them together.
        project.afterEvaluate {
            val paths = extension.images.buildTasks.getOrElse(emptyList())
            if (paths.isNotEmpty()) {
                project.tasks.named(BUILD_SUT_IMAGES_TASK).configure {
                    paths.forEach { path -> dependsOn(path) }
                }
                project.tasks.matching { it.path in paths }.configureEach {
                    mustRunAfter(DEPLOY_AUX_SUT_TASK)
                }
            }
        }

        // Materialise bundled aux manifests and user-registered manifests.
        // afterEvaluate is required so consumer DSL toggles (e.g. enabled=true, registry entries,
        // substitutions) are visible.
        project.afterEvaluate {
            registerEnabledAuxServices(project, extension)
            materializeUserManifests(project, extension)
        }
    }

    /**
     * Reads each user-registered service from the [SutExtension.registry], applies any per-service
     * `substitutions` to a rendered copy in `<buildDir>/htbuild/registry/<name>.yaml`, and stores
     * the resulting [DeploySutTask.ManifestEntry] list in [SutExtension.userManifests].
     */
    private fun materializeUserManifests(
        project: Project,
        extension: SutExtension,
    ) {
        val entries =
            extension.registry.ordered().map { svc ->
                val subs = svc.substitutions.getOrElse(emptyMap())
                val original =
                    svc.file.orNull
                        ?: throw GradleException(
                            "[htbuild] service '${svc.name}' has no manifest file set " +
                                "(registry { service(\"${svc.name}\") { file = ... } }).",
                        )
                val finalPath =
                    if (subs.isEmpty()) {
                        original
                    } else {
                        materializeUserManifest(project, svc.name, original, subs).absolutePath
                    }
                DeploySutTask.ManifestEntry(svc.name, svc.order.get(), finalPath)
            }
        extension.userManifests.set(entries)
    }

    /**
     * Reads a user manifest from disk, applies literal-string substitutions, and writes the
     * rendered copy to `<buildDir>/htbuild/registry/<name>.yaml`. Returns the rendered file.
     */
    private fun materializeUserManifest(
        project: Project,
        name: String,
        originalPath: String,
        substitutions: Map<String, String>,
    ): File {
        val source =
            File(originalPath).let { f ->
                if (f.isAbsolute) {
                    f
                } else {
                    project.layout.projectDirectory
                        .file(originalPath)
                        .asFile
                }
            }
        if (!source.exists()) {
            throw GradleException("[htbuild] manifest file for service '$name' does not exist: ${source.absolutePath}")
        }
        val target =
            project.layout.buildDirectory
                .dir("htbuild/registry")
                .get()
                .file("$name.yaml")
                .asFile
        target.parentFile.mkdirs()
        val raw = source.readText(Charsets.UTF_8)
        val rendered =
            substitutions.entries.fold(raw) { acc, (placeholder, value) ->
                acc.replace(placeholder, value)
            }
        target.writeText(rendered, Charsets.UTF_8)
        return target
    }

    private fun registerEnabledAuxServices(
        project: Project,
        extension: SutExtension,
    ) {
        val collected = mutableListOf<DeploySutTask.ManifestEntry>()
        if (extension.auxServices.postgres.enabled
                .getOrElse(false)
        ) {
            val manifest = extractAuxManifest(project, PostgresAux.NAME, PostgresAux.RESOURCE_PATH)
            val order =
                extension.auxServices.postgres.order
                    .getOrElse(PostgresAux.DEFAULT_ORDER)
            collected += DeploySutTask.ManifestEntry(PostgresAux.NAME, order, manifest.absolutePath)
        }

        if (extension.auxServices.minio.enabled
                .getOrElse(false)
        ) {
            val bucket =
                extension.auxServices.minio.bucket.orNull
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException(
                        "[htbuild] sut.auxServices.minio.bucket must be set when minio is enabled " +
                            "(e.g. minio { enabled = true; bucket = \"my-bucket\" }).",
                    )
            val manifest =
                extractAuxManifest(
                    project,
                    MinioAux.NAME,
                    MinioAux.RESOURCE_PATH,
                    substitutions = mapOf(MinioAux.BUCKET_PLACEHOLDER to bucket),
                )
            val order =
                extension.auxServices.minio.order
                    .getOrElse(MinioAux.DEFAULT_ORDER)
            collected += DeploySutTask.ManifestEntry(MinioAux.NAME, order, manifest.absolutePath)
        }

        if (extension.auxServices.observability.enabled
                .getOrElse(false)
        ) {
            val manifest = extractAuxManifest(project, ObservabilityAux.NAME, ObservabilityAux.RESOURCE_PATH)
            val order =
                extension.auxServices.observability.order
                    .getOrElse(ObservabilityAux.DEFAULT_ORDER)
            collected += DeploySutTask.ManifestEntry(ObservabilityAux.NAME, order, manifest.absolutePath)
        }

        extension.auxManifests.set(collected.sortedBy { it.order })
    }

    /**
     * Copies a bundled YAML resource into `<buildDir>/htbuild/aux/<name>.yaml`, optionally
     * performing simple literal-string substitutions on the content.
     */
    private fun extractAuxManifest(
        project: Project,
        name: String,
        resourcePath: String,
        substitutions: Map<String, String> = emptyMap(),
    ): File {
        val target =
            project.layout.buildDirectory
                .dir("htbuild/aux")
                .get()
                .file("$name.yaml")
                .asFile
        target.parentFile.mkdirs()
        val stream =
            BuildPlugin::class.java.getResourceAsStream(resourcePath)
                ?: throw GradleException("[htbuild] bundled aux manifest not found on classpath: $resourcePath")
        val raw = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val rendered =
            substitutions.entries.fold(raw) { acc, (placeholder, value) ->
                acc.replace(placeholder, value)
            }
        target.writeText(rendered, Charsets.UTF_8)
        return target
    }

    companion object {
        const val EXTENSION_NAME = "sut"
        const val SUT_GROUP = "sut"
        const val BUILD_SUT_IMAGES_TASK = "buildSutImages"
        const val DEPLOY_AUX_SUT_TASK = "deployAuxSut"
    }
}
