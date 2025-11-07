# AGENTS GUIDE

Shared notes for anyone (human or AI) collaborating on `datomic-tools`.

## Core Concepts
- `src/datomic_tools/component.clj` holds the Component records for spinning up in-memory Datomic peers during development or wiring a connection in production.
- `src/datomic_tools/migrate.clj` is the heart of the project: it installs the tracking schema, enumerates migration files in ID order, and applies schema, fact, and job migrations idempotently.
- Test fixtures and sample migrations live under `resources/datomic-tools/testmigrations`; mirror that layout when adding new fixtures.

## Workflow Expectations
- Prefer small, well-described migrations. File names must start with a sortable numeric ID (typically a timestamp). The ID is the source of truth for ordering and for deduplication checks.
- Always run `lein test` before handing work back. The suite spins up isolated in-memory databases, so it is safe (and fast) to execute repeatedly.
- Keep documentation in sync. README should describe any behavior changes; large features should also be noted in this file so the next agent has breadcrumbs.
- Avoid destructive git commands (`reset --hard`, force pushes) unless explicitly requested by the user.

## Developing Features
- Use the Component helpers from `dev/system.clj` or `test/datomic_tools/test_util.clj` to stand up a sandboxed Datomic instance while iterating.
- When adding new migration helpers, consider how they surface through `migrate!`. The function is called from user code at startup, so backwards compatibility and clear optional arguments are important.
- Large schema/fact backfills should go through the `:chunk-size` option (supported by `migrate!` and the lower-level helpers) to avoid hitting Datomic’s 4MB transaction limit. Tests under `datomic-tools.migrate-test` cover both the happy-path and rollback behavior.
- For Component-based services, prefer wiring migrations through `datomic-tools.component/new-migration-runner` so every environment shares the same config (see `system/basic-dev-migration-config` for the default map).
- Tests that depend on migrations should create dedicated resource directories instead of piggybacking on the shared ones to keep fixtures isolated.

## Release Checklist
1. Update README plus any relevant docs explaining new behavior.
2. Ensure migrations remain deterministic: no reliance on file iteration order, always guard against duplicate IDs.
3. Run `lein test`.

## Open Questions / Future Enhancements
- Improve guidance around rollback workflows for production (currently only low-level helpers exist).
- Surface metrics or structured logging around migration progress for easier observability.

If you discover additional conventions or trip hazards, please document them here so the next contributor builds on solid ground.
