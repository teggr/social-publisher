package dev.socialpublisher.generation;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads editorial profiles from {@code <profilesDirectory>/<name>.yml}. */
public final class ProfileLoader {
  private static final YAMLMapper YAML_MAPPER =
      YAMLMapper.builder().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();

  private ProfileLoader() {}

  public static EditorialProfile load(Path profilesDirectory, String name) throws IOException {
    if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
      throw new IOException("Invalid profile name; use letters, digits, '-' and '_': " + name);
    }
    Path file = profilesDirectory.resolve(name + ".yml");
    if (!Files.isRegularFile(file)) {
      file = profilesDirectory.resolve(name + ".yaml");
    }
    if (!Files.isRegularFile(file)) {
      throw new IOException(
          "Profile not found: " + name + " (looked in " + profilesDirectory + ")");
    }
    EditorialProfile profile = YAML_MAPPER.readValue(file.toFile(), EditorialProfile.class);
    if (profile == null) {
      throw new IOException("Profile " + name + " must contain a YAML mapping");
    }
    profile.setName(name);
    return profile;
  }
}
