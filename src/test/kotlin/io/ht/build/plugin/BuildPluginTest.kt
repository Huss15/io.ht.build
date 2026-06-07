package io.ht.build.plugin

import io.ht.build.plugin.tasks.DeploySutTask
import io.ht.build.plugin.tasks.LoadSutImagesTask
import io.ht.build.plugin.tasks.StartSutTask
import io.ht.build.plugin.tasks.StopSutTask
import io.ht.build.plugin.tasks.WaitForSutTask
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class BuildPluginTest {
    @Test
    fun `applies plugin, registers sut extension and startSut task`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")

        val extension = project.extensions.findByName("sut")
        assertThat(extension).isInstanceOf(SutExtension::class.java)

        val task = project.tasks.findByName(StartSutTask.NAME)
        assertThat(task)
            .isNotNull
            .isInstanceOf(StartSutTask::class.java)
        assertThat(task!!.group).isEqualTo(BuildPlugin.SUT_GROUP)
    }

    @Test
    fun `cluster namespace is wired from extension to task`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)
        ext.cluster.namespace.set("htoffice")

        val task = project.tasks.getByName(StartSutTask.NAME) as StartSutTask
        assertThat(task.clusterName.get()).isEqualTo("htoffice")
        assertThat(task.kindBinary.get()).isEqualTo("kind")
    }

    @Test
    fun `namespace is unset by default - consumer must provide it`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")

        val task = project.tasks.getByName(StartSutTask.NAME) as StartSutTask
        assertThat(task.clusterName.isPresent).isFalse()
    }

    @Test
    fun `registry registers services and returns them ordered by order`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.registry.service("backend") {
            order.set(20)
            file.set("k8s/backend.yaml")
        }
        ext.registry.service("postgres") {
            order.set(10)
            file.set("k8s/postgres.yaml")
        }
        ext.registry.service("ingress") {
            order.set(30)
            file.set("k8s/ingress.yaml")
        }

        val ordered = ext.registry.ordered()
        assertThat(ordered.map { it.name }).containsExactly("postgres", "backend", "ingress")
        assertThat(ordered.map { it.order.get() }).containsExactly(10, 20, 30)
        assertThat(ordered.map { it.file.get() })
            .containsExactly("k8s/postgres.yaml", "k8s/backend.yaml", "k8s/ingress.yaml")
    }

    @Test
    fun `re-registering a service updates it instead of duplicating`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.registry.service("backend") {
            order.set(20)
            file.set("k8s/backend.yaml")
        }
        ext.registry.service("backend") {
            order.set(99)
            file.set("k8s/backend-override.yaml")
        }

        val ordered = ext.registry.ordered()
        assertThat(ordered).hasSize(1)
        assertThat(ordered[0].order.get()).isEqualTo(99)
        assertThat(ordered[0].file.get()).isEqualTo("k8s/backend-override.yaml")
    }

    @Test
    fun `ordered fails fast if a service is missing order or file`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.registry.service("postgres") {
            order.set(10)
            // file missing
        }

        assertThatThrownBy { ext.registry.ordered() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("postgres")
            .hasMessageContaining("order")
            .hasMessageContaining("file")
    }

    @Test
    fun `blank service name is rejected`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        assertThatThrownBy {
            ext.registry.service("  ") {
                order.set(1)
                file.set("x.yaml")
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Service name must not be blank")
    }

    @Test
    fun `auxServices postgres is disabled by default`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        assertThat(
            ext.auxServices.postgres.enabled
                .get(),
        ).isFalse()
        assertThat(ext.auxManifests.get()).isEmpty()
        assertThat(ext.registry.services.findByName(PostgresAux.NAME)).isNull()
    }

    @Test
    fun `enabling postgres auto-registers it as an aux manifest at order 1`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.postgres {
            enabled.set(true)
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val aux = ext.auxManifests.get()
        assertThat(aux).hasSize(1)
        val pg = aux[0]
        assertThat(pg.name).isEqualTo(PostgresAux.NAME)
        assertThat(pg.order).isEqualTo(1)
        val manifest = java.io.File(pg.path)
        assertThat(manifest).exists()
        val text = manifest.readText()
        assertThat(text).contains("kind: Deployment")
        assertThat(text).contains("postgres:16-alpine")
        // Not in user registry — aux is segregated now.
        assertThat(ext.registry.services.findByName(PostgresAux.NAME)).isNull()
    }

    @Test
    fun `postgres order can be overridden`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.postgres {
            enabled.set(true)
            order.set(5)
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val pg = ext.auxManifests.get().first { it.name == PostgresAux.NAME }
        assertThat(pg.order).isEqualTo(5)
    }

    @Test
    fun `auxServices observability is disabled by default`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        assertThat(
            ext.auxServices.observability.enabled
                .get(),
        ).isFalse()
        assertThat(ext.auxManifests.get().map { it.name }).doesNotContain(ObservabilityAux.NAME)
        assertThat(ext.registry.services.findByName(ObservabilityAux.NAME)).isNull()
    }

    @Test
    fun `enabling observability auto-registers it as an aux manifest at order 3`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.observability {
            enabled.set(true)
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val aux = ext.auxManifests.get().first { it.name == ObservabilityAux.NAME }
        assertThat(aux.order).isEqualTo(3)
        val manifest = java.io.File(aux.path)
        assertThat(manifest).exists()
        val text = manifest.readText()
        assertThat(text).contains("kind: Deployment")
        assertThat(text).contains("grafana/otel-lgtm:0.8.1")
        assertThat(ext.registry.services.findByName(ObservabilityAux.NAME)).isNull()
    }

    @Test
    fun `observability order can be overridden`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.observability {
            enabled.set(true)
            order.set(9)
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val aux = ext.auxManifests.get().first { it.name == ObservabilityAux.NAME }
        assertThat(aux.order).isEqualTo(9)
    }

    @Test
    fun `all bundled aux services keep their declared order`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.postgres { enabled.set(true) }
        ext.auxServices.minio {
            enabled.set(true)
            bucket.set("htoffice-bucket")
        }
        ext.auxServices.observability { enabled.set(true) }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        assertThat(ext.auxManifests.get().map { it.name })
            .containsExactly(PostgresAux.NAME, MinioAux.NAME, ObservabilityAux.NAME)
    }

    @Test
    fun `deploySut depends on loadSutImages and exposes only user-registered manifests`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.cluster.namespace.set("htoffice")
        ext.auxServices.postgres { enabled.set(true) } // aux must NOT leak into deploySut
        ext.registry.service("backend") {
            order.set(20)
            file.set("k8s/backend.yaml")
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val deploy = project.tasks.getByName(DeploySutTask.NAME) as DeploySutTask
        assertThat(deploy.group).isEqualTo("sut")
        assertThat(deploy.taskDependencies.getDependencies(deploy).map { it.name })
            .contains(LoadSutImagesTask.NAME)
            .doesNotContain(StartSutTask.NAME)
        assertThat(deploy.kubectlBinary.get()).isEqualTo("kubectl")
        assertThat(deploy.clusterName.get()).isEqualTo("htoffice")

        val entries = deploy.manifests.get()
        assertThat(entries.map { it.name }).containsExactly("backend")
        assertThat(entries[0].path).isEqualTo("k8s/backend.yaml")
    }

    @Test
    fun `deployAuxSut depends on startSut and exposes only aux manifests`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.cluster.namespace.set("htoffice")
        ext.auxServices.postgres { enabled.set(true) }
        ext.registry.service("backend") {
            order.set(20)
            file.set("k8s/backend.yaml")
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val deployAux = project.tasks.getByName(BuildPlugin.DEPLOY_AUX_SUT_TASK) as DeploySutTask
        assertThat(deployAux.group).isEqualTo("sut")
        assertThat(deployAux.taskDependencies.getDependencies(deployAux).map { it.name })
            .contains(StartSutTask.NAME)
        val entries = deployAux.manifests.get()
        assertThat(entries.map { it.name }).containsExactly(PostgresAux.NAME)
    }

    @Test
    fun `buildSutImages depends on deployAuxSut and loadSutImages depends on buildSutImages`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.cluster.namespace.set("htoffice")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val build = project.tasks.getByName(BuildPlugin.BUILD_SUT_IMAGES_TASK)
        assertThat(build.taskDependencies.getDependencies(build).map { it.name })
            .contains(BuildPlugin.DEPLOY_AUX_SUT_TASK)

        val load = project.tasks.getByName(LoadSutImagesTask.NAME)
        assertThat(load.taskDependencies.getDependencies(load).map { it.name })
            .contains(BuildPlugin.BUILD_SUT_IMAGES_TASK)
    }

    @Test
    fun `stopSut task is registered with cluster name and default kind binary`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.cluster.namespace.set("htoffice")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val stop = project.tasks.getByName(StopSutTask.NAME) as StopSutTask
        assertThat(stop.group).isEqualTo("sut")
        assertThat(stop.clusterName.get()).isEqualTo("htoffice")
        assertThat(stop.kindBinary.get()).isEqualTo("kind")
    }

    @Test
    fun `waitForSut depends on deploySut and reflects aux + user manifests`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.cluster.namespace.set("htoffice")
        ext.auxServices.postgres { enabled.set(true) }
        ext.registry.service("backend") {
            order.set(20)
            file.set("k8s/backend.yaml")
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val wait = project.tasks.getByName(WaitForSutTask.NAME) as WaitForSutTask
        assertThat(wait.group).isEqualTo("sut")
        assertThat(wait.taskDependencies.getDependencies(wait).map { it.name })
            .contains(DeploySutTask.NAME)
        assertThat(wait.clusterName.get()).isEqualTo("htoffice")
        assertThat(wait.kubectlBinary.get()).isEqualTo("kubectl")
        assertThat(wait.timeoutSeconds.get()).isEqualTo(WaitForSutTask.DEFAULT_TIMEOUT_SECONDS)

        val entries = wait.manifests.get()
        assertThat(entries.map { it.name }).containsExactly(PostgresAux.NAME, "backend")
    }

    @Test
    fun `rolloutTimeoutSeconds can be overridden via cluster DSL`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.cluster.namespace.set("htoffice")
        ext.cluster.rolloutTimeoutSeconds.set(45)

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val wait = project.tasks.getByName(WaitForSutTask.NAME) as WaitForSutTask
        assertThat(wait.timeoutSeconds.get()).isEqualTo(45)
    }

    @Test
    fun `auxServices minio is disabled by default and not registered`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        assertThat(
            ext.auxServices.minio.enabled
                .get(),
        ).isFalse()
        assertThat(ext.auxManifests.get().map { it.name }).doesNotContain(MinioAux.NAME)
        assertThat(ext.registry.services.findByName(MinioAux.NAME)).isNull()
    }

    @Test
    fun `enabling minio without a bucket fails fast with a helpful message`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.minio {
            enabled.set(true)
            // bucket intentionally unset
        }

        assertThatThrownBy {
            (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.rootCause()
            .isInstanceOf(org.gradle.api.GradleException::class.java)
            .hasMessageContaining("sut.auxServices.minio.bucket")
    }

    @Test
    fun `enabling minio with a bucket registers it as an aux manifest at order 2 with substituted bucket`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.minio {
            enabled.set(true)
            bucket.set("htoffice-bucket")
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val m = ext.auxManifests.get().firstOrNull { it.name == MinioAux.NAME }
        assertThat(m).isNotNull
        assertThat(m!!.order).isEqualTo(2)
        val manifest = java.io.File(m.path)
        assertThat(manifest).exists()
        val text = manifest.readText()
        assertThat(text).contains("kind: Deployment")
        assertThat(text).contains("image: minio/minio")
        assertThat(text).contains("htoffice-bucket")
        assertThat(text).doesNotContain(MinioAux.BUCKET_PLACEHOLDER)
    }

    @Test
    fun `minio order can be overridden`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.ht.build")
        val ext = project.extensions.getByType(SutExtension::class.java)

        ext.auxServices.minio {
            enabled.set(true)
            bucket.set("b")
            order.set(7)
        }

        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val m = ext.auxManifests.get().first { it.name == MinioAux.NAME }
        assertThat(m.order).isEqualTo(7)
    }
}
