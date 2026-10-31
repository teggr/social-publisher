package dev.socialpublisher.publishing;

import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.SocialPost;

public interface PlatformPublisher {
  Platform platform();

  PublishReceipt publish(SocialPost post) throws PublishException;
}
