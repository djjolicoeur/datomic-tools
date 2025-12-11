# datomic-tools

Utilities for managing Datomic dev environments and coordinated schema, fact, and job migrations.

The library is built around two namespaces:

- `datomic-tools.component` – Component definitions for standing up transient (in-memory) Datomic instances during development or connecting to an existing peer.
- `datomic-tools.migrate` – Helpers for installing the `datomic-tools` schema, enumerating migration files from the classpath, and applying them in ID order with idempotency guarantees.

## Getting Started

1. **Add the dependency** – include this project in your `project.clj` / deps.edn, then require the namespaces you need.
2. **Define a system** – `datomic-tools.component/new-dev-datomic` gives you a Component that provisions a throw-away `datomic:mem://` database per run, perfect for tests and local REPL work.
3. **Create migration directories** – place migration files on the classpath under e.g. `resources/datomic-tools/migrations/{schema|facts|jobs}`.
4. **Run migrations at startup** – call `datomic-tools.migrate/migrate!` with a connection and the classpath roots for schema, fact, and job migrations.

```clojure
(require '[datomic-tools.migrate :as migrate]
         '[datomic-tools.component :as db])

(defn migrate-at-startup [conn]
  (migrate/migrate! conn
                    "datomic-tools/migrations/schema"
                    "datomic-tools/migrations/facts"
                    "datomic-tools/migrations/jobs"))
```

`migrate!` automatically installs the supporting schema defined in `resources/migrations.edn` (migration tracking attributes) the first time it runs against a database.

## Migration Files

Each migration file name begins with a sortable numeric identifier: `YYYYMMDDHHMMSSxx-<label>-<type>.edn`. The numeric prefix is parsed and used to order work across schema, fact, and job migrations.

### Schema (`.edn`)
- Stored under e.g. `resources/datomic-tools/migrations/schema`.
- Contain raw Datomic schema transactions.
- Applied exactly once; reruns are skipped if the ID has already been recorded in the database.

### Fact (`.edn`)
- Stored under something like `resources/datomic-tools/migrations/facts`.
- Contain data seeding transactions.
- Go through the same tracking/skip logic as schema migrations.

### Job (`.clj` / `.edn`)
- Stored under `resources/datomic-tools/migrations/jobs`.
- Loaded as code; the file must call `datomic-tools.migrate/bind-tx-fn!` with a function that accepts a DB value and returns tx-data.
- Helpful for complex data backfills that require queries or branching logic.

For each migration type you can supply one or more directories to `migrate!`. The helper `datomic-tools.migrate/file-map` merges them and guards against duplicate IDs so you get a deterministic plan.

## Generating Migration Stubs

Use `datomic-tools.migrate/generate-migration-files!` to create timestamped files in all the configured directories:

```clojure
(require '[datomic-tools.migrate :as migrate]
         '[system :as system])

(migrate/generate-migration-files!
  system/dev-file-helper-config
  "add-user-status"
  :schema :prod-facts :prod-jobs)
```

The helper writes comment headers plus a job namespace template when needed, so you can focus on the tx-data.

## Chunking Large Transactions

Very large migration files can hit Datomic’s transaction size limits. Pass an optional opts map with `:chunk-size` to `migrate!` (and the lower-level helpers) to split tx-data into batches while keeping tracking/rollback logic intact.

```clojure
(migrate/migrate! conn
                  "datomic-tools/migrations/schema"
                  "datomic-tools/migrations/facts"
                  "datomic-tools/migrations/jobs"
                  {:chunk-size {migrate/SCHEMA-MIGRATION 500
                                migrate/SEED-FACT-MIGRATION 1000
                                :default 250}})
```

` :chunk-size` accepts either a single positive integer (applied to every migration type) or a map keyed by `migrate/SCHEMA-MIGRATION`, `migrate/SEED-FACT-MIGRATION`, `migrate/JOB-MIGRATION`, or convenience keys like `:schema`, `:facts`, `:jobs`. If any chunk fails, previously committed chunks are rolled back automatically before the migration metadata is retracted, so your database returns to the pre-migration state.

Keep these values in configuration so different environments can tune their thresholds. `dev/system.clj` exposes `system/default-migrate-opts` (see `dev/system.clj:10`) and threads it through `system/basic-dev-migration-config`. From an application component you can keep the paths and opts together:

```clojure
(let [{:keys [schema prod-facts prod-jobs migrate-opts]}
      system/basic-dev-migration-config]
  (migrate/migrate! conn schema prod-facts prod-jobs migrate-opts))
```

Override `:chunk-size` per environment (e.g. set larger chunks in tests, smaller ones in prod) by assoc'ing into `migrate-opts` before passing them along.

### Component Integration

If you are using `com.stuartsierra.component`, hook migrations into your startup stack with `datomic-tools.component/new-migration-runner`. Give it the same config map described above and depend on your Datomic component so migrations execute before the rest of the system starts:

```clojure
(require '[com.stuartsierra.component :as component]
         '[datomic-tools.component :as components]
         '[system :as system])

(component/system-map
  :datomic-uri "datomic:mem://my-app"
  :db (component/using (components/new-dev-datomic) [:datomic-uri])
  :migrations (component/using
                (components/new-migration-runner system/basic-dev-migration-config)
                [:db]))
```

`components/new-migration-runner` accepts overrides, so production can swap in different paths or chunk settings without forking your wiring.

### Environment Defaults

The repository ships with recommended chunk sizes (see `system/env->migrate-opts`) so every environment has sensible defaults:

| Env  | Chunk Config                                      |
|------|---------------------------------------------------|
| dev  | `{:schema 500 :facts 1000 :jobs 250}`             |
| test | `{:schema 250 :facts 500 :jobs 125}`              |
| prod | `nil` (runs migrations as single full-size txs)   |

Override these maps as needed—e.g. set `:chunk-size nil` for a specific migration when you need Datomic to treat it as one logical transaction.

## Rolling Back

When a migration fails you often want to undo the datoms that were applied before the exception. `datomic-tools.migrate/rollback-tx` accepts a connection and a transaction entity ID (the value recorded under `:datomic-tools.*.tx-id`) and automatically retracts/replays the datoms for that transaction.

- **Local/dev usage** – call `rollback-tx` from the REPL after grabbing the tx entity recorded on the migration entity. Chunked migrations already call the same logic internally, so you can safely re-run a migration file after fixing data.
- **Production usage** – prefer “forward” fix migrations so the history remains auditable. Use `rollback-tx` only for emergency situations (e.g. partial backfill during a deployment) and immediately follow it with a new migration that encodes the intended change.
- **Auditing** – every migration entity stores both its logical ID and the Datomic tx entity ID. Capture those IDs in logs/metrics so you can reference the exact transaction later if you need to roll it back.

## Development & Testing

- `dev/system.clj` declares a tiny Component system used by the tests in `test/datomic_tools`.
- Run `lein test` (or your preferred REPL flow) to exercise the migration helpers against an in-memory Datomic peer.
- Sample migrations live under `resources/datomic-tools/testmigrations`; feel free to copy that layout when bootstrapping a new service.

## Publishing Snapshots

Merges to `main` automatically publish the current `-SNAPSHOT` version to Clojars via GitHub Actions (`.github/workflows/publish-snapshot.yml`). Add the secrets `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` to the repository for the deploy to succeed. The workflow installs Leiningen, runs `lein test`, writes credentials to `~/.lein/credentials.clj`, and runs `lein deploy clojars`.

## License

Copyright © 2023 Dan Jolicoeur

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
