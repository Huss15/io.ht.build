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
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * Deploys all registered SUT services into the kind cluster, **sequentially**.
 *
 * For each manifest path in [manifests] (ordered by [io.ht.build.plugin.ServiceDefinition.order]):
 *  1. `kubectl --context kind-<clusterName> apply -f <file>`
 *  2. Enumerates the manifest's resources via `kubectl get -f <file> -o name`, then runs
 *     `kubectl rollout status <ref> --timeout=<timeoutSeconds>s` for every Deployment /
 *     StatefulSet / DaemonSet (other kinds are ignored — rollout-status doesn't understand them).
 *  3. Enumerates every Job in the manifest and runs
 *     `kubectl wait --for=condition=complete job/<name> --timeout=<timeoutSeconds>s`.
 *
 * The next manifest is only applied once the previous one's workloads have rolled out and any
 * Jobs have completed. Any failure aborts immediately.
 *
 * Depends on [StartSutTask] (for `deployAuxSut`) or [LoadSutImagesTask] (for `deploySut`).
 */
@DisableCachingByDefault(because = "kubectl apply mutates external cluster state; idempotency is server-side.")
abstract class DeploySutTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val clusterName: Property<String>

    @get:Input
    abstract val kubectlBinary: Property<String>

    /** Per-manifest rollout timeout (seconds). */
    @get:Input
    abstract val timeoutSeconds: Property<Int>

    /** Manifest file paths, already sorted by deployment order. Resolved at execution. */
    @get:Input
    abstract val manifests: ListProperty<ManifestEntry>

    @TaskAction
    fun deploy() {
        val kubectl = kubectlBinary.get()
        val name =
            clusterName.orNull?.takeIf { it.isNotBlank() }
                ?: throw GradleException(
                    "[deploySut] sut.cluster.namespace is not configured. " +
                        "Add `sut { cluster { namespace = \"<name>\" } }` to your build script.",
                )
        val timeout = timeoutSeconds.get()
        val context = "kind-$name"

        ensureKubectlAvailable(kubectl)

        val entries = manifests.get()
        if (entries.isEmpty()) {
            logger.lifecycle(
                "[deploySut] no services registered — nothing to deploy. " +
                    "Add entries under `sut { registry { ... } }` or enable an aux service.",
            )
            return
        }

        logger.lifecycle(
            "[deploySut] applying {} manifest(s) sequentially to context '{}' (rollout timeout={}s):",
            entries.size,
            context,
            timeout,
        )
        entries.forEach { entry ->
            val file = File(entry.path)
            if (!file.isFile) {
                throw GradleException("[deploySut] manifest for service '${entry.name}' not found: ${file.absolutePath}")
            }
            applyManifest(kubectl, context, entry, file)
            waitForRollout(kubectl, context, entry, file, timeout)
            waitForJobs(kubectl, context, entry, file, timeout)
        }
        logger.lifecycle("[deploySut] all {} service(s) applied and ready in '{}'.", entries.size, context)
    }

    private fun applyManifest(
        kubectl: String,
        context: String,
        entry: ManifestEntry,
        file: File,
    ) {
        logger.lifecycle("  → [{}] order={} apply {}", entry.name, entry.order, file.absolutePath)
        val cmd = listOf(kubectl, "--context", context, "apply", "-f", file.absolutePath)
        val result = exec(cmd, inheritIo = true)
        if (result.exitCode != 0) {
            throw GradleException(
                "[deploySut] kubectl apply failed for service '${entry.name}' (exit ${result.exitCode}). " +
                    "Command: ${cmd.joinToString(" ")}",
            )
        }
    }

    private fun waitForRollout(
        kubectl: String,
        context: String,
        entry: ManifestEntry,
        file: File,
        timeout: Int,
    ) {
        // Enumerate all resources defined in the manifest, then issue rollout-status only for the
        // workload kinds that actually support it. This avoids the trap where `kubectl rollout
        // status -f <file>` exits 1 on the first non-rollout resource (e.g. Job, Secret, Service)
        // before the Deployment has had a chance to become ready.
        val listed = exec(listOf(kubectl, "--context", context, "get", "-f", file.absolutePath, "-o", "name"))
        if (listed.exitCode != 0) {
            val msg = (listed.stdout + listed.stderr).trim()
            throw GradleException(
                "[deploySut] could not enumerate resources for service '${entry.name}' (exit ${listed.exitCode}): $msg",
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
            logger.lifecycle(
                "  ⏳ [{}] no rollout-capable resources in manifest — skipping rollout-status",
                entry.name,
            )
            return
        }
        logger.lifecycle("  ⏳ [{}] waiting for {} rollout(s): {}", entry.name, workloads.size, workloads)
        workloads.forEach { ref ->
            val cmd = listOf(kubectl, "--context", context, "rollout", "status", ref, "--timeout=${timeout}s")
            val result = exec(cmd)
            val combined = (result.stdout + result.stderr).trim()
            if (combined.isNotBlank()) logger.lifecycle(combined)
            if (result.exitCode != 0) {
                if (isTimeout(combined)) {
                    throw GradleException(
                        "[deploySut] timed out after ${timeout}s waiting for $ref (service '${entry.name}'). " +
                            "This is usually a slow image pull on a fresh kind cluster — kind nodes don't share " +
                            "the host Docker image cache. Run it again (the image is now cached in the kind node), " +
                            "or increase the timeout via `sut { cluster { rolloutTimeoutSeconds = 1200 } }`. " +
                            "Inspect with: kubectl --context $context get pods -A",
                    )
                }
                throw GradleException(
                    "[deploySut] rollout did not become ready for $ref (service '${entry.name}', exit ${result.exitCode}). " +
                        "Command: ${cmd.joinToString(" ")}",
                )
            }
        }
    }

    /**
     * Returns true if the resource reference returned by `kubectl get -o name` is a workload kind
     * that supports `kubectl rollout status`. Matches both short (`deployment/...`) and long
     * (`deployment.apps/...`) forms.
     */
    private fun String.isRolloutKind(): Boolean {
        val kind = substringBefore('/').substringBefore('.').lowercase()
        return kind == "deployment" || kind == "statefulset" || kind == "daemonset"
    }

    /**
     * After rollout-status, find every Job in the manifest and wait for `condition=complete`.
     * `kubectl rollout status` does not understand Jobs, so they're invisible to the previous step.
     * Treats manifests without Jobs as a no-op.
     */
    private fun waitForJobs(
        kubectl: String,
        context: String,
        entry: ManifestEntry,
        file: File,
        timeout: Int,
    ) {
        val listed = exec(listOf(kubectl, "--context", context, "get", "-f", file.absolutePath, "-o", "name"))
        if (listed.exitCode != 0) {
            val msg = (listed.stdout + listed.stderr).trim()
            logger.warn(
                "  ⚠ [{}] could not enumerate resources to detect Jobs (exit {}): {}",
                entry.name,
                listed.exitCode,
                msg,
            )
            return
        }
        val jobNames =
            listed.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { it.startsWith("job/") || it.startsWith("job.batch/") }
                .map { it.substringAfter('/') }
                .toList()
        if (jobNames.isEmpty()) return
        logger.lifecycle("  ⏳ [{}] waiting for {} job(s) to complete: {}", entry.name, jobNames.size, jobNames)
        jobNames.forEach { jobName ->
            val waitCmd =
                listOf(
                    kubectl,
                    "--context",
                    context,
                    "wait",
                    "--for=condition=complete",
                    "job/$jobName",
                    "--timeout=${timeout}s",
                )
            val result = exec(waitCmd)
            val combined = (result.stdout + result.stderr).trim()
            if (combined.isNotBlank()) logger.lifecycle(combined)
            if (result.exitCode != 0) {
                throw GradleException(
                    "[deploySut] job '$jobName' (service '${entry.name}') did not complete within ${timeout}s. " +
                        "Inspect with: kubectl --context $context describe job/$jobName ; " +
                        "kubectl --context $context logs job/$jobName",
                )
            }
        }
    }

    private fun isTimeout(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("timed out waiting for the condition") ||
            (lower.contains("timeout") && lower.contains("waiting"))
    }

    private fun ensureKubectlAvailable(kubectl: String) {
        val result = exec(listOf(kubectl, "version", "--client=true", "--output=json"))
        if (result.exitCode != 0) {
            val plain = exec(listOf(kubectl, "version", "--client=true"))
            if (plain.exitCode != 0) {
                throw GradleException(
                    "[deploySut] '$kubectl' is not available on PATH or failed to run. " +
                        "Install kubectl: https://kubernetes.io/docs/tasks/tools/",
                )
            }
        }
    }

    private data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun exec(
        cmd: List<String>,
        inheritIo: Boolean = false,
    ): ExecResult {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        if (inheritIo) pb.inheritIO()
        return try {
            val process = pb.start()
            val stdout = if (inheritIo) "" else process.inputStream.readBytes().toString(Charsets.UTF_8)
            val stderr = if (inheritIo) "" else process.errorStream.readBytes().toString(Charsets.UTF_8)
            // Use 2x rollout timeout (or 30 min, whichever is larger) as the process hard-cap.
            val processTimeoutSeconds = maxOf(30L * 60L, 2L * timeoutSeconds.getOrElse(0).toLong())
            if (!process.waitFor(processTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw GradleException("[deploySut] command timed out: ${cmd.joinToString(" ")}")
            }
            ExecResult(process.exitValue(), stdout, stderr)
        } catch (e: IOException) {
            ExecResult(127, "", e.message ?: "")
        }
    }

    /** A single manifest entry to be applied, in deployment order. */
    data class ManifestEntry(
        val name: String,
        val order: Int,
        val path: String,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        const val NAME = "deploySut"
    }
}
