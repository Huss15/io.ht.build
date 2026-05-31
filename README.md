# io.ht.buildplugin

Gradle plugin for HT Spring Boot projects. Provides shared build tasks; the first one is `startSut`, which spins up a local [kind](https://kind.sigs.k8s.io/) cluster to use as the System Under Test.

More functionality will be added over time.

## Requirements

- JDK 21
- [`kind`](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) on `PATH`
- A running Docker daemon (kind needs it)

## Build & publish locally

```bash
./gradlew build publishToMavenLocal
```

(Once you've generated the wrapper: `gradle wrapper --gradle-version 8.10`.)

The plugin is published to `~/.m2/repository` as `io.ht:buildplugin:0.0.1-SNAPSHOT` with marker `io.ht.buildplugin`.

## Use in a Spring Boot project

`settings.gradle.kts` of the consuming project:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

`build.gradle.kts`:

```kotlin
plugins {
    id("io.ht.buildplugin") version "0.0.1-SNAPSHOT"
}

sut {
    cluster {
        namespace = "htoffice"                       // REQUIRED — consumer must set
        // nodeImage  = "kindest/node:v1.31.0"       // optional
        // configFile = "$rootDir/kind-config.yaml"  // optional
        // kindBinary = "/usr/local/bin/kind"        // optional, defaults to "kind"
    }

    auxServices {
        postgres {
            enabled = true     // off by default; when true, deploys at order 1
            // order = 5       // optional override
        }
    }

    registry {
        service("backend") {
            order = 20
            file  = "k8s/backend.yaml"
        }
        service("ingress") {
            order = 30
            file  = "k8s/ingress.yaml"
        }
    }
}
```

Then:

```bash
./gradlew startSut       # create kind cluster
./gradlew deploySut      # depends on startSut; applies every manifest in order
./gradlew waitForSut     # depends on deploySut; waits for all rollouts
./gradlew sutServices    # list registered services in deployment order
./gradlew stopSut        # delete the kind cluster
```

## Tasks

| Task          | Group | Depends on   | Description                                                                              |
|---------------|-------|--------------|------------------------------------------------------------------------------------------|
| `startSut`    | `sut` | —            | Creates the local kind cluster (idempotent: skips if it exists).                         |
| `deploySut`   | `sut` | `startSut`   | Applies every registered manifest (sorted by `order`) via `kubectl apply -f`.            |
| `waitForSut`  | `sut` | `deploySut`  | Runs `kubectl rollout status -f <file>` per manifest with configurable timeout.          |
| `stopSut`     | `sut` | —            | Deletes the kind cluster (idempotent: skips if it does not exist).                       |
| `sutServices` | `sut` | —            | Prints registered SUT services in ascending `order`.                                     |

## DSL

```kotlin
sut {
    cluster {
        namespace             = "<required>"          // kind cluster name; no default
        nodeImage             = "kindest/node:<tag>"  // optional
        configFile            = "path/to/kind.yaml"   // optional
        kindBinary            = "kind"                // default
        kubectlBinary         = "kubectl"             // default
        rolloutTimeoutSeconds = 300                   // default (per-manifest)
    }

    auxServices {
        postgres {
            enabled = <Boolean>            // default: false
            order   = <Int>                // default: 1
        }
    }

    registry {
        service("<name>") {
            order = <Int>      // required, deployment priority (low → high)
            file  = "<path>"   // required, path to a YAML manifest
        }
    }
}
```

## Auxiliary services (bundled)

The plugin ships ready-to-use manifests for common dev dependencies. Toggle them
under `auxServices { ... }`; when enabled, they are extracted to
`<buildDir>/htbuild/aux/<name>.yaml` and **auto-registered** into the registry —
no need to write the YAML or `service(...)` block yourself.

| Aux service | Enabled by default | Default order | Manifest contents                                                                                                  |
|-------------|--------------------|---------------|--------------------------------------------------------------------------------------------------------------------|
| `postgres`  | `false`            | `1`           | `Secret` + `Deployment` + `ClusterIP Service` for `postgres:16-alpine`, db/user/password = `sut`, port `5432`, ephemeral storage. **Dev/test only.** |

To enable Postgres with defaults:

```kotlin
sut {
    auxServices { postgres { enabled = true } }
}
```

To override the deployment order so it runs after a custom service:

```kotlin
sut {
    auxServices { postgres { enabled = true; order = 5 } }
    registry {
        service("network-policy") { order = 1; file = "k8s/np.yaml" }
    }
}
```

- `service(name) { ... }` is **idempotent**: calling it again with the same
  name reconfigures the existing entry instead of duplicating it.
- `registry.ordered()` returns services sorted by `order`; it fails fast if any
  entry is missing `order` or `file`. Upcoming deploy tasks will consume this list.

If `sut.cluster.namespace` is not set, `startSut` fails fast with a clear message
pointing back to the DSL.

## Behaviour of `startSut`

1. Validates that `sut.cluster.namespace` is configured.
2. Verifies `kind` is on PATH (`kind version`).
3. Runs `kind get clusters` and skips creation if `namespace` already exists.
4. Otherwise runs `kind create cluster --name <namespace>` plus `--image` / `--config` when configured.
5. Fails the build with a clear error if `kind` is missing or cluster creation fails.

## Behaviour of `deploySut`

1. Runs `startSut` first (declared via `dependsOn`).
2. Validates that `sut.cluster.namespace` is configured.
3. Verifies `kubectl` is on PATH.
4. Snapshots the registry (via `registry.ordered()`) — `afterEvaluate`-registered
   aux services are included.
5. For each entry in ascending `order`, runs
   `kubectl --context kind-<namespace> apply -f <file>`, streaming output.
6. Fails fast on the first non-zero exit code, naming the offending service.
7. Logs `[deploySut] no services registered — nothing to deploy.` if the
   registry is empty (no failure).

## Behaviour of `waitForSut`

1. Runs `deploySut` first.
2. For each manifest (in deployment order), runs
   `kubectl --context kind-<namespace> rollout status -f <file> --timeout=<rolloutTimeoutSeconds>s`.
3. Manifests containing only non-workload resources (Secret, Service, ConfigMap, …)
   are tolerated — kubectl's "no kind that supports rollout" output is treated as ready.
4. Fails fast on the first manifest whose rollout times out or errors, naming the
   offending service.

## Behaviour of `stopSut`

1. Validates that `sut.cluster.namespace` is configured.
2. If `kind` is not on PATH, logs and exits successfully (nothing to clean up).
3. Runs `kind get clusters`; if the cluster is absent, exits successfully.
4. Otherwise runs `kind delete cluster --name <namespace>`.

## Project layout

```
io.ht.buildplugin/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/io/ht/buildplugin/
    │   │   ├── BuildPlugin.kt
    │   │   ├── SutExtension.kt
    │   │   ├── ServiceRegistry.kt
    │   │   ├── ServiceDefinition.kt
    │   │   ├── AuxServicesExtension.kt
    │   │   └── tasks/
    │   │       ├── StartSutTask.kt
    │   │       ├── DeploySutTask.kt
    │   │       ├── WaitForSutTask.kt
    │   │       └── StopSutTask.kt
    │   └── resources/io/ht/buildplugin/aux/
    │       └── postgres.yaml
    └── test/kotlin/io/ht/buildplugin/
        └── BuildPluginTest.kt
```
