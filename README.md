# dcre-psx

PSX is the **N/ACK response-leg reader of the PAYMENTS family**. It is one of the three parallel
readers the payments response sheet requires:

```
Fint Resp ->  { PSX -> sbsr_resp , PSX -> sbsr_resp , PPX -> pbsr_resp }  -> PRG -> OnHost Resp
```

Fintegrate drops a reply file into a per-client `fint-resp-pay/in` exchange directory; AGT selects
the reader by the `_SBSR` filename token and launches PSX as a short-lived Kubernetes Job in the
`dcre-pay` namespace. PSX parses the reply, one `<OrgnlMsgId>` plus repeated `<Tx>` blocks of
`<OrgnlEndToEndId>` + `<TxSts>` + optional `<Rsn>` ([SYNTHETIC-CONTRACT R-35] shape), and upserts
one `sbsr_resp` row per Tx block (fan-out at ingest per R-17). Replaying the same file is a no-op via
`INSERT ... ON CONFLICT (response_file, e2e)`.

## Family boundary

PSX writes **`sbsr_resp` in `dcre_pay`**, which it creates and owns. It does not read collections'
table of the same name, and it holds no connection to `dcre_col`. The table name is unprefixed
because every family sheet labels the response cylinders identically and the database supplies the
namespace; `dcre_man`'s `man_*_resp` is a diagram deviation recorded as a mandates follow-up, not a
pattern to copy.

Forked from `collections/cix` and reduced. Two things were deliberately **not** carried across:

- **The CRW batch correlation** (`emission_id`, the `crw_emission` lookup, the fail-closed
  foreign-e2e filter). `crw_emission` lives in `dcre_col` under collections ownership, so a reader
  in `dcre_pay` has no route to it. When the payments writer registers its own emissions in
  `dcre_pay`, the guard can return against **that** registry.
- **The hand-rolled `Dockerfile`.** Images build through Paketo (`bootBuildImage`), per the estate
  mandate. A brand-new repo with no published image is the cheapest adoption point.

## Behaviour worth knowing

- **Sliced ingest (SCRUM-42)**: one giant serializable transaction is unrefreshable at 300k rows
  (`RETRY_SERIALIZABLE`), so upserts commit in bounded slices (`dcre.psx.ingest-slice-size`, default
  10000), each in its own `REQUIRES_NEW` transaction wrapped by `CrdbRetry` (5 attempts, exponential
  backoff with jitter). Committed slices stand when a later slice fails; a restart no-ops over them
  and resumes the rest.
- **Full-identity idempotency**: the upsert key is `(response_file, e2e)`, the complete uniqueness
  tuple of a verdict. `e2e` alone would let a later reply file overwrite an earlier file's verdict
  and still report success.
- **CRDB 40001 retry** is registered on the ingest step through the shared
  `CrdbRetryExceptionHandler`, covering commit-time aborts.
- **Stale-execution sweep (A-39a)**: `StaleExecutionSweeper.abandonStale(ds, "PSX_BATCH_", 60)` runs
  before job launch so a relaunch after a pod kill never throws `JobExecutionAlreadyRunning`.
- **Outcome seam (SCRUM-58)**: the shared `OutcomeSeamListener` writes `BUSINESS_ACCEPTED` on
  COMPLETED; without `JOB_NAME` the dev fallback name is `local-psx-<executionId>`.

## Schema

`sbsr_resp` (Liquibase `db/changelog/2026/08/001-pay-sbsr-resp.xml`, typed tags only): `response_file`
VARCHAR(512), `orgnl_msg_id`, `e2e`, `status`, `reason` (nullable), plus `BaseEntity` columns
(`version`, `created_at`, `updated_at`); `UNIQUE (response_file, e2e)` as
`uq_pay_sbsr_resp_file_e2e`. `response_file` is 512 from birth, matching
`agt_ops.file_arrival.physical_filename`, so no widening ALTER is ever needed.

Batch metadata lives in `PSX_BATCH_`-prefixed tables via a Liquibase-owned copy of the Batch 6 DDL
with `EXIT_MESSAGE` widened to TEXT (`002-batch-metadata.xml`). Liquibase history is per service:
`psx_databasechangelog` / `psx_databasechangeloglock`.

Bootstrap guards use `onFail="CONTINUE"`, never `MARK_RAN`: `MARK_RAN` records the skip permanently,
so a database that was merely not-yet-ready never receives the change at all. That shape has cost
this project two defects (A-79, A-81).

## Run

```bash
./gradlew clean build
java -jar build/libs/psx-2.0.jar \
  arrival.id=<uuid> input.file=/path/20260712_FNB_SBSR_reply.xml original.name=20260712_FNB_SBSR_reply.xml
```

## Configuration

12FactorApp Alignment (https://12factor.net/): working dev defaults are committed, and a fresh clone
runs with no `.env` at all.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_pay?sslmode=disable` | Payments database |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | Payments credentials |
| `DCRE_AGTOPS_DB_URL` | `.../agt_ops` | Heartbeat liveness stamp |
| `DCRE_EXCHANGE_ROOT` | six `../` to `infra/dcre-infra/exchange` | Outcome seam root |
| `DCRE_PSX_INGEST_SLICE_SIZE` | `10000` | Rows per committed ingest slice (SCRUM-42) |

## Tests

`./gradlew clean build` runs everything.

- `PsxJobTest`: full job on Testcontainers CockroachDB `v26.2.3`; ingests a 4-Tx synthetic reply
  (ACSC/RJCT with reason AC04), asserts per-row status/reason/orgnl_msg_id, and proves replay of the
  same file stays at 4 rows. Second test pins the `local-psx-<executionId>` seam fallback.
- `PsxJobConfigRetryTest`: proves the shared 40001 retry handler is registered on the step the real
  job config builds, covering commit-time aborts.
- `ReaderServiceSliceTest`: sliced-ingest proofs against a real CRDB (committed slices survive a
  later failure; a transient abort retries in a fresh transaction; a re-run no-ops without dup rows).
- `ResponseFileWidthIT`: a 200-char `response_file` round-trips and the replay-guard UNIQUE still bites.
- `ConfigPrefixBindingTest`: red-proofs the `dcre.cix` -> `dcre.psx` rename by reading the `@Value`
  placeholder off the real constructor and asserting `application.yml` **declares** that key. A
  lookup would return the constant default either way; this asserts the map is populated. Also pins
  the `dcre_pay` datasource and the six-level `exchange-root` depth.
- `PayFlowOnlyTest`: no `flow` discriminator in sources, resources, the entity or the launch
  parameters, and no reference to `crw_emission` or `dcre_col` anywhere.
- Cucumber (`isr-reply-reader.feature`): 5 business-readable scenarios covering fan-out, reason
  codes, replay, a malformed reply with no `OrgnlMsgId`, and an empty reply.

## Image

```bash
./gradlew bootBuildImage
kind load docker-image --name dcre-dev dcre-psx:2.0
```

AGT launches PSX as an ephemeral K8s Job in the `dcre-pay` namespace whenever an `_SBSR` reply lands
in a per-client `fint-resp-pay/in` directory, resolving the image from its `AGT_PSX_IMAGE` env.
JobParameters arrive as program args; `JOB_NAME` is set in the Job env. Releases are digits-only
3-component SemVer tags, uniform across the fleet.
