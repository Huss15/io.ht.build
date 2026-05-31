package io.ht.build.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Validates that everything required to bring up and deploy the SUT is present **before** any
 * external state is mutated. Runs as a dependency of [StartSutTask] so the cluster is never
 * created if a later step would inevitably fail.
 *
 * Checks performed (all failures are collected and reported together):
 *  - `sut.cluster.namespace` is configured.
 *  - The `kind` binary is on PATH and executable.
 *  - The container provider (Docker / Podman) is reachable — verified by `kind get clusters`,
 *    which is the same command kind uses internally.
 *  - The `kubectl` binary is on PATH and executable.
 *  - If `sut.cluster.configFile` is set, the file exists.
 *  - Every registered service has a non-blank manifest path pointing at an existing file.
 *
 * Failing fast here avoids leaving a half-created kind cluster behind when the user has e.g.
 * forgotten to start Docker Desktop.
 */
@DisableCachingByDefault(because = "Validates host/cluster preconditions with external side effects.")
abstract class CheckSutDependencyTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val clusterName: Property<String>

    @get:Input
    abstract val kindBinary: Property<String>

    @get:Input
    abstract val kubectlBinary: Property<String>

    @get:Input
    @get:Optional
    abstract val configFile: Property<String>

    /** Manifests registered in the service registry. Resolved lazily at execution time. */
    @get:Input
    abstract val manifests: ListProperty<DeploySutTask.ManifestEntry>

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()

        logger.lifecycle("[checkSutDependency] validating SUT preconditions...")

        // 1) namespace
        val ns = clusterName.orNull?.takeIf { it.isNotBlank() }
        if (ns == null) {
            problems += "sut.cluster.namespace is not configured " +
                "(add `sut { cluster { namespace = \"<name>\" } }`)."
        } else {
            logger.lifecycle("  ✓ namespace: '{}'", ns)
        }

        // 2) kind binary
        val kind = kindBinary.get()
        val kindCheck = exec(listOf(kind, "version"))
        if (kindCheck.exitCode != 0) {
            problems += "'$kind' is not available on PATH or failed to run " +
                "(install kind: https://kind.sigs.k8s.io/). " +
                (
                    kindCheck.stderr
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { "Stderr: $it" } ?: ""
                )
        } else {
            val v =
                kindCheck.stdout
                    .trim()
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
            logger.lifecycle("  ✓ kind binary: {} ({})", kind, v)
        }

        // 3) container provider (Docker / Podman) reachable
        // `kind get clusters` is cheap, deterministic, and goes through kind's own provider
        // detection. If Docker Desktop is stopped, kind reports it here.
        if (kindCheck.exitCode == 0) {
            val providerCheck = exec(listOf(kind, "get", "clusters"))
            if (providerCheck.exitCode != 0) {
                val stderr = providerCheck.stderr.trim().ifBlank { providerCheck.stdout.trim() }
                problems += "kind cannot reach its container provider (Docker / Podman). " +
                    "Is Docker Desktop running? Detail: ${stderr.ifBlank { "no output" }}"
            } else {
                logger.lifecycle("  ✓ container provider reachable via kind")
            }
        }

        // 4) kubectl binary
        val kubectl = kubectlBinary.get()
        val kubectlCheck = exec(listOf(kubectl, "version", "--client=true", "--output=json"))
        val kubectlOk =
            kubectlCheck.exitCode == 0 ||
                exec(listOf(kubectl, "version", "--client=true")).exitCode == 0
        if (!kubectlOk) {
            problems += "'$kubectl' is not available on PATH or failed to run " +
                "(install kubectl: https://kubernetes.io/docs/tasks/tools/)."
        } else {
            logger.lifecycle("  ✓ kubectl binary: {}", kubectl)
        }

        // 5) explicit kind config file
        val explicitConfig = configFile.orNull?.takeIf { it.isNotBlank() }
        if (explicitConfig != null) {
            val f = File(explicitConfig)
            if (!f.isFile) {
                problems += "sut.cluster.configFile points at a file that does not exist: ${f.absolutePath}"
            } else {
                logger.lifecycle("  ✓ kind config file: {}", f.absolutePath)
            }
        }

        // 6) every registered service has an existing manifest
        val entries = manifests.get()
        if (entries.isEmpty()) {
            logger.lifecycle(
                "  ⚠ no services registered — deploySut will be a no-op. " +
                    "Enable an aux service (e.g. `auxServices { postgres { enabled = true } }`) " +
                    "or register your own under `registry { service(...) { ... } }`.",
            )
        } else {
            entries.forEach { entry ->
                val path = entry.path
                if (path.isBlank()) {
                    problems += "service '${entry.name}' has a blank manifest path."
                } else {
                    val f = File(path)
                    if (!f.isFile) {
                        problems += "service '${entry.name}' references a manifest that does not exist: ${f.absolutePath}"
                    } else {
                        logger.lifecycle(
                            "  ✓ service '{}' (order={}) manifest: {}",
                            entry.name,
                            entry.order,
                            f.absolutePath,
                        )
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            val bullets = problems.joinToString(separator = "\n") { "  • $it" }
            throw GradleException(
                "[checkSutDependency] ${problems.size} precondition(s) failed; refusing to start the SUT:\n" +
                    bullets,
            )
        }
        logger.lifecycle("[checkSutDependency] all preconditions OK.")
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
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ExecResult(124, stdout, "command timed out after 30s: ${cmd.joinToString(" ")}")
            } else {
                ExecResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: IOException) {
            // Most commonly: binary not on PATH (No such file or directory).
            ExecResult(127, "", e.message ?: "")
        }
    }

    companion object {
        const val NAME: String = "checkSutDependency"
    }
}
