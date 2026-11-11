# Maintenance preview (non-production)

Repository: Merylcaraudio-manager-Mobile-
Technology: Android

## Objective
Prepare a reversible, opt-in maintenance mode on this isolated branch.

## Implementation plan
Plan a reversible maintenance activity with explicit opt-in.

## Safety constraints
- Do not corrupt or delete application source files, data, credentials, or history.
- Do not merge to main or deploy to production automatically.
- Keep the default behavior unchanged until a separate reviewed release.
- Verify builds and provide rollback instructions before any rollout.

## Status
Planning document only. No executable application behavior has changed.
