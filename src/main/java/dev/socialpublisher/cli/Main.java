package dev.socialpublisher.cli;

import dev.socialpublisher.article.ArticleExtractor;
import dev.socialpublisher.article.HtmlArticleExtractor;
import dev.socialpublisher.config.AppConfig;
import dev.socialpublisher.config.ConfigLoader;
import dev.socialpublisher.generation.ArticleDraftService;
import dev.socialpublisher.generation.EditorialProfile;
import dev.socialpublisher.generation.PostGenerator;
import dev.socialpublisher.generation.ProfileLoader;
import dev.socialpublisher.generation.TemplatePostGenerator;
import dev.socialpublisher.generation.openai.OpenAiPostGenerator;
import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.PlatformDraft;
import dev.socialpublisher.model.PublishingResult;
import dev.socialpublisher.model.PublishingStatus;
import dev.socialpublisher.model.SocialPost;
import dev.socialpublisher.persistence.YamlDraftRepository;
import dev.socialpublisher.publishing.BlueskyPublisher;
import dev.socialpublisher.publishing.PlatformPublisher;
import dev.socialpublisher.publishing.PublishOutcome;
import dev.socialpublisher.publishing.PublishingService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int exitCode = run(args, System.out, System.err, System.getenv());
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /** Creates the AI generator used for article generation; replaceable in tests. */
  @FunctionalInterface
  public interface GeneratorFactory {
    PostGenerator create(AppConfig config, String modelOverride);
  }

  public static int run(
      String[] args, PrintStream out, PrintStream err, Map<String, String> environment) {
    return run(args, out, err, environment, new HtmlArticleExtractor(), null);
  }

  public static int run(
      String[] args,
      PrintStream out,
      PrintStream err,
      Map<String, String> environment,
      ArticleExtractor extractor,
      GeneratorFactory generatorFactory) {
    try {
      List<String> arguments = new ArrayList<>(Arrays.asList(args));
      Path configPath = configPath(arguments, environment);
      String dataDirectoryOverride = extractOption(arguments, "--data-dir");
      if (arguments.isEmpty() || arguments.contains("--help") || arguments.contains("-h")) {
        printUsage(out);
        return 0;
      }

      AppConfig config = ConfigLoader.load(configPath);
      Path dataDirectory =
          Path.of(
              dataDirectoryOverride != null ? dataDirectoryOverride : config.getDataDirectory());
      YamlDraftRepository repository = new YamlDraftRepository(dataDirectory);
      List<PlatformPublisher> publishers =
          List.of(
              new BlueskyPublisher(config, java.net.http.HttpClient.newHttpClient(), environment));
      PublishingService publishingService = new PublishingService(repository, publishers);
      GeneratorFactory factory =
          generatorFactory != null
              ? generatorFactory
              : (cfg, model) -> OpenAiPostGenerator.fromConfig(cfg, environment, model);
      Generation generation = new Generation(config, extractor, factory);
      return execute(
          arguments, repository, publishingService, new TemplatePostGenerator(), generation, out);
    } catch (Exception e) {
      err.println("Error: " + e.getMessage());
      return 2;
    }
  }

  private static int execute(
      List<String> args,
      YamlDraftRepository repository,
      PublishingService publishingService,
      PostGenerator generator,
      Generation generation,
      PrintStream out)
      throws Exception {
    String command = args.get(0);
    if ("generate".equals(command)) {
      String url = option(args, "--url", null);
      if (url != null) {
        if (option(args, "--topic", null) != null) {
          throw new IllegalArgumentException("Use either --url or --topic, not both");
        }
        return generateFromArticle(args, url, repository, generation, out);
      }
      String text = generator.generate(requiredOption(args, "--topic"));
      SocialPost post = createPost(text);
      repository.create(post);
      out.println("Created draft " + post.getId());
      out.println(post.getText());
      return 0;
    }
    if ("draft".equals(command)) {
      if (args.size() < 2) {
        throw new IllegalArgumentException("Expected draft create or draft edit");
      }
      if ("create".equals(args.get(1))) {
        String text = requiredOption(args, "--text");
        SocialPost post = createPost(text);
        repository.create(post);
        out.println("Created draft " + post.getId());
        return 0;
      }
      if ("edit".equals(args.get(1))) {
        if (args.size() < 3) {
          throw new IllegalArgumentException("Expected a draft ID");
        }
        String id = args.get(2);
        String text = requiredOption(args, "--text");
        if (text.isBlank()) {
          throw new IllegalArgumentException("Draft text must not be empty");
        }
        String platformOption = option(args, "--platform", null);
        Platform editPlatform = platformOption == null ? null : parsePlatform(platformOption);
        repository.update(
            id,
            post -> {
              if (post.getPlatformDrafts().isEmpty() && editPlatform != null) {
                throw new IllegalArgumentException(
                    "--platform applies only to generated multi-platform drafts");
              }
              if (!post.getPlatformDrafts().isEmpty() && editPlatform == null) {
                throw new IllegalArgumentException(
                    "This draft has per-platform text; choose one with --platform "
                        + post.getPlatformDrafts().keySet());
              }
              if (editPlatform != null && !post.getPlatformDrafts().containsKey(editPlatform)) {
                throw new IllegalArgumentException("Draft has no " + editPlatform + " text");
              }
              boolean hasAttempts =
                  post.getPublishingResults().values().stream()
                      .anyMatch(result -> result.getAttempts() > 0);
              if (hasAttempts) {
                throw new IllegalStateException(
                    "A draft cannot be edited after a publication attempt");
              }
              if (editPlatform != null) {
                post.getPlatformDrafts()
                    .put(editPlatform, new PlatformDraft(text, Instant.now().toString()));
              } else {
                post.setText(text);
              }
              post.setApproved(false);
              post.setApprovedBy(null);
              post.setApprovedAt(null);
              post.setUpdatedAt(Instant.now().toString());
            });
        out.println("Updated draft " + id + "; approval was cleared");
        return 0;
      }
      throw new IllegalArgumentException("Expected draft create or draft edit");
    }
    if ("approve".equals(command)) {
      if (args.size() < 2) {
        throw new IllegalArgumentException("Expected a draft ID");
      }
      String id = args.get(1);
      String approvedBy = option(args, "--by", "local user");
      repository.update(
          id,
          post -> {
            if (post.getText() == null || post.getText().isBlank()) {
              throw new IllegalStateException("Cannot approve an empty draft");
            }
            post.setApproved(true);
            post.setApprovedBy(approvedBy);
            post.setApprovedAt(Instant.now().toString());
            post.setUpdatedAt(Instant.now().toString());
          });
      out.println("Approved draft " + id + " by " + approvedBy);
      return 0;
    }
    if ("preview".equals(command)) {
      if (args.size() < 2) {
        throw new IllegalArgumentException("Expected a draft ID");
      }
      printPreview(repository.findById(args.get(1)), out);
      return 0;
    }
    if ("publish".equals(command)) {
      if (args.size() < 2) {
        throw new IllegalArgumentException("Expected a draft ID");
      }
      Platform platform = parsePlatform(requiredOption(args, "--platform"));
      boolean dryRun = args.contains("--dry-run");
      PublishOutcome outcome = publishingService.publish(args.get(1), platform, dryRun);
      if (outcome.dryRun()) {
        out.println("Dry run only; no network request was made.");
        out.println(outcome.preview());
      } else {
        out.println("Published to " + platform);
        out.println("Remote ID: " + outcome.result().getRemoteId());
        if (outcome.result().getUrl() != null) {
          out.println("URL: " + outcome.result().getUrl());
        }
      }
      return 0;
    }
    if ("reconcile".equals(command)) {
      if (args.size() < 2) {
        throw new IllegalArgumentException("Expected a draft ID");
      }
      Platform platform = parsePlatform(requiredOption(args, "--platform"));
      String outcome = requiredOption(args, "--outcome");
      boolean published;
      if ("published".equals(outcome)) {
        published = true;
      } else if ("not-published".equals(outcome)) {
        published = false;
      } else {
        throw new IllegalArgumentException("--outcome must be published or not-published");
      }
      PublishingResult result =
          publishingService.reconcile(
              args.get(1),
              platform,
              published,
              option(args, "--remote-id", null),
              option(args, "--url", null));
      out.println("Reconciled " + platform + " as " + result.getStatus());
      return 0;
    }
    if ("list".equals(command)) {
      for (SocialPost post : repository.findAll()) {
        out.printf(
            "%s  %s  %s%n",
            post.getId(), post.isApproved() ? "approved" : "draft", oneLine(post.getText()));
      }
      return 0;
    }
    throw new IllegalArgumentException("Unknown command: " + command);
  }

  private record Generation(
      AppConfig config, ArticleExtractor extractor, GeneratorFactory generatorFactory) {}

  private static int generateFromArticle(
      List<String> args,
      String url,
      YamlDraftRepository repository,
      Generation generation,
      PrintStream out)
      throws Exception {
    AppConfig config = generation.config();
    String profileName = option(args, "--profile", config.getDefaultProfile());
    EditorialProfile profile =
        profileName == null
            ? EditorialProfile.defaultProfile()
            : ProfileLoader.load(Path.of(config.getProfilesDirectory()), profileName);
    PostGenerator generator =
        generation.generatorFactory().create(config, option(args, "--model", null));
    ArticleDraftService service =
        new ArticleDraftService(
            generation.extractor(), generator, config.getMastodonCharacterLimit());
    SocialPost post = service.generate(url, profile);
    boolean preview = args.contains("--preview");
    if (preview) {
      out.println("Preview only; nothing was saved.");
    } else {
      repository.create(post);
      out.println("Created draft " + post.getId());
    }
    printPreview(post, out);
    if (!preview) {
      out.println(
          "Not approved. Review with 'preview "
              + post.getId()
              + "', edit with 'draft edit "
              + post.getId()
              + " --platform <PLATFORM> --text <text>', then approve explicitly.");
    }
    return 0;
  }

  private static Platform parsePlatform(String value) {
    try {
      return Platform.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown platform: " + value);
    }
  }

  private static SocialPost createPost(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Draft text must not be empty");
    }
    return new SocialPost(UUID.randomUUID().toString(), text, Instant.now().toString());
  }

  private static void printPreview(SocialPost post, PrintStream out) {
    out.println("Draft: " + post.getId());
    out.println("Approval: " + (post.isApproved() ? "approved" : "not approved"));
    if (post.getArticle() != null) {
      out.println("Article: " + post.getArticle().getTitle());
      out.println("Source: " + post.getArticle().getSourceUrl());
      out.println("Canonical: " + post.getArticle().getCanonicalUrl());
    }
    if (post.getGeneration() != null) {
      out.println(
          "Generated: "
              + post.getGeneration().getGeneratedAt()
              + " by "
              + post.getGeneration().getProvider()
              + "/"
              + post.getGeneration().getModel()
              + " (profile: "
              + post.getGeneration().getProfile()
              + ")");
    }
    if (post.getPlatformDrafts().isEmpty()) {
      out.println("Text:");
      out.println(post.getText());
    } else {
      for (Map.Entry<Platform, PlatformDraft> entry : post.getPlatformDrafts().entrySet()) {
        out.println();
        out.println("[" + entry.getKey() + "]");
        out.println(entry.getValue().getText());
      }
      out.println();
    }
    for (Map.Entry<Platform, PublishingResult> entry : post.getPublishingResults().entrySet()) {
      PublishingResult result = entry.getValue();
      PublishingStatus status =
          result.getStatus() == null ? PublishingStatus.NOT_PUBLISHED : result.getStatus();
      out.println(entry.getKey() + ": " + status);
      if (result.getUrl() != null) {
        out.println("  " + result.getUrl());
      }
    }
  }

  private static String option(List<String> args, String name, String defaultValue) {
    for (int i = 0; i < args.size(); i++) {
      String value = args.get(i);
      if (value.startsWith(name + "=")) {
        return value.substring(name.length() + 1);
      }
      if (value.equals(name) && i + 1 < args.size()) {
        return args.get(i + 1);
      }
    }
    return defaultValue;
  }

  private static String requiredOption(List<String> args, String name) {
    String value = option(args, name, null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required option " + name);
    }
    return value;
  }

  private static Path configPath(List<String> args, Map<String, String> environment) {
    String configuredPath =
        environment.getOrDefault("SOCIAL_PUBLISHER_CONFIG", "social-publisher.yml");
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.startsWith("--config=")) {
        configuredPath = arg.substring("--config=".length());
        args.remove(i);
        break;
      }
      if ("--config".equals(arg)) {
        if (i + 1 >= args.size()) {
          throw new IllegalArgumentException("Missing path after --config");
        }
        configuredPath = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        break;
      }
    }
    return Path.of(configuredPath);
  }

  /** Removes a global {@code --name value} or {@code --name=value} option from the arguments. */
  private static String extractOption(List<String> args, String name) {
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.startsWith(name + "=")) {
        args.remove(i);
        return arg.substring(name.length() + 1);
      }
      if (arg.equals(name)) {
        if (i + 1 >= args.size()) {
          throw new IllegalArgumentException("Missing value after " + name);
        }
        String value = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return value;
      }
    }
    return null;
  }

  private static String oneLine(String text) {
    return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
  }

  private static void printUsage(PrintStream out) {
    out.println(
        """
        Usage:
          jbang social-publisher.java generate --topic <topic>
          jbang social-publisher.java generate --url <article-url> [--profile <name>]
              [--model <model>] [--preview]
          jbang social-publisher.java draft create --text <text>
          jbang social-publisher.java draft edit <id> --text <text> [--platform <platform>]
          jbang social-publisher.java approve <id> [--by <name>]
          jbang social-publisher.java preview <id>
          jbang social-publisher.java publish <id> --platform BLUESKY [--dry-run]
          jbang social-publisher.java reconcile <id> --platform BLUESKY
              --outcome published|not-published [--remote-id <id>] [--url <url>]
          jbang social-publisher.java list

        Global options:
          --config <path>     YAML configuration file (default: social-publisher.yml)
          --data-dir <path>   Draft data directory, overriding dataDirectory in the config

        Article generation reads the OpenAI key from OPENAI_API_KEY (name configurable).
        It only creates unapproved drafts; --preview prints them without saving.
        """);
  }
}
