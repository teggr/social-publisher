---
name: social-publisher
description: Safely use the social-publisher CLI to draft, edit, approve, preview, and publish posts.
---

# social-publisher toolkit

Use the repository's JBang CLI for post workflows. Draft data is YAML in the
configured local data directory. Do not edit the persistence files directly
unless the task explicitly asks for data migration or repair.

## Safe workflow

1. Create a draft with `jbang social-publisher.java draft create --text "..."`
   or generate a starter draft with `jbang social-publisher.java generate
   --topic "..."`.
2. Make requested changes with `draft edit <id> --text "..."`. Editing clears
   approval.
3. Request/record approval with `approve <id> --by "<reviewer>"`.
4. Inspect the exact final text and platform statuses using `preview <id>`.
5. Use `publish <id> --platform BLUESKY --dry-run` to demonstrate the path
   without sending a network request.
6. Publish for real only when the user explicitly requests publication and the
   post is approved. Never bypass the CLI approval guard.

## Generating drafts from an article

`jbang social-publisher.java generate --url <article-url> --profile <name>
[--model <model>] [--preview] [--data-dir <dir>]` creates one draft holding
separate LinkedIn, Bluesky, X and Mastodon text, plus article metadata and
generation provenance.

- Requires `OPENAI_API_KEY` (or the variable named by `openAiApiKeyEnv`) in the
  environment. Never write the key into files, commands or output.
- Profiles live in `profiles/<name>.yml`; pick the one matching the site.
- Use `--preview` first when unsure; it saves nothing.
- Generated drafts are always unapproved. Never approve or publish them
  without the user reading each platform's text and explicitly asking.
- Review posts against the article; the model can be wrong. Fix text with
  `draft edit <id> --platform <PLATFORM> --text "..."` (clears approval).
- Only public http(s) URLs are fetched; do not try to bypass URL safety checks.

## Security and outcomes

- Never place access tokens, app passwords, or other credentials in source,
  YAML drafts, configuration, logs, or generated content.
- Live Bluesky credentials must come from the configured environment variable
  names (`BLUESKY_IDENTIFIER` and `BLUESKY_APP_PASSWORD` by default).
- Do not publish unapproved content or claim that a dry run was published.
- Treat `IN_PROGRESS` and `UNKNOWN` as potentially published. Verify the
  platform manually, then use `reconcile` (never edit YAML); automatic retries are
  intentionally blocked to prevent duplicates.
- Publishing state is independent for each platform. Bluesky is the only live
  adapter; LinkedIn, X, and Mastodon are not configured.

Use `jbang social-publisher.java --help` for the current command list. See the
repository README for configuration and development commands.
