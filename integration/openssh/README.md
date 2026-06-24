# OpenSSH transport spike

Phase 0.5 validates the OpenSSH command shapes before they are wrapped in
Kotlin code.

The spike must cover:

- remote Unix-domain socket forwarding
- dedicated private master setup
- `ssh -O forward`
- `ssh -O cancel`
- forwarding rejection diagnostics
- stale socket and permission failures

Evidence from this spike should be copied into this directory or summarized in
this README before Phase 3 transport implementation begins.
