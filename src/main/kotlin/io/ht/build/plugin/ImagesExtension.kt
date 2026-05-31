package io.ht.build.plugin

import org.gradle.api.provider.ListProperty

/**
 * DSL block for consumer-image build & load integration, exposed under `sut { images { ... } }`.
 *
 * Two concerns, two lists:
 *  - [buildTasks] — Gradle task paths to invoke for **producing** images on the host Docker
 *    daemon (typically `bootBuildImage`). Wired as dependencies of `buildSutImages`.
 *  - [loadImages] — image references to **load into the kind cluster** so its containerd can
 *    schedule pods using them without an external registry. Wired into `loadSutImages`.
 *
 * Kind nodes run their own containerd that does **not** share the host Docker image cache.
 * Without `kind load docker-image`, pods that reference a locally built image fail with
 * `ErrImagePull`. The two lists are independent so consumers can build without loading
 * (or load externally pulled images without building) if needed.
 *
 * Task chain:
 *
 *   checkSutDependency → startSut → deploySut → buildSutImages → loadSutImages
 *
 * Example:
 * ```
 * sut {
 *     images {
 *         buildTasks.add(":service:bootBuildImage")
 *         loadImages.add("com.hassuna.tech.htoffice.service:0.0.1-SNAPSHOT")
 *     }
 * }
 * ```
 */
abstract class ImagesExtension {
    /** Gradle task paths to invoke for building consumer images. Default: empty (no-op). */
    abstract val buildTasks: org.gradle.api.provider.ListProperty<String>

    /**
     * Image references to load into the kind cluster via `kind load docker-image <ref>`.
     * Format: `<name>:<tag>` (or any reference Docker accepts). Default: empty (no-op).
     */
    abstract val loadImages: org.gradle.api.provider.ListProperty<String>
}
