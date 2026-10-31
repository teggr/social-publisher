package dev.socialpublisher.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.socialpublisher.model.SocialPost;
import dev.socialpublisher.persistence.YamlDraftRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainIntegrationTest {
  @TempDir Path tempDirectory;

  @Test
  void createsEditsApprovesPreviewsAndDryRunsDraftFromCli() throws Exception {
    Path data = tempDirectory.resolve("data");
    Path config = tempDirectory.resolve("social-publisher.yml");
    Files.writeString(config, "dataDirectory: " + data + System.lineSeparator());
    Map<String, String> environment = Map.of("SOCIAL_PUBLISHER_CONFIG", config.toString());

    String createOutput = invoke(environment, "draft", "create", "--text", "First version").output;
    Matcher matcher = Pattern.compile("Created draft ([0-9a-f-]{36})").matcher(createOutput);
    assertTrue(matcher.find());
    String id = matcher.group(1);

    assertEquals(0, invoke(environment, "draft", "edit", id, "--text", "Final version").exitCode);
    assertEquals(0, invoke(environment, "approve", id, "--by", "reviewer").exitCode);
    String preview = invoke(environment, "preview", id).output;
    assertTrue(preview.contains("Approval: approved"));
    assertTrue(preview.contains("Final version"));

    CliResult dryRun = invoke(environment, "publish", id, "--platform", "BLUESKY", "--dry-run");
    assertEquals(0, dryRun.exitCode);
    assertTrue(dryRun.output.contains("no network request was made"));
    SocialPost post = new YamlDraftRepository(data).findById(id);
    assertTrue(post.getPublishingResults().isEmpty());
  }

  @Test
  void generationCreatesAnUnapprovedDraft() throws Exception {
    Path data = tempDirectory.resolve("generated");
    Path config = tempDirectory.resolve("generated.yml");
    Files.writeString(config, "dataDirectory: " + data + System.lineSeparator());

    CliResult generated =
        invoke(
            Map.of("SOCIAL_PUBLISHER_CONFIG", config.toString()), "generate", "--topic", "testing");

    assertEquals(0, generated.exitCode);
    assertTrue(generated.output.contains("A thought on testing"));
    Matcher matcher = Pattern.compile("Created draft ([0-9a-f-]{36})").matcher(generated.output);
    assertTrue(matcher.find());
    assertFalse(new YamlDraftRepository(data).findById(matcher.group(1)).isApproved());
  }

  private CliResult invoke(Map<String, String> environment, String... args) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    int exitCode = Main.run(args, new PrintStream(output), new PrintStream(error), environment);
    return new CliResult(exitCode, output.toString(), error.toString());
  }

  private record CliResult(int exitCode, String output, String error) {}
}
