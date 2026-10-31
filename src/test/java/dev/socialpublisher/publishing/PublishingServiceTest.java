package dev.socialpublisher.publishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.PublishingStatus;
import dev.socialpublisher.model.SocialPost;
import dev.socialpublisher.persistence.YamlDraftRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishingServiceTest {
  @TempDir Path tempDirectory;

  @Test
  void requiresApprovalAndDryRunDoesNotPersistAResult() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost post = createPost(repository, false);
    StubPublisher publisher = new StubPublisher(Platform.BLUESKY);
    PublishingService service = new PublishingService(repository, List.of(publisher));

    assertThrows(
        IllegalStateException.class, () -> service.publish(post.getId(), Platform.BLUESKY, true));
    approve(repository, post.getId());

    PublishOutcome outcome = service.publish(post.getId(), Platform.BLUESKY, true);

    assertTrue(outcome.dryRun());
    assertEquals(post.getText(), outcome.preview());
    assertEquals(0, publisher.calls);
    assertTrue(repository.findById(post.getId()).getPublishingResults().isEmpty());
  }

  @Test
  void tracksPlatformsIndependentlyAndBlocksDuplicatePublication() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost post = createPost(repository, true);
    StubPublisher bluesky = new StubPublisher(Platform.BLUESKY);
    StubPublisher linkedin = new StubPublisher(Platform.LINKEDIN);
    PublishingService service = new PublishingService(repository, List.of(bluesky, linkedin));

    service.publish(post.getId(), Platform.BLUESKY, false);
    service.publish(post.getId(), Platform.LINKEDIN, false);

    SocialPost published = repository.findById(post.getId());
    assertEquals(
        PublishingStatus.PUBLISHED,
        published.getPublishingResults().get(Platform.BLUESKY).getStatus());
    assertEquals(
        PublishingStatus.PUBLISHED,
        published.getPublishingResults().get(Platform.LINKEDIN).getStatus());
    assertEquals(
        PublishingStatus.IN_PROGRESS,
        bluesky.lastPost.getPublishingResults().get(Platform.BLUESKY).getStatus());
    assertThrows(
        IllegalStateException.class, () -> service.publish(post.getId(), Platform.BLUESKY, false));
    assertEquals(1, bluesky.calls);
    assertEquals(1, linkedin.calls);
  }

  @Test
  void retriesOnlyFailuresKnownToBeSafe() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost post = createPost(repository, true);
    StubPublisher retryable = new StubPublisher(Platform.BLUESKY);
    retryable.failure = new PublishException("temporary rejection", true);
    PublishingService service = new PublishingService(repository, List.of(retryable));

    assertThrows(
        PublishingFailedException.class,
        () -> service.publish(post.getId(), Platform.BLUESKY, false));
    assertEquals(
        PublishingStatus.FAILED,
        repository.findById(post.getId()).getPublishingResults().get(Platform.BLUESKY).getStatus());

    retryable.failure = null;
    service.publish(post.getId(), Platform.BLUESKY, false);
    assertEquals(2, retryable.calls);
  }

  @Test
  void marksAmbiguousFailureUnknownAndNeverAutomaticallyRetries() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost post = createPost(repository, true);
    StubPublisher ambiguous = new StubPublisher(Platform.BLUESKY);
    ambiguous.failure = new PublishException("connection lost after request", false);
    PublishingService service = new PublishingService(repository, List.of(ambiguous));

    assertThrows(
        PublishingFailedException.class,
        () -> service.publish(post.getId(), Platform.BLUESKY, false));
    assertEquals(
        PublishingStatus.UNKNOWN,
        repository.findById(post.getId()).getPublishingResults().get(Platform.BLUESKY).getStatus());
    assertThrows(
        IllegalStateException.class, () -> service.publish(post.getId(), Platform.BLUESKY, false));
    assertEquals(1, ambiguous.calls);
  }

  private SocialPost createPost(YamlDraftRepository repository, boolean approved) throws Exception {
    SocialPost post =
        new SocialPost(UUID.randomUUID().toString(), "Test post", Instant.now().toString());
    repository.create(post);
    if (approved) {
      approve(repository, post.getId());
    }
    return post;
  }

  private void approve(YamlDraftRepository repository, String id) throws Exception {
    repository.update(
        id,
        post -> {
          post.setApproved(true);
          post.setApprovedAt(Instant.now().toString());
        });
  }

  private static final class StubPublisher implements PlatformPublisher {
    private final Platform platform;
    private int calls;
    private PublishException failure;
    private SocialPost lastPost;

    private StubPublisher(Platform platform) {
      this.platform = platform;
    }

    @Override
    public Platform platform() {
      return platform;
    }

    @Override
    public PublishReceipt publish(SocialPost post) throws PublishException {
      calls++;
      lastPost = post;
      if (failure != null) {
        throw failure;
      }
      return new PublishReceipt("remote-" + platform, "https://example.test/" + platform);
    }
  }
}
