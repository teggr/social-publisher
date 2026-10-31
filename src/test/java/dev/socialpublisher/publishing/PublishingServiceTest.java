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

  @Test
  void partialFailureOnOnePlatformDoesNotAffectAnother() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost post = createPost(repository, true);
    StubPublisher bluesky = new StubPublisher(Platform.BLUESKY);
    bluesky.failure = new PublishException("connection lost", false);
    StubPublisher linkedin = new StubPublisher(Platform.LINKEDIN);
    PublishingService service = new PublishingService(repository, List.of(bluesky, linkedin));

    assertThrows(
        PublishingFailedException.class,
        () -> service.publish(post.getId(), Platform.BLUESKY, false));
    service.publish(post.getId(), Platform.LINKEDIN, false);

    SocialPost saved = repository.findById(post.getId());
    assertEquals(
        PublishingStatus.UNKNOWN, saved.getPublishingResults().get(Platform.BLUESKY).getStatus());
    assertEquals(
        PublishingStatus.PUBLISHED,
        saved.getPublishingResults().get(Platform.LINKEDIN).getStatus());
    assertThrows(
        IllegalStateException.class, () -> service.publish(post.getId(), Platform.BLUESKY, false));
    assertEquals(1, bluesky.calls);
  }

  @Test
  void interruptedInProgressAndUnexpectedErrorsBlockRetries() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost stuck = createPost(repository, true);
    repository.update(
        stuck.getId(),
        post -> {
          dev.socialpublisher.model.PublishingResult result =
              new dev.socialpublisher.model.PublishingResult();
          result.setStatus(PublishingStatus.IN_PROGRESS);
          post.getPublishingResults().put(Platform.BLUESKY, result);
        });
    StubPublisher publisher = new StubPublisher(Platform.BLUESKY);
    PublishingService service = new PublishingService(repository, List.of(publisher));

    assertThrows(
        IllegalStateException.class, () -> service.publish(stuck.getId(), Platform.BLUESKY, false));
    assertEquals(0, publisher.calls);

    SocialPost crashing = createPost(repository, true);
    publisher.runtimeFailure = new IllegalStateException("boom");
    assertThrows(
        IllegalStateException.class,
        () -> service.publish(crashing.getId(), Platform.BLUESKY, false));
    assertEquals(
        PublishingStatus.UNKNOWN,
        repository
            .findById(crashing.getId())
            .getPublishingResults()
            .get(Platform.BLUESKY)
            .getStatus());
    publisher.runtimeFailure = null;
    assertThrows(
        IllegalStateException.class,
        () -> service.publish(crashing.getId(), Platform.BLUESKY, false));
    assertEquals(1, publisher.calls);
  }

  @Test
  void reconcileResolvesOnlyUnresolvedStatesWithoutCallingThePlatform() throws Exception {
    YamlDraftRepository repository = new YamlDraftRepository(tempDirectory);
    SocialPost post = createPost(repository, true);
    StubPublisher publisher = new StubPublisher(Platform.BLUESKY);
    publisher.failure = new PublishException("connection lost", false);
    PublishingService service = new PublishingService(repository, List.of(publisher));
    assertThrows(
        IllegalStateException.class,
        () -> service.reconcile(post.getId(), Platform.BLUESKY, false, null, null));
    assertThrows(
        PublishingFailedException.class,
        () -> service.publish(post.getId(), Platform.BLUESKY, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.reconcile(post.getId(), Platform.BLUESKY, true, " ", null));

    service.reconcile(post.getId(), Platform.BLUESKY, false, null, null);
    assertEquals(
        PublishingStatus.FAILED,
        repository.findById(post.getId()).getPublishingResults().get(Platform.BLUESKY).getStatus());
    publisher.failure = new PublishException("lost again", false);
    assertThrows(
        PublishingFailedException.class,
        () -> service.publish(post.getId(), Platform.BLUESKY, false));
    service.reconcile(post.getId(), Platform.BLUESKY, true, "at://remote", "https://x.test/1");

    var result = repository.findById(post.getId()).getPublishingResults().get(Platform.BLUESKY);
    assertEquals(PublishingStatus.PUBLISHED, result.getStatus());
    assertEquals("at://remote", result.getRemoteId());
    assertEquals(2, result.getAttempts());
    assertEquals(2, publisher.calls);
    assertThrows(
        IllegalStateException.class,
        () -> service.reconcile(post.getId(), Platform.BLUESKY, false, null, null));
    assertThrows(
        IllegalStateException.class, () -> service.publish(post.getId(), Platform.BLUESKY, false));
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
    private RuntimeException runtimeFailure;
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
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      if (failure != null) {
        throw failure;
      }
      return new PublishReceipt("remote-" + platform, "https://example.test/" + platform);
    }
  }
}
