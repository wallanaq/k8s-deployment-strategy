# k8s-deployment-strategy

## Helm chart

The `base-webapp` Helm chart used to deploy `qrcode-api` (and any future app pairing
with it) is maintained externally, in its own repo: **`helm-chart-base`**
(<!-- TODO: fill in the repo URL once it's pushed to Forgejo/GitHub -->). It used to live
here at `charts/base-webapp/`; that directory is gone as of the extraction, and
`package-chart`/`publish-chart`/`verify-chart-published`/`harbor-chart-project` moved to
that repo's own Makefile along with it.

This only changes where the chart's *source* is maintained, not how it's *consumed* --
`qrcode-api`'s Argo CD Application (in `k8s-deployment-strategy-gitops`) already pulled it
as a versioned OCI artifact from Harbor (`oci://harbor.k8s.orb.local/charts/base-webapp`),
not by file path, so nothing downstream needed to change. This monorepo's own local
smoke-test target (`make qrcode-api`) now does the same -- pulls a pinned version from
Harbor -- rather than pointing at a local chart directory that no longer exists.
