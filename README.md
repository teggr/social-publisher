# social-publisher

A local-first Java/JBang toolkit for drafting, approving, previewing, and
publishing social posts. Drafts and results are stored as YAML files; no
database, web application, or consuming-site integration is included.

## Requirements

- Java 17 or newer for Maven builds (JDK 17 or 21 is tested in CI; Spotless'
  google-java-format 1.24 fails on very new JDKs such as 27, so run
  `./mvnw verify` on 17 or 21)
- [JBang](https://www.jbang.dev/) to run the CLI. JBang downloads JDK 17 and
  the Jackson YAML, jsoup and Spring AI (OpenAI) dependencies (declared in `social-publisher.java`) itself, so
  no prior Java or Maven setup is needed. `scripts/jbang-smoke.sh` verifies
  this and runs in CI.
- Git for source checkout

The Maven Wrapper is included for build and test commands; a separate Maven
installation is not required.

## Quick start

```shell
# Create a draft from a prompt using the bundled starter generator.
jbang social-publisher.java generate --topic "building reliable software"

# Or write your own post directly.
jbang social-publisher.java draft create --text "A short post to review."

# Generate LinkedIn, Bluesky, X and Mastodon drafts from an article (needs OPENAI_API_KEY).
jbang social-publisher.java generate --url https://robintegg.com/2026/10/29/example-article \
  --profile personal-technical-blog

# Edit the draft, approve it, then inspect the exact text before publishing.
jbang social-publisher.java draft edit <draft-id> --text "The final reviewed post."
jbang social-publisher.java approve <draft-id> --by "reviewer"
jbang social-publisher.java preview <draft-id>

# Exercise the publishing path without making a network request.
jbang social-publisher.java publish <draft-id> --platform BLUESKY --dry-run

# Publish for real only after configuring credentials and approving the draft.
jbang social-publisher.java publish <draft-id> --platform BLUESKY
```

The CLI prints a generated draft ID. Use that ID in subsequent commands.
`draft edit` clears approval, so an edited post must be approved again.
`publish --dry-run` also requires approval, displays the post, and leaves
publication state unchanged.

Other useful commands:

```shell
jbang social-publisher.java list
jbang social-publisher.java --help
```

## Configuration

Copy [`social-publisher.example.yml`](social-publisher.example.yml) to
`social-publisher.yml` and adjust the local data directory or Bluesky service if
needed. By default, the CLI reads `social-publisher.yml` from the current
directory. Set `SOCIAL_PUBLISHER_CONFIG` or pass `--config <path>` to use a
different file.

```yaml
dataDirectory: .social-publisher
blueskyService: https://bsky.social
blueskyIdentifierEnv: BLUESKY_IDENTIFIER
blueskyAppPasswordEnv: BLUESKY_APP_PASSWORD
```

Drafts are kept in `<dataDirectory>/drafts/` as individual YAML files. Writes
are atomic and use per-draft file locks. Local data is not encrypted and is
intended for a trusted workspace.

## AI article generation

`generate --url <article-url>` fetches the article, extracts its metadata and
main text, and asks OpenAI for four separate posts (LinkedIn, Bluesky, X and
Mastodon), each with its own editorial instructions, structured JSON output and
length validation (Bluesky 300 graphemes / 3000 bytes, X 280 weighted
characters, Mastodon `mastodonCharacterLimit`, LinkedIn 3000; links count as 23
on X and Mastodon). Invalid output is retried once, then the command fails and
saves nothing.

```shell
export OPENAI_API_KEY="sk-..."   # the variable name is configurable
jbang social-publisher.java generate --url https://robintegg.com/2026/10/29/example-article \
  --profile personal-technical-blog --model gpt-4o-mini
jbang social-publisher.java generate --url <url> --preview      # print only, save nothing
jbang social-publisher.java generate --url <url> --data-dir ./my-drafts
```

Options: `--profile <name>`, `--model <model>`, `--preview`, and the global
`--data-dir <path>`. `--topic` generation still works and needs no API key.

Generated drafts are **never approved or published**. Each platform's text is
edited separately and editing clears approval:

```shell
jbang social-publisher.java preview <draft-id>
jbang social-publisher.java draft edit <draft-id> --platform LINKEDIN --text "Reviewed text with https://..."
jbang social-publisher.java approve <draft-id> --by "reviewer"
```

The draft YAML records the source and canonical URL, title, description,
publication date, Open Graph image URL, generation time, provider/model, the
profile name and each platform's text. The article body and API key are never
stored. Only Bluesky has a publisher; its text is the draft's BLUESKY text.

### Configuration

| Key | Default | Purpose |
| --- | --- | --- |
| `openAiApiKeyEnv` | `OPENAI_API_KEY` | Name of the environment variable holding the key |
| `openAiModel` | `gpt-4o-mini` | Model (override per run with `--model`) |
| `openAiBaseUrl` | `https://api.openai.com` | API base URL (OpenAI-compatible endpoints) |
| `openAiTimeoutSeconds` | `60` | Connect/read timeout, 1-600 |
| `mastodonCharacterLimit` | `500` | Your instance's post limit |
| `profilesDirectory` | `profiles` | Where editorial profiles live |
| `defaultProfile` | none | Profile used when `--profile` is omitted |

### Editorial profiles

A profile is a YAML file at `<profilesDirectory>/<name>.yml` controlling
`siteName`, `websiteUrl`, `tone`, `audience`, `writingStyle`, `avoid` (a list)
and `platformInstructions` (keys `linkedin`, `bluesky`, `x`, `mastodon`). Without
a profile a neutral default is used. Examples:
[`profiles/personal-technical-blog.yml`](profiles/personal-technical-blog.yml) and
[`profiles/java-resource-catalogue.yml`](profiles/java-resource-catalogue.yml).

### Article fetching safety

Only `http`/`https` URLs are fetched. Hosts are resolved and rejected if any
address is loopback, private, link-local, carrier-grade NAT, multicast or
otherwise reserved; every redirect (max 5) is re-validated. Requests have
connect/read timeouts, a 2 MiB size cap and must return HTML. Pages are parsed
statically (no scripts run, no sub-resources fetched) and the text is passed to
the model as untrusted data. Cross-site canonical links are ignored.

## Bluesky credentials

Create an app password in Bluesky account settings. Provide credentials through
environment variables; the toolkit does not save credentials in configuration,
draft files, or logs.

```shell
export BLUESKY_IDENTIFIER="your.handle.example"
export BLUESKY_APP_PASSWORD="your-app-password"
jbang social-publisher.java publish <draft-id> --platform BLUESKY
```

For automation, configure these as GitHub Actions secrets and expose them to
the job environment. Never commit real values in YAML, source files, command
history, or CI configuration.

## Safety and publishing state

- Only approved drafts can enter the publish flow. Editing clears approval.
- Each platform has a separate result and attempt counter.
- Published posts cannot be published again through this CLI.
- A known-safe failure can be retried. An ambiguous request outcome is recorded
  as `UNKNOWN` and blocks automatic retries to avoid duplicate posts.
- A process interrupted after recording `IN_PROGRESS` also blocks a new attempt.
  Check the platform manually, then resolve either state with
  `reconcile <id> --platform BLUESKY --outcome published --remote-id <at-uri> [--url <url>]`
  or `--outcome not-published` (which makes a retry possible). `reconcile`
  never contacts the platform and only accepts `IN_PROGRESS`, `UNKNOWN`, or
  non-retry-safe `FAILED` results.
- Bluesky posts are limited to 300 graphemes; links and hashtags become rich-text
  facets. HTTP 4xx (except 408) is treated as a safe rejection (429 includes
  the retry hint); 5xx, timeouts and transport errors are `UNKNOWN`. Link
  cards and images are not implemented.

## GitHub Actions

- `ci.yml`: `./mvnw verify` (tests + Spotless) on Java 17/21 and the JBang smoke test.
- `codeql.yml`: CodeQL analysis; `dependabot.yml` tracks Maven and Actions updates.
- `publish.yml`: manual dispatch of one approved draft on `main`. Create a
  `publishing` environment holding `BLUESKY_IDENTIFIER`/`BLUESKY_APP_PASSWORD`
  and required reviewers. A repository-wide concurrency group serialises
  publishes, and state is committed to `main` even after failures. Drafts for
  CI live in `social-publisher-data/drafts/` (see `.github/social-publisher-ci.yml`).
- Dry runs make no network request and do not claim a publication result.
- Bootstrap: `social-publisher-data/drafts/` is tracked via `.gitkeep`. Create,
  approve, and commit a draft locally (`dataDirectory: social-publisher-data` in
  `.github/social-publisher-ci.yml`, e.g. via `--config`) and push it to `main`
  before dispatching `publish.yml`.

## Development

```shell
./mvnw test
./mvnw spotless:check
./mvnw verify
```

Java sources and tests use the standard Maven layout. The `social-publisher.java`
JBang launcher loads the same application sources tested by Maven.

## Milestone 1 status and limitations

- **Working:** YAML drafts/configuration, create/edit/approve/preview/list
  commands, a provider-neutral generation interface, a deterministic starter
  generator, dry-run publishing, and live Bluesky session/post creation.
- **Not implemented:** Bluesky link cards/images, LinkedIn, X, Mastodon, scheduling,
  automated retries/reconciliation, multi-user access control, and web or
  consuming-site integrations. Other platforms can be added independently via
  the `PlatformPublisher` interface.
- `generate --topic` is still the simple local template; `generate --url` uses OpenAI.
- AI generation limitations: output is only as accurate as the model, so review
  every post. JavaScript-rendered pages without server-side HTML are not
  supported. DNS is resolved once for validation and again by the HTTP client
  (a DNS-rebinding race is theoretically possible). Image attachments are not
  generated. X and Mastodon length checks use the documented counting rules,
  not live platform validation.
- A returned Bluesky record URI and URL are recorded per platform. Secrets are
  only read from environment variables at publish time.

See [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) for the architecture and
decisions for this milestone.
