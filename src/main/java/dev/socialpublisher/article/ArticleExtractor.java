package dev.socialpublisher.article;

/** Fetches and extracts an article; independent of any AI provider. */
public interface ArticleExtractor {
  ExtractedArticle extract(String url) throws ArticleExtractionException;
}
