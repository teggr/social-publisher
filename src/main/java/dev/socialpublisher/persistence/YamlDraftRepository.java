package dev.socialpublisher.persistence;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.socialpublisher.model.SocialPost;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class YamlDraftRepository {
  private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
  private final Path draftsDirectory;

  public YamlDraftRepository(Path dataDirectory) {
    this.draftsDirectory = dataDirectory.resolve("drafts");
  }

  public void create(SocialPost post) throws IOException {
    withLock(
        post.getId(),
        () -> {
          Path file = draftPath(post.getId());
          if (Files.exists(file)) {
            throw new RepositoryException("Draft already exists: " + post.getId());
          }
          writeAtomic(file, post);
          return null;
        });
  }

  public SocialPost findById(String id) throws IOException {
    Path file = draftPath(id);
    if (!Files.isRegularFile(file)) {
      throw new IOException("Draft not found: " + id);
    }
    return YAML_MAPPER.readValue(file.toFile(), SocialPost.class);
  }

  public List<SocialPost> findAll() throws IOException {
    if (!Files.isDirectory(draftsDirectory)) {
      return List.of();
    }
    List<SocialPost> posts = new ArrayList<>();
    try (var files = Files.list(draftsDirectory)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).toList()) {
        posts.add(YAML_MAPPER.readValue(file.toFile(), SocialPost.class));
      }
    }
    posts.sort(Comparator.comparing(SocialPost::getCreatedAt));
    return posts;
  }

  public SocialPost update(String id, Consumer<SocialPost> change) throws IOException {
    return withLock(
        id,
        () -> {
          SocialPost post = findById(id);
          change.accept(post);
          writeAtomic(draftPath(id), post);
          return post;
        });
  }

  private <T> T withLock(String id, IoSupplier<T> action) throws IOException {
    Path lockFile = lockPath(id);
    Files.createDirectories(draftsDirectory);
    try (FileChannel channel =
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ignored = channel.lock()) {
      try {
        return action.get();
      } catch (RepositoryException e) {
        throw e;
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("Could not update draft " + id, e);
      }
    }
  }

  private void writeAtomic(Path destination, SocialPost post) throws IOException {
    Path temporary =
        Files.createTempFile(draftsDirectory, post.getId() + "-", ".tmp");
    try {
      YAML_MAPPER.writeValue(temporary.toFile(), post);
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Path draftPath(String id) throws IOException {
    validateId(id);
    return draftsDirectory.resolve(id + ".yaml");
  }

  private Path lockPath(String id) throws IOException {
    validateId(id);
    return draftsDirectory.resolve(id + ".lock");
  }

  private void validateId(String id) throws IOException {
    if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
      throw new IOException("Invalid draft ID");
    }
  }

  @FunctionalInterface
  private interface IoSupplier<T> {
    T get() throws Exception;
  }

  private static final class RepositoryException extends IOException {
    private RepositoryException(String message) {
      super(message);
    }
  }
}
