package dev.socialpublisher.config;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigLoader {
  private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

  private ConfigLoader() {}

  public static AppConfig load(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return new AppConfig();
    }
    AppConfig config = YAML_MAPPER.readValue(path.toFile(), AppConfig.class);
    if (config == null) {
      throw new IOException("Configuration file must contain a YAML mapping");
    }
    if (config.getDataDirectory() == null || config.getDataDirectory().isBlank()) {
      throw new IOException("Configuration dataDirectory must not be empty");
    }
    if (config.getBlueskyService() == null || config.getBlueskyService().isBlank()) {
      throw new IOException("Configuration blueskyService must not be empty");
    }
    if (config.getBlueskyIdentifierEnv() == null || config.getBlueskyIdentifierEnv().isBlank()) {
      throw new IOException("Configuration blueskyIdentifierEnv must not be empty");
    }
    if (config.getBlueskyAppPasswordEnv() == null || config.getBlueskyAppPasswordEnv().isBlank()) {
      throw new IOException("Configuration blueskyAppPasswordEnv must not be empty");
    }
    requireText(config.getOpenAiApiKeyEnv(), "openAiApiKeyEnv");
    requireText(config.getOpenAiBaseUrl(), "openAiBaseUrl");
    requireText(config.getOpenAiModel(), "openAiModel");
    requireText(config.getProfilesDirectory(), "profilesDirectory");
    if (config.getOpenAiTimeoutSeconds() < 1 || config.getOpenAiTimeoutSeconds() > 600) {
      throw new IOException("Configuration openAiTimeoutSeconds must be between 1 and 600");
    }
    if (config.getMastodonCharacterLimit() < 50) {
      throw new IOException("Configuration mastodonCharacterLimit must be at least 50");
    }
    if (config.getDefaultProfile() != null && config.getDefaultProfile().isBlank()) {
      config.setDefaultProfile(null);
    }
    return config;
  }

  private static void requireText(String value, String name) throws IOException {
    if (value == null || value.isBlank()) {
      throw new IOException("Configuration " + name + " must not be empty");
    }
  }
}
