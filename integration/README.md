# kclip integration tests

This directory holds integration fixtures and scripts that exercise behavior
outside pure Kotlin unit tests.

The first target is the OpenSSH transport spike described in
`docs/design-v1.md` Phase 0.5. Scripts in `openssh/scripts/` must be
re-runnable and must not depend on production Kotlin code.
