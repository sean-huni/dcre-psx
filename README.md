# dcre-psx

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register#repositories) table.

PSX is the **SBSR response-leg reader of the PAYMENTS family**: it ingests a Fintegrate
`_SBSR` reply file into `sbsr_resp` in `dcre_pay`.

## What it does

| | |
| --- | --- |
| Stage code | `PSX` (AGT `Stage.PSX`) |
| Family / leg | payments (ENDO), RES |
| Trigger | arrival-launched: one Kubernetes Job per `_SBSR` reply file |
| Upstream | none in the DAG. The input is a Fintegrate reply registered by AGT |
| Downstream | none in the DAG. PRG reads `sbsr_resp` (through its `prg_sbsr_pick` view, checked 2026-09-28) when AGT launches it |
| Diagram sheet | `dcre-payments-res` in the design register |

The payments RES sheet requires three parallel readers:

```
Fint Resp ->  { PIX -> isr_resp , PSX -> sbsr_resp , PPX -> pbsr_resp }  -> PRG -> OnHost Resp
```

In AGT (`RouteDags.FINT_RESP_PAY`, checked 2026-09-28) a response DAG has no successor edges: the
leg reader is the entry and is itself terminal. Fintegrate drops the reply into the client's
`<client>/fint-resp/in` exchange directory. That channel is shared with collections, so AGT picks
the family from the client token (payments when it is listed in AGT's `AGT_PAY_CLIENTS`, an interim
R-42 rule, default `FNBRF01`) and then the leg from the `_SBSR` filename token, and launches PSX in
the `dcre-pay` namespace. An unknown token launches nothing (fail closed).

PSX parses the reply, one `<OrgnlMsgId>` plus repeated `<Tx>` blocks of `<OrgnlEndToEndId>` +
`<TxSts>` + optional `<Rsn>` ([SYNTHETIC-CONTRACT R-35] shape), and upserts one `sbsr_resp` row per
Tx block (fan-out at ingest per R-17).

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 / Java 25, run as a one-shot process. One job, one tasklet step.

### Database

- **Database today:** `dcre_pay`, via `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. A second
  datasource, `DCRE_AGTOPS_DB_URL` / `_USER` / `_PASSWORD`, targets AGT's `agt_ops` for the
  `HeartbeatWriter` liveness stamp on `agt_ops.launch_intent`.
- **Writes:** `sbsr_resp` (created and owned here), `PSX_BATCH_*` Spring Batch metadata, and the
  Liquibase history `psx_databasechangelog` / `psx_databasechangeloglock`.
- **Reads:** no table. The only input is the reply file.

### Family boundary

PSX does not read collections' table of the same name, and it holds no connection to `dcre_col`.
The table name is unprefixed because every family sheet labels the response cylinders identically
and the database supplies the namespace; `dcre_man`'s `man_*_resp` is a diagram deviation recorded
as a mandates follow-up, not a pattern to copy.

Forked from `collections/cix` and reduced. Two things were deliberately **not** carried across:

- **The CRW batch correlation** (`emission_id`, the `crw_emission` lookup, the fail-closed
  foreign-e2e filter). `crw_emission` lives in `dcre_col` under collections ownership, so a reader
  in `dcre_pay` has no route to it. PRG correlates payments replies to `prw_emission` by
  `orgnl_msg_id` instead (checked 2026-09-28).
- **The hand-rolled `Dockerfile`.** Images build through Paketo (`bootBuildImage`), per the estate
  mandate.

### Invariants

- **Full-identity idempotency**: `INSERT ... ON CONFLICT (response_file, e2e) DO UPDATE` on the
  complete uniqueness tuple of a verdict (`uq_pay_sbsr_resp_file_e2e`). A replay of the same file
  adds no rows. `e2e` alone would let a later reply file overwrite an earlier file's verdict and
  still report success.
- **Sliced ingest (SCRUM-42)**: one giant serializable transaction is unrefreshable at 300k rows
  (`RETRY_SERIALIZABLE`), so upserts commit in bounded slices (`dcre.psx.ingest-slice-size`, default
  10000), each in its own `REQUIRES_NEW` transaction wrapped by `CrdbRetry` (5 attempts, exponential
  backoff with jitter). Committed slices stand when a later slice fails; a restart resumes the rest.
- **CRDB 40001 retry** is registered on the ingest step through the shared
  `CrdbRetryExceptionHandler`, covering commit-time aborts.
- **Stale-execution sweep (A-39a)**: `StaleExecutionSweeper.abandonStale(ds, "PSX_BATCH_", 60)` runs
  before job launch so a relaunch after a pod kill never throws `JobExecutionAlreadyRunning`.
- **Fail closed**: a reply with no `OrgnlMsgId` fails the job rather than storing orphan rows.
- **Outcome seam (SCRUM-58)**: the shared `OutcomeSeamListener` writes `BUSINESS_ACCEPTED` to
  `<exchange-root>/outcomes/<JOB_NAME>` on COMPLETED; without `JOB_NAME` the dev fallback name is
  `local-psx-<executionId>`.

### Schema

`sbsr_resp` (Liquibase `db/changelog/2026/08/001-pay-sbsr-resp.xml`, typed tags only): `response_file`
VARCHAR(512), `orgnl_msg_id`, `e2e`, `status`, `reason` (nullable), plus `BaseEntity` columns
(`version`, `created_at`, `updated_at`); `UNIQUE (response_file, e2e)` as
`uq_pay_sbsr_resp_file_e2e`. `response_file` is 512 from birth, matching
`agt_ops.file_arrival.physical_filename`, so no widening ALTER is ever needed.

Batch metadata lives in `PSX_BATCH_`-prefixed tables via a Liquibase-owned copy of the Batch 6 DDL
with `EXIT_MESSAGE` widened to TEXT (`002-batch-metadata.xml`).

Bootstrap guards use `onFail="CONTINUE"`, never `MARK_RAN`: `MARK_RAN` records the skip permanently,
so a database that was merely not-yet-ready never receives the change at all. That shape has cost
this project two defects (A-79, A-81).

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem`
- Gradle 9.5.1 via the committed wrapper (`gradle/wrapper/gradle-wrapper.properties`)
- Docker, for the Testcontainers suite and the Paketo image build
- Platform libraries in Maven Local (no remote repository): `za.co.fnb.dcre:platform-persistence:0.1.0`
  and `za.co.fnb.dcre:platform-batch:0.1.0`. Run `./gradlew publishToMavenLocal` in
  `dcre-platform-model`, then `dcre-platform-files`, then `dcre-platform-batch` (batch brings files
  and model transitively); `dcre-platform-persistence` is standalone.
- For a real run: a reachable CockroachDB (the `dcre-infra` kind cluster locally)

## Quickstart

A clean clone runs with no `.env`; working dev defaults are committed in `application.yml`.

```bash
./gradlew clean build
java -jar build/libs/psx-2.0.jar \
  arrival.id=<uuid> input.file=/path/20260712_FNB_SBSR_reply.xml original.name=20260712_FNB_SBSR_reply.xml
```

## Configuration

12FactorApp Alignment (https://12factor.net/): working dev defaults are committed, and a fresh clone
runs with no `.env` at all. Precedence: `application.yml` default < environment variable.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_pay?sslmode=disable` | Payments database. AGT injects this exact name and routes the value per family |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | Payments credentials |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat liveness stamp |
| `DCRE_AGTOPS_DB_USER` / `DCRE_AGTOPS_DB_PASSWORD` | `root` / empty | Heartbeat credentials |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` (six `../`) | Outcome seam root |
| `DCRE_PSX_INGEST_SLICE_SIZE` | `10000` | Rows per committed ingest slice (SCRUM-42) |
| `JOB_NAME` | unset: seam falls back to `local-psx-<executionId>` | Set by AGT on the K8s Job |

This table is the documented set, not a closed total: Spring Boot relaxed binding lets any property
be overridden by its environment-variable form (for example `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`).

## Testing

`./gradlew clean build` runs everything (Docker required). Integration tests use Testcontainers
CockroachDB `cockroachdb/cockroach:v26.2.3`.

- `PsxJobTest`: full job; ingests a 4-Tx synthetic reply (ACSC/RJCT with reason AC04), asserts
  per-row status/reason/orgnl_msg_id, and proves replay of the same file stays at 4 rows. Second
  test pins the `local-psx-<executionId>` seam fallback.
- `PsxJobConfigRetryTest`: proves the shared 40001 retry handler is registered on the step the real
  job config builds, covering commit-time aborts.
- `ReaderServiceSliceTest`: sliced-ingest proofs against a real CRDB (committed slices survive a
  later failure; a transient abort retries in a fresh transaction; a re-run adds no dup rows).
- `ResponseFileWidthIT`: a 200-char `response_file` round-trips and the replay-guard UNIQUE still bites.
- `ConfigPrefixBindingTest`: red-proofs the `dcre.cix` -> `dcre.psx` rename by reading the `@Value`
  placeholder off the real constructor and asserting `application.yml` **declares** that key. Also
  pins the `dcre_pay` datasource and the six-level `exchange-root` depth.
- `PayFlowOnlyTest`: no `flow` discriminator in sources, resources, the entity or the launch
  parameters, and no reference to `crw_emission` or `dcre_col` anywhere.
- Cucumber (`CucumberSuiteTest`, `features/sbsr-reply-reader.feature`): fan-out, reason codes, replay,
  a malformed reply with no `OrgnlMsgId`, and an empty reply.

## Local cluster deployment

```bash
./gradlew bootBuildImage        # Paketo, builder-noble-java-tiny, BP_JVM_VERSION=25 -> dcre-psx:2.0
kind load docker-image --name dcre-dev dcre-psx:2.0
kubectl set env -n dcre deploy/dcre-agt AGT_PSX_IMAGE=dcre-psx:2.0
```

The cluster (`dcre-dev`, CockroachDB, AGT) comes from `dcre-infra` (`scripts/kind-up.sh`;
`scripts/env-reset.sh` for a clean slate). AGT resolves the image from `AGT_PSX_IMAGE`; empty means
launch-disabled. `dcre-infra`'s `scripts/switch-version.sh` does not export `AGT_PSX_IMAGE` (its
stage roster predates the payments split, checked 2026-09-28), hence the explicit `kubectl set env`.

AGT launches the Job in `dcre-pay` with program args `arrival.id=<uuid>` (identifying),
`input.file=<claimed path>` and `original.name=<physical filename>` (both non-identifying), and env
`JOB_NAME`, `DCRE_DB_URL` (the `dcre_pay` URL), `DCRE_EXCHANGE_ROOT=/exchange`, `DCRE_AGTOPS_DB_URL`
and `DCRE_AGTOPS_DB_USER` (AGT `JobLauncher`, checked 2026-09-28).

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
