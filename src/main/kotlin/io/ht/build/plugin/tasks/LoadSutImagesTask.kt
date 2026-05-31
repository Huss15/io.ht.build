package io.ht.build.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Loads consumer-built Docker images into the kind cluster so pods can schedule them.
 *
 * Kind nodes run their own containerd, separate from the host Docker daemon, so locally built
 * images are invisible inside the cluster until you push them via `kind load docker-image`.
 * This task runs that command once per reference in [images], all against the same cluster
 * (`--name <clusterName>`).
 *
 * For each image:
 *   `kind load docker-image <ref> --name <clusterName>`
 *
 * Already-present images are a fast no-op on kind's side (content-addressable). Wired after
 * `buildSutImages` so freshly produced images are loaded immediately.
 */
@DisableCachingByDefault(because = "Loads images into an external cluster; idempotency is handled by kind.")
abstract class LoadSutImagesTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val clusterName: Property<String>

    @get:Input
    abstract val kindBinary: Property<String>

    /** Image references to load (e.g. `com.example/foo:0.0.1-SNAPSHOT`). */
    @get:Input
    abstract val images: ListProperty<String>

    @TaskAction
    fun load() {
        val kind = kindBinary.get()
        val name =
            clusterName.orNull?.takeIf { it.isNotBlank() }
                ?: throw GradleException(
                    "[loadSutImages] sut.cluster.namespace is not configured. " +
                        "Add `sut { cluster { namespace = \"<name>\" } }` to your build script.",
                )
        val refs =
            images
                .get()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        if (refs.isEmpty()) {
            logger.lifecycle(
                "[loadSutImages] no images declared — nothing to load. " +
                    "Add entries with `sut { images { loadImages.add(\"<ref>\") } }`.",
            )
            return
        }

        logger.lifecycle("[loadSutImages] loading {} image(s) into kind cluster '{}':", refs.size, name)
        val failures = mutableListOf<String>()
        refs.forEach { ref ->
            val cmd = listOf(kind, "load", "docker-image", ref, "--name", name)
            logger.lifecycle("  → {}", cmd.joinToString(" "))
            val result = exec(cmd)
            val output = (result.stdout + result.stderr).trim()
            if (output.isNotBlank()) logger.lifecycle(output)
            if (result.exitCode != 0) {
                failures += "  • '$ref' — exit ${result.exitCode}: " +
                    (output.lineSequence().firstOrNull { it.isNotBlank() } ?: "")
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "[loadSutImages] ${failures.size} image(s) failed to load into '$name':\n" +
                    failures.joinToString("\n") +
                    "\nCommon causes: image not present in host Docker (run buildSutImages first), " +
                    "kind cluster not running (run startSut), or a typo in the image reference.",
            )
        }
        logger.lifecycle("[loadSutImages] all {} image(s) loaded into '{}'.", refs.size, name)
    }

    private data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun exec(cmd: List<String>): ExecResult {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        return try {
            val process = pb.start()
            val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
            // kind load for a multi-GB image can take a few minutes; allow 10.
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                ExecResult(124, stdout, "command timed out after 10m: ${cmd.joinToString(" ")}")
            } else {
                ExecResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: IOException) {
            ExecResult(127, "", e.message ?: "")
        }
    }

    companion object {
        const val NAME: String = "loadSutImages"
    }
}
