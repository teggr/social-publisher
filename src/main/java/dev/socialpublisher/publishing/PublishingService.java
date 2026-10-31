package dev.socialpublisher.publishing;

import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.PublishingResult;
import dev.socialpublisher.model.PublishingStatus;
import dev.socialpublisher.model.SocialPost;
import dev.socialpublisher.persistence.YamlDraftRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class PublishingService {
  private final YamlDraftRepository repository;
  private final Map<Platform, PlatformPublisher> publishers = new EnumMap<>(Platform.class);

  public PublishingService(YamlDraftRepository repository, List<PlatformPublisher> publishers) {
    this.repository = repository;
    for (PlatformPublisher publisher : publishers) {
      this.publishers.put(publisher.platform(), publisher);
    }
  }

  public PublishOutcome publish(String id, Platform platform, boolean dryRun) throws IOException {
    PlatformPublisher publisher = publishers.get(platform);
    if (publisher == null && !dryRun) {
      throw new IllegalArgumentException("No publisher is configured for " + platform);
    }

    SocialPost post = repository.findById(id);
    if (!post.isApproved()) {
      throw new IllegalStateException("Draft must be approved before publishing");
    }
    if (dryRun) {
      return new PublishOutcome(true, post.getText(), null);
    }

    PublishingResult attempt =
        repository
            .update(
                id,
                current -> {
                  if (!current.isApproved()) {
                    throw new IllegalStateException("Draft must be approved before publishing");
                  }
                  PublishingResult result = resultFor(current, platform);
                  if (result.getStatus() == PublishingStatus.PUBLISHED) {
                    throw new IllegalStateException("Draft is already published to " + platform);
                  }
                  if (result.getStatus() == PublishingStatus.IN_PROGRESS
                      || result.getStatus() == PublishingStatus.UNKNOWN) {
                    throw new IllegalStateException(
                        "A previous publication outcome is unresolved for "
                            + platform
                            + "; reconcile it before retrying");
                  }
                  if (result.getStatus() == PublishingStatus.FAILED && !result.isRetrySafe()) {
                    throw new IllegalStateException(
                        "A previous publication failure is not safe to retry for " + platform);
                  }
                  result.setStatus(PublishingStatus.IN_PROGRESS);
                  result.setAttemptedAt(Instant.now().toString());
                  result.setAttempts(result.getAttempts() + 1);
                  result.setError(null);
                  result.setRetrySafe(false);
                })
            .getPublishingResults()
            .get(platform);

    try {
      PublishReceipt receipt = publisher.publish(post);
      PublishingResult result =
          repository
              .update(
                  id,
                  current -> {
                    PublishingResult currentResult = resultFor(current, platform);
                    currentResult.setStatus(PublishingStatus.PUBLISHED);
                    currentResult.setRemoteId(receipt.remoteId());
                    currentResult.setUrl(receipt.url());
                    currentResult.setPublishedAt(Instant.now().toString());
                    currentResult.setError(null);
                    currentResult.setRetrySafe(false);
                  })
              .getPublishingResults()
              .get(platform);
      return new PublishOutcome(false, null, result);
    } catch (PublishException e) {
      PublishingResult result =
          repository
              .update(
                  id,
                  current -> {
                    PublishingResult currentResult = resultFor(current, platform);
                    currentResult.setStatus(
                        e.isRetrySafe() ? PublishingStatus.FAILED : PublishingStatus.UNKNOWN);
                    currentResult.setError(e.getMessage());
                    currentResult.setRetrySafe(e.isRetrySafe());
                  })
              .getPublishingResults()
              .get(platform);
      throw new PublishingFailedException(
          "Publishing to " + platform + " failed: " + e.getMessage(), result, e);
    } catch (RuntimeException e) {
      repository.update(
          id,
          current -> {
            PublishingResult currentResult = resultFor(current, platform);
            currentResult.setStatus(PublishingStatus.UNKNOWN);
            currentResult.setError("Unexpected adapter error; publication outcome is unknown");
            currentResult.setRetrySafe(false);
          });
      throw e;
    }
  }

  private PublishingResult resultFor(SocialPost post, Platform platform) {
    return post.getPublishingResults().computeIfAbsent(platform, key -> new PublishingResult());
  }
}
