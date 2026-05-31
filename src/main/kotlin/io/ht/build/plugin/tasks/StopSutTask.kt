package io.ht.build.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Deletes the local kind cluster used as System Under Test.
 *
 * Idempotent: if the cluster does not exist, the task is a no-op.
 * Runs `kind delete cluster --name <clusterName>`.
 */
@DisableCachingByDefault(because = "Cluster deletion has external side effects; idempotency is handled in the task itself.")
abstract class StopSutTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val clusterName: Property<String>

    @get:Input
    abstract val kindBinary: Property<String>

    @TaskAction
    fun stopSut() {
        val kind = kindBinary.get()
        val name =
            clusterName.orNull?.takeIf { it.isNotBlank() }
                ?: throw GradleException(
                    "[stopSut] sut.cluster.namespace is not configured. " +
                        "Add `sut { cluster { namespace = \"<name>\" } }` to your build script.",
                )

        if (!kindAvailable(kind)) {
            logger.lifecycle("[stopSut] '{}' not on PATH — nothing to do.", kind)
            return
        }

        if (!clusterExists(kind, name)) {
            logger.lifecycle("[stopSut] kind cluster '{}' does not exist — nothing to do.", name)
            return
        }

        logger.lifecycle("[stopSut] deleting kind cluster '{}'", name)
        val result = exec(listOf(kind, "delete", "cluster", "--name", name), inheritIo = true)
        if (result.exitCode != 0) {
            throw GradleException("[stopSut] kind delete cluster failed with exit code ${result.exitCode}")
        }
        logger.lifecycle("[stopSut] kind cluster '{}' deleted.", name)
    }

    private fun kindAvailable(kind: String): Boolean = exec(listOf(kind, "version")).exitCode == 0

    private fun clusterExists(
        kind: String,
        name: String,
    ): Boolean {
        val result = exec(listOf(kind, "get", "clusters"))
        if (result.exitCode != 0) return false
        return result.stdout.lineSequence().any { it.trim() == name }
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
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw GradleException("[stopSut] command timed out: ${cmd.joinToString(" ")}")
            }
            ExecResult(process.exitValue(), stdout, stderr)
        } catch (e: IOException) {
            ExecResult(127, "", e.message ?: "")
        }
    }

    companion object {
        const val NAME = "stopSut"
    }
}
