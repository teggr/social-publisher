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
    if (config.getDataDirectory() == null || config.getDataDirectory().isBlank()) {
      throw new IOException("Configuration dataDirectory must not be empty");
    }
    if (config.getBlueskyService() == null || config.getBlueskyService().isBlank()) {
      throw new IOException("Configuration blueskyService must not be empty");
    }
    return config;
  }
}
