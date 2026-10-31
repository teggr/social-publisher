package dev.socialpublisher.publishing;

import dev.socialpublisher.model.PublishingResult;

public record PublishOutcome(boolean dryRun, String preview, PublishingResult result) {}
