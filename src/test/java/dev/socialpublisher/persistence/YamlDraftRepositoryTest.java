package dev.socialpublisher.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.PublishingResult;
import dev.socialpublisher.model.PublishingStatus;
import dev.socialpublisher.model.SocialPost;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlDraftRepositoryTest {
  @TempDir Path tempDirectory;

  @Test
  void persistsPostAndPerPlatformPublicationStateAsYaml() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    String id = UUID.randomUUID().toString();
    SocialPost post = new SocialPost(id, "A test post", Instant.now().toString());
    post.setApproved(true);
    post.setApprovedBy("reviewer");
    PublishingResult result = new PublishingResult();
    result.setStatus(PublishingStatus.PUBLISHED);
    result.setRemoteId("at://did:example/post/1");
    post.getPublishingResults().put(Platform.BLUESKY, result);

    repository.create(post);

    SocialPost saved = repository.findById(id);
    assertEquals("A test post", saved.getText());
    assertTrue(saved.isApproved());
    assertEquals("reviewer", saved.getApprovedBy());
    assertEquals(
        PublishingStatus.PUBLISHED, saved.getPublishingResults().get(Platform.BLUESKY).getStatus());
    assertTrue(
        java.nio.file.Files.readString(tempDirectory.resolve("drafts").resolve(id + ".yaml"))
            .contains("A test post"));
  }

  @Test
  void rejectsInvalidIdentifiersAndDuplicateDrafts() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost post =
        new SocialPost(UUID.randomUUID().toString(), "Post", Instant.now().toString());
    repository.create(post);

    assertThrows(IOException.class, () -> repository.findById("../outside"));
    assertThrows(IOException.class, () -> repository.create(post));
  }
}
