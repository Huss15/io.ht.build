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
 * Re-verifies that all rollouts referenced by the registered SUT manifests are ready.
 *
 * Since [DeploySutTask] now applies-and-waits per manifest sequentially (with per-resource
 * rollout-status and per-Job `kubectl wait`), this task is mostly a standalone health probe:
 * it can be invoked on an already-deployed cluster to confirm all Deployments / StatefulSets /
 * DaemonSets are still rolled out. On a freshly deployed SUT it is a near-instant no-op.
 *
 * For every manifest (in deployment order), enumerates rollout-capable resources via
 * `kubectl get -f <file> -o name` and then runs
 * `kubectl rollout status <ref> --timeout=<seconds>s` for each. Manifests with only non-workload
 * resources (Secrets, Services, ConfigMaps, …) are tolerated.
 *
 * Depends on [DeploySutTask].
 */
@DisableCachingByDefault(because = "Wait task observes external cluster state.")
abstract class WaitForSutTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val clusterName: Property<String>

    @get:Input
    abstract val kubectlBinary: Property<String>

    @get:Input
    abstract val timeoutSeconds: Property<Int>

    /** Manifest entries (already sorted by deployment order). */
    @get:Input
    abstract val manifests: ListProperty<DeploySutTask.ManifestEntry>

    @TaskAction
    fun waitForSut() {
        val kubectl = kubectlBinary.get()
        val name =
            clusterName.orNull?.takeIf { it.isNotBlank() }
                ?: throw GradleException("[waitForSut] sut.cluster.namespace is not configured.")
        val timeout = timeoutSeconds.get()
        val context = "kind-$name"

        val entries = manifests.get()
        if (entries.isEmpty()) {
            logger.lifecycle("[waitForSut] no services registered — nothing to wait for.")
            return
        }

        logger.lifecycle(
            "[waitForSut] re-verifying {} manifest(s) in context '{}' (timeout={}s per rollout)",
            entries.size,
            context,
            timeout,
        )

        entries.forEach { entry ->
            val file = File(entry.path)
            if (!file.isFile) {
                throw GradleException("[waitForSut] manifest for service '${entry.name}' not found: ${file.absolutePath}")
            }
            val listed = exec(listOf(kubectl, "--context", context, "get", "-f", file.absolutePath, "-o", "name"))
            if (listed.exitCode != 0) {
                val msg = (listed.stdout + listed.stderr).trim()
                throw GradleException(
                    "[waitForSut] could not enumerate resources for service '${entry.name}' (exit ${listed.exitCode}): $msg",
                )
            }
            val workloads =
                listed.stdout
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filter { it.isRolloutKind() }
                    .toList()
            if (workloads.isEmpty()) {
                logger.lifecycle("  ⏳ [{}] no rollout-capable resources — treating as ready", entry.name)
                return@forEach
            }
            logger.lifecycle("  ⏳ [{}] re-checking {} rollout(s): {}", entry.name, workloads.size, workloads)
            workloads.forEach { ref ->
                val cmd = listOf(kubectl, "--context", context, "rollout", "status", ref, "--timeout=${timeout}s")
                val result = exec(cmd)
                val combined = (result.stdout + result.stderr).trim()
                if (combined.isNotBlank()) logger.lifecycle(combined)
                if (result.exitCode != 0) {
                    throw GradleException(
                        "[waitForSut] rollout did not become ready for $ref (service '${entry.name}', exit ${result.exitCode}). " +
                            "Command: ${cmd.joinToString(" ")}",
                    )
                }
            }
        }
        logger.lifecycle("[waitForSut] all {} manifest(s) are ready in '{}'.", entries.size, context)
    }

    private fun String.isRolloutKind(): Boolean {
        val kind = substringBefore('/').substringBefore('.').lowercase()
        return kind == "deployment" || kind == "statefulset" || kind == "daemonset"
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
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw GradleException("[waitForSut] command timed out: ${cmd.joinToString(" ")}")
            }
            ExecResult(process.exitValue(), stdout, stderr)
        } catch (e: IOException) {
            ExecResult(127, "", e.message ?: "")
        }
    }

    companion object {
        const val NAME = "waitForSut"

        /**
         * Default per-manifest rollout timeout in seconds.
         *
         * Set to 10 minutes to tolerate cold image pulls on a fresh kind cluster — kind nodes
         * run their own containerd and do not share the host Docker image cache, so first-time
         * pulls of images like `postgres`, `minio/minio`, or `minio/mc` can easily take 2–5
         * minutes on slower connections.
         *
         * Override with `sut { cluster { rolloutTimeoutSeconds = 1200 } }` if your network
         * is even slower, or set lower (e.g. 120) for CI where images are pre-pulled.
         */
        const val DEFAULT_TIMEOUT_SECONDS: Int = 600
    }
}
