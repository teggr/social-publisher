package dev.socialpublisher.article;

import dev.socialpublisher.model.ArticleMetadata;

/** Article metadata plus its main text. The text is untrusted input. */
public record ExtractedArticle(ArticleMetadata metadata, String content) {}
