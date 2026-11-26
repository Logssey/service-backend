# Re:Used isolated demonstration

Do not merge this branch into develop/main. Existing production Dockerfile, deployment manifests and business logic are unchanged. All demo endpoints require the `attack-lab` profile, an explicit enabled flag, the exact lab namespace and a temporary token of at least 32 characters.

## What this demonstrates

1. Existing IaC gate rejects an external data source before plan or AWS credentials.
2. Trivy detects CVE-2025-14813 in the existing Bouncy Castle 1.81 dependency. The unmodified baseline has no path to the GOST sink; this branch adds an authenticated, benign 16-byte call for a positive Reach control. No exploit or reused key/IV is involved.
3. A separate endpoint runs the fixed command `/bin/sh -c /usr/bin/id`. Falco detects Java spawning a shell and Logssey records the pod/workload and process context.

These are separate control demonstrations. The crypto probe does **not** cause the shell execution. Do not describe this as a Spring Security exploit, successful compromise, or a single causal attack chain. Current Spring-specific rule coverage must be developed and verified separately before that story is used.

## Isolation

- Dedicated ECR repository `logssey/attack-lab/reused-api`; deployment by immutable digest.
- Dedicated namespace `reused-attack-lab` in the existing cluster, not a separate cluster.
- Separate ephemeral PostgreSQL/Redis; no production database or credential copies.
- No public Ingress or LoadBalancer. Default-deny network policies allow runner → API, API → lab DB/Redis and cluster DNS only.
- No service account token mounts, host mounts or privileged containers; non-root process.
- Request body cannot supply a command. Incorrect/missing token returns 403; nonempty body returns 400. Runtime probe permits at most 10 runs per process and one per 5 seconds.
- CoreDNS IP `10.96.0.3` is specific to this cluster; change it in render.py for another cluster.

## Repeat a prepared demonstration

From this directory, with the authorized AWS profile and control-plane SSM access:

```powershell
python lab.py status --instance i-08698ab3a5415d8fb
python lab.py collect --instance i-08698ab3a5415d8fb
python lab.py normal --instance i-08698ab3a5415d8fb
python lab.py unauthorized --instance i-08698ab3a5415d8fb
python lab.py reject-body --instance i-08698ab3a5415d8fb
python lab.py crypto --instance i-08698ab3a5415d8fb
python lab.py runtime --instance i-08698ab3a5415d8fb
```

In Logssey, inspect scan 68 and finding 2176, then the running workload `reused-api-fixture`. L1 shows `RUNNING` and `scanMatch=MATCH` by digest. Filter runtime findings to namespace `reused-attack-lab`, rule `RUN-002`. Repeated execution can increase an existing finding's occurrence count; do not expect a fresh card every time. `restart` resets the probe limit and creates a new pod, but use it only between recordings, followed by `collect`.

The dedicated `reach/reach-l1-demo-collector` also runs every five minutes. `collect` runs it immediately and waits for success. It shares the existing collector service account but has only pod-list permission in the lab namespace, not access to lab Secrets. Production collection remains on its original schedule.

## Build and render

```powershell
./gradlew.bat --no-daemon test --tests 'com.reused.attacklab.*' bootJar
$demoCommit = git rev-parse HEAD
docker build --provenance=false -f deploy/attack-lab/Dockerfile --build-arg SOURCE_COMMIT=$demoCommit -t logssey-attack-lab:demo .
python deploy/attack-lab/render.py <dedicated-image-repository@sha256:digest>
```

Use a single-platform manifest (`--provenance=false`) so the deployed imageID and scan digest refer to the same manifest. Generate `lab-credentials` within the lab namespace: `token`, `jwt`, `db-password`. Never commit generated values. Rendered objects may be applied only to the guarded lab namespace. Deployment preparation and verified IDs are recorded in the accompanying verification report.

## Cleanup after recording

First remove `reach/l1/demo-collector-cronjob.yaml` and `reach/l1/demo-collector-rbac.yaml` from the GitOps main branch through review. Wait for Argo `reach-l1` to sync and prune those demo resources; preserve `reach-l1-collector`. Then run:

```powershell
python lab.py cleanup --instance i-08698ab3a5415d8fb
```

This removes only the labelled lab namespace and its ephemeral data. Cleanup refuses while the GitOps demo collector or lab RoleBinding still exists, to prevent automatic recreation conflicts. Afterwards remove the lab AppProject destination (apply that project file manually) and lab namespace from the dashboard L1 allowlist, restarting only the dashboard API. Close the demo IaC PR without merging. ECR images, Git branches and recorded dashboard findings remain as evidence and have separate retention decisions; cleanup does not silently delete them.

## Actual coverage recording

`l3_local.py` uses the existing dashboard analyzer's recorder and matcher. It runs the same local image in an internal Docker network with disposable DB/Redis, records startup and each request separately using checksum-verified JaCoCo 0.8.13, then removes its containers and network. It does not expose host ports or reuse production credentials. Java 21, Python, Docker, and a checkout of dashboard-backend are required.

```powershell
python deploy/attack-lab/l3_local.py --dashboard-backend <dashboard-backend-path> --evidence-dir <directory-containing-sbom-and-reach-result> --image-info <image.json>
```

The verified recording scope was `com.reused.*:org.bouncycastle.*`; other packages without recordings must not be described as verified. A recorded function means it executed, not that exploitation conditions were met.

## Verified run: 2026-11-26

- IaC PR: https://github.com/Logssey/infrastructure/pull/83 (closed without merging), dashboard run 17.
- Image check/scan 68, finding 2176: CVE-2025-14813, `REACHABLE`, L3 `REGION`, only request segment `002`. Image check policy `GATE` returned `FAIL` because of one reachable critical/high finding. This was an actual local analysis uploaded through the existing result API, not an automatic service PR workflow.
- Runtime finding `RTF-038208`, rule `RUN-002`: actual Falco event, parent Java and child BusyBox shell, lab pod/workload, two occurrences.
- Image revision `1429ca5c161a4db6548752f3c02eea3822660a48`; manifest digest `sha256:13e9da354e4a29fc8038a3f0ac86765bb655fc57f9e74efde690d63c6f5c82b3`.
- Six guard tests passed. Live requests: normal 200, no token 403, nonempty body 400, crypto/runtime probes 200. Lab-runner connections to IMDS, internet and production API were blocked.

### L1 and runtime integration verified later on the same day

- GitOps PR #14 merged: https://github.com/Logssey/gitops/pull/14. Added separate demo L1 collection and the native Falco `container.image.digest` field in custom rule outputs. Existing rule conditions and production service deployments were unchanged. Both Argo applications are Synced/Healthy, with all three Falco sensors ready.
- Infrastructure PR #84 merged: https://github.com/Logssey/infrastructure/pull/84. Dashboard L1 now allows `reused,reused-attack-lab`. Only the dashboard API was restarted with the same image; no Re:Used application rollout was performed. API settings backup: `/opt/logssey/backups/demo-l1-20261126T123326Z`.
- Real service-account collection records `source=CLUSTER_READER`; lab workload is `RUNNING`, matches scan 68 by digest, and finding 2176 is now `RUNNING`.
- A fresh runtime probe produced `RTF-040225`, with image digest equal to the scanned image, recorded L3 build, and actual lab container imageID. No event was manually inserted.
- L1 calls the physical cluster `reused-prod`; runtime calls it `logssey-prod`. These are existing logical aliases. Correlation verified by namespace, workload, Pod and identical digest; no cross-screen navigation feature was added.

Remaining limits: runtime `evaluationStatus` is still `PENDING_CONTEXT` and final severity is not assigned. Matching the image digest does not itself complete runtime risk evaluation. The native sensor's image display string still includes its reported tag; the separately displayed `imageDigest` is the verified registry manifest digest. L3 evidence was recorded earlier on the same image in an isolated local container; running `crypto` in Kubernetes does not automatically record/upload a new L3 session. No automatic full service PR pipeline, Spring exploit, or crypto-to-shell causal attack chain has been verified.
