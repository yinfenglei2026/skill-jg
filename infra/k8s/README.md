# K3s PoC Manifests

Apply the base only after external PostgreSQL, Keycloak, Gitea, Harbor, MinIO, Vault, a Model Gateway and a `gvisor` RuntimeClass exist. Image references are illustrative immutable digests; the release controller replaces them only after validating the corresponding control-plane approval.

Run `kubectl kustomize infra/k8s/base` before applying changes. The manifest set demonstrates namespace isolation, default-deny networking, least-privilege service accounts, immutable image references and bounded runtime resources. It is not a production topology.
