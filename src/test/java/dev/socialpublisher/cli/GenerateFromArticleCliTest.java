package dev.socialpublisher.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.socialpublisher.article.ArticleExtractionException;
import dev.socialpublisher.article.ArticleExtractor;
import dev.socialpublisher.article.ExtractedArticle;
import dev.socialpublisher.generation.PostGenerator;
import dev.socialpublisher.generation.StructuredPrompt;
import dev.socialpublisher.model.ArticleMetadata;
import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.SocialPost;
import dev.socialpublisher.persistence.YamlDraftRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateFromArticleCliTest {
  private static final String URL = "https://example.com/2026/10/29/post";
  @TempDir Path temp;

  private final ArticleExtractor extractor =
      url -> {
        if (url.contains("broken")) {
          throw new ArticleExtractionException("Refusing to fetch a local address: localhost");
        }
        ArticleMetadata meta = new ArticleMetadata();
        meta.setSourceUrl(url);
        meta.setCanonicalUrl(URL);
        meta.setTitle("Virtual threads");
        meta.setPublishedAt("2026-10-29");
        meta.setImageUrl("https://example.com/cover.png");
        return new ExtractedArticle(meta, "Body");
      };

  private final List<String> models = new java.util.ArrayList<>();

  private Main.GeneratorFactory factory(boolean fail) {
    return (config, model) -> {
      models.add(model == null ? config.getOpenAiModel() : model);
      return new PostGenerator() {
        @Override
        public String generate(String prompt) {
          throw new UnsupportedOperationException();
        }

        @Override
        public String generateStructured(StructuredPrompt prompt) {
          if (fail) {
            throw new dev.socialpublisher.generation.GenerationRuntimeException(
                "OpenAI request failed: HTTP 500");
          }
          String platform = prompt.user().split(" ")[2];
          return "{\"text\":\"" + platform + " says hi " + URL + "\"}";
        }

        @Override
        public String provider() {
          return "openai";
        }

        @Override
        public String model() {
          return models.get(models.size() - 1);
        }
      };
    };
  }

  private record Result(int exit, String out, String err) {}

  private Result run(boolean fail, String... args) throws Exception {
    Path config = temp.resolve("social-publisher.yml");
    if (!Files.exists(config)) {
      Files.writeString(
          config,
          "dataDirectory: "
              + temp.resolve("data")
              + "\nprofilesDirectory: "
              + Path.of("profiles").toAbsolutePath()
              + "\nopenAiModel: configured-model\n");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit =
        Main.run(
            args,
            new PrintStream(out),
            new PrintStream(err),
            Map.of("SOCIAL_PUBLISHER_CONFIG", config.toString()),
            extractor,
            factory(fail));
    return new Result(exit, out.toString(), err.toString());
  }

  private static String idOf(String output) {
    Matcher m = Pattern.compile("Created draft ([0-9a-f-]{36})").matcher(output);
    assertTrue(m.find(), output);
    return m.group(1);
  }

  @Test
  void generatesPersistedUnapprovedDraftWithAllPlatforms() throws Exception {
    Result result =
        run(
            false,
            "generate",
            "--url",
            URL,
            "--profile",
            "personal-technical-blog",
            "--model",
            "gpt-x");
    assertEquals(0, result.exit, result.err);
    assertTrue(result.out.contains("Not approved"));
    String id = idOf(result.out);

    SocialPost post = new YamlDraftRepository(temp.resolve("data")).findById(id);
    assertFalse(post.isApproved());
    assertEquals(null, post.getApprovedAt());
    assertTrue(post.getPublishingResults().isEmpty());
    assertEquals(4, post.getPlatformDrafts().size());
    for (Platform platform : Platform.values()) {
      assertTrue(post.getPlatformDrafts().get(platform).getText().contains(URL));
    }
    assertEquals(URL, post.getArticle().getCanonicalUrl());
    assertEquals(URL, post.getArticle().getSourceUrl());
    assertEquals("https://example.com/cover.png", post.getArticle().getImageUrl());
    assertEquals("openai", post.getGeneration().getProvider());
    assertEquals("gpt-x", post.getGeneration().getModel());
    assertEquals("personal-technical-blog", post.getGeneration().getProfile());
    assertNotNull(post.getGeneration().getGeneratedAt());
    assertEquals(List.of("gpt-x"), models);

    String yaml = Files.readString(temp.resolve("data/drafts/" + id + ".yaml"));
    assertTrue(yaml.contains("approved: false"));
    assertFalse(yaml.contains("Body"));
    assertFalse(yaml.toLowerCase().contains("api"));
  }

  @Test
  void usesConfiguredModelByDefaultAndDataDirOverride() throws Exception {
    Path other = temp.resolve("elsewhere");
    Result result = run(false, "generate", "--url", URL, "--data-dir", other.toString());
    assertEquals(0, result.exit, result.err);
    assertEquals(List.of("configured-model"), models);
    assertEquals(1, new YamlDraftRepository(other).findAll().size());
    assertEquals(0, new YamlDraftRepository(temp.resolve("data")).findAll().size());
  }

  @Test
  void previewDoesNotPersist() throws Exception {
    Result result = run(false, "generate", "--url", URL, "--preview");
    assertEquals(0, result.exit, result.err);
    assertTrue(result.out.contains("Preview only"));
    assertTrue(result.out.contains("[LINKEDIN]"));
    assertEquals(0, new YamlDraftRepository(temp.resolve("data")).findAll().size());
  }

  @Test
  void failedGenerationPersistsNothing() throws Exception {
    Result provider = run(true, "generate", "--url", URL);
    assertEquals(2, provider.exit);
    assertTrue(provider.err.contains("HTTP 500"));
    Result extraction = run(false, "generate", "--url", "http://broken.example/");
    assertEquals(2, extraction.exit);
    assertTrue(extraction.err.contains("local address"));
    Result profile = run(false, "generate", "--url", URL, "--profile", "nope");
    assertEquals(2, profile.exit);
    assertEquals(0, new YamlDraftRepository(temp.resolve("data")).findAll().size());
  }

  @Test
  void rejectsUrlWithTopic() throws Exception {
    assertEquals(2, run(false, "generate", "--url", URL, "--topic", "x").exit);
  }

  @Test
  void topicGenerationStillWorksWithoutOpenAi() throws Exception {
    Result result = run(false, "generate", "--topic", "testing");
    assertEquals(0, result.exit, result.err);
    SocialPost post = new YamlDraftRepository(temp.resolve("data")).findById(idOf(result.out));
    assertTrue(post.getText().contains("testing"));
    assertTrue(post.getPlatformDrafts().isEmpty());
    assertTrue(models.isEmpty());
  }

  @Test
  void editingAGeneratedPlatformDraftClearsApprovalAndNeverPublishes() throws Exception {
    String id = idOf(run(false, "generate", "--url", URL).out);
    assertEquals(0, run(false, "approve", id, "--by", "me").exit);
    assertTrue(new YamlDraftRepository(temp.resolve("data")).findById(id).isApproved());

    assertEquals(2, run(false, "draft", "edit", id, "--text", "no platform").exit);
    Result edit =
        run(false, "draft", "edit", id, "--platform", "bluesky", "--text", "Edited " + URL);
    assertEquals(0, edit.exit, edit.err);

    SocialPost post = new YamlDraftRepository(temp.resolve("data")).findById(id);
    assertFalse(post.isApproved());
    assertEquals(null, post.getApprovedBy());
    assertEquals("Edited " + URL, post.getPlatformDrafts().get(Platform.BLUESKY).getText());
    assertTrue(post.getPlatformDrafts().get(Platform.X).getText().contains("X says hi"));
    assertTrue(post.getPublishingResults().isEmpty());
    assertEquals(2, run(false, "publish", id, "--platform", "BLUESKY", "--dry-run").exit);
  }

  @Test
  void dryRunPublishUsesThePlatformSpecificText() throws Exception {
    String id = idOf(run(false, "generate", "--url", URL).out);
    run(false, "approve", id);
    Result dry = run(false, "publish", id, "--platform", "BLUESKY", "--dry-run");
    assertEquals(0, dry.exit, dry.err);
    assertTrue(dry.out.contains("BLUESKY says hi"));
    assertFalse(dry.out.contains("LINKEDIN says hi"));
  }

  @Test
  void configRejectsInvalidAiSettings() throws Exception {
    Files.writeString(temp.resolve("social-publisher.yml"), "openAiTimeoutSeconds: 0\n");
    Result result = run(false, "list");
    assertEquals(2, result.exit);
    assertTrue(result.err.contains("openAiTimeoutSeconds"));
  }
}
