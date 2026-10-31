package dev.socialpublisher.cli;

import dev.socialpublisher.config.AppConfig;
import dev.socialpublisher.config.ConfigLoader;
import dev.socialpublisher.generation.PostGenerator;
import dev.socialpublisher.generation.TemplatePostGenerator;
import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.PublishingResult;
import dev.socialpublisher.model.PublishingStatus;
import dev.socialpublisher.model.SocialPost;
import dev.socialpublisher.persistence.YamlDraftRepository;
import dev.socialpublisher.publishing.BlueskyPublisher;
import dev.socialpublisher.publishing.PlatformPublisher;
import dev.socialpublisher.publishing.PublishOutcome;
import dev.socialpublisher.publishing.PublishingService;
import java.io.PrintStream;
import java.io.IOException;
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

  public static int run(
      String[] args, PrintStream out, PrintStream err, Map<String, String> environment) {
    try {
      List<String> arguments = new ArrayList<>(Arrays.asList(args));
      Path configPath = configPath(arguments, environment);
      if (arguments.isEmpty() || arguments.contains("--help") || arguments.contains("-h")) {
        printUsage(out);
        return 0;
      }

      AppConfig config = ConfigLoader.load(configPath);
      Path dataDirectory = Path.of(config.getDataDirectory());
      YamlDraftRepository repository = new YamlDraftRepository(dataDirectory);
      List<PlatformPublisher> publishers =
          List.of(new BlueskyPublisher(config, java.net.http.HttpClient.newHttpClient(), environment));
      PublishingService publishingService = new PublishingService(repository, publishers);
      return execute(arguments, repository, publishingService, new TemplatePostGenerator(), out);
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
      PrintStream out)
      throws IOException {
    String command = args.get(0);
    if ("generate".equals(command)) {
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
        repository.update(
            id,
            post -> {
              boolean hasAttempts =
                  post.getPublishingResults().values().stream()
                      .anyMatch(result -> result.getAttempts() > 0);
              if (hasAttempts) {
                throw new IllegalStateException(
                    "A draft cannot be edited after a publication attempt");
              }
              post.setText(text);
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
      Platform platform = Platform.valueOf(requiredOption(args, "--platform").toUpperCase());
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
    if ("list".equals(command)) {
      for (SocialPost post : repository.findAll()) {
        out.printf(
            "%s  %s  %s%n",
            post.getId(),
            post.isApproved() ? "approved" : "draft",
            oneLine(post.getText()));
      }
      return 0;
    }
    throw new IllegalArgumentException("Unknown command: " + command);
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
    out.println("Text:");
    out.println(post.getText());
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
    String configuredPath = environment.getOrDefault("SOCIAL_PUBLISHER_CONFIG", "social-publisher.yml");
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

  private static String oneLine(String text) {
    return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
  }

  private static void printUsage(PrintStream out) {
    out.println(
        """
        Usage:
          jbang social-publisher.java generate --topic <topic>
          jbang social-publisher.java draft create --text <text>
          jbang social-publisher.java draft edit <id> --text <text>
          jbang social-publisher.java approve <id> [--by <name>]
          jbang social-publisher.java preview <id>
          jbang social-publisher.java publish <id> --platform BLUESKY [--dry-run]
          jbang social-publisher.java list

        Global options:
          --config <path>   YAML configuration file (default: social-publisher.yml)
        """);
  }
}
