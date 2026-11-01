package dev.socialpublisher.generation;

import dev.socialpublisher.article.ArticleExtractionException;
import dev.socialpublisher.article.ArticleExtractor;
import dev.socialpublisher.article.ExtractedArticle;
import dev.socialpublisher.model.GenerationInfo;
import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.PlatformDraft;
import dev.socialpublisher.model.SocialPost;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an article URL into an unapproved, unpublished multi-platform draft. The returned post is
 * not persisted here; callers decide whether to save or only preview it.
 */
public class ArticleDraftService {
  private final ArticleExtractor extractor;
  private final PostGenerator generator;
  private final int mastodonLimit;

  public ArticleDraftService(
      ArticleExtractor extractor, PostGenerator generator, int mastodonLimit) {
    this.extractor = extractor;
    this.generator = generator;
    this.mastodonLimit = mastodonLimit;
  }

  public SocialPost generate(String url, EditorialProfile profile)
      throws ArticleExtractionException, GenerationException {
    ExtractedArticle article = extractor.extract(url);
    Map<Platform, String> texts =
        new ArticleDraftGenerator(generator, mastodonLimit).generate(article, profile);

    String now = Instant.now().toString();
    String title = article.metadata().getTitle();
    SocialPost post =
        new SocialPost(
            UUID.randomUUID().toString(), title + " " + article.metadata().getCanonicalUrl(), now);
    post.setArticle(article.metadata());
    GenerationInfo info = new GenerationInfo();
    info.setProvider(generator.provider());
    info.setModel(generator.model());
    info.setProfile(profile.getName());
    info.setGeneratedAt(now);
    post.setGeneration(info);
    texts.forEach(
        (platform, text) -> post.getPlatformDrafts().put(platform, new PlatformDraft(text, now)));
    // Generation never approves; the defaults are restated to make the invariant explicit.
    post.setApproved(false);
    post.setApprovedBy(null);
    post.setApprovedAt(null);
    return post;
  }
}
