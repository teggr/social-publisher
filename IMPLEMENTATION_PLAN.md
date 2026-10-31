# Milestone 1 implementation plan

## Goal and scope

Build a small, local-first Java toolkit for drafting, approving, previewing, and
publishing social posts. GitHub Actions can invoke the CLI, but orchestration is
not part of this milestone. There is no database, web application, or consuming
site integration.

## Architecture

- **Java 17 and Maven:** Keep the application and tests in conventional Maven
  source trees. Supply the Maven Wrapper so local and automated builds use
  `./mvnw`. Keep the command-line entry point usable from JBang.
- **Domain:** A post has a stable ID, text, timestamps, approval metadata, and
  an independent publishing result for each platform. Drafts must be approved
  before a real publish attempt. Editing an approved post returns it to draft
  status and clears its approval.
- **Persistence and configuration:** Store one YAML file per post in a
  configurable local data directory. Write updates atomically and serialize
  state transitions with a per-draft file lock. Read configuration from an
  optional YAML file; read Bluesky credentials only from environment variables.
  Never persist credentials.
- **CLI and generation:** Provide commands for draft creation, generation,
  editing, approval, preview, and publishing. Put generated content behind a
  provider-neutral interface; ship a deterministic local starter generator,
  not a model-provider integration.
- **Publishing:** Define a platform adapter interface and implement Bluesky
  using its session and record-creation XRPC APIs. Keep LinkedIn, X, and
  Mastodon as independent future adapters. Dry-run previews do not make network
  requests or claim that a post was published.
- **Failure safety:** Save an in-progress state before calling a platform so
  concurrent/repeated CLI calls cannot publish twice. Mark an ambiguous
  network outcome as unknown and block automatic retries; operators must
  reconcile an unknown result with the platform before any further attempt.
  Record outcomes separately per platform. Never retry a published result.
- **Quality and documentation:** Test domain rules, YAML round-tripping,
  publishing guards, dry-run behavior, and the CLI vertical slice. Document
  setup, commands, secrets, development, and known limitations. Add a Copilot
  skill describing safe toolkit use.

## Milestone 1 sequence

1. Establish Maven/JBang project scaffolding, configuration, and YAML
   persistence.
2. Implement the post, approval, and per-platform result domain.
3. Implement the CLI and provider-neutral generation abstraction.
4. Implement dry-run publishing and the Bluesky adapter with duplicate
   prevention and safe ambiguous-failure handling.
5. Add unit and integration coverage, the agent skill, and user documentation.
6. Run formatting and Maven checks; scan changed files for secrets and run
   CodeQL.

## Deliberate limitations

- No AI provider is bundled; generation is a replaceable interface with a
  local starter implementation.
- Bluesky is the only live adapter in this milestone. Other adapters are
  extension points, not working integrations.
- Unknown publication outcomes require manual platform reconciliation; this
  release does not provide an automated reconciliation or retry command.
- Local YAML files are intended for a single trusted workspace. They do not
  provide multi-user access control, distributed coordination, or encryption
  at rest.
