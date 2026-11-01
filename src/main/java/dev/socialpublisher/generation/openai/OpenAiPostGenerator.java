package dev.socialpublisher.generation.openai;

import dev.socialpublisher.config.AppConfig;
import dev.socialpublisher.generation.GenerationRuntimeException;
import dev.socialpublisher.generation.PostGenerator;
import dev.socialpublisher.generation.StructuredPrompt;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * OpenAI implementation of {@link PostGenerator} built on Spring AI's chat model (without Spring
 * Boot). All OpenAI-specific details stay in this class. The API key is supplied by the caller from
 * an environment variable and is never logged or persisted.
 */
public class OpenAiPostGenerator implements PostGenerator {
  private static final String SYSTEM_FOR_PLAIN_PROMPTS =
      "You write short, accurate social media posts for human review.";

  private final OpenAiChatModel chatModel;
  private final String model;
  private final String apiKey;

  public OpenAiPostGenerator(String apiKey, String baseUrl, String model, Duration timeout) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("An OpenAI API key is required");
    }
    this.apiKey = apiKey;
    this.model = model;
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
    requestFactory.setReadTimeout(timeout);
    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
            .build();
    RetryTemplate retry =
        RetryTemplate.builder()
            .maxAttempts(2)
            .fixedBackoff(Duration.ofSeconds(1))
            .retryOn(TransientAiException.class)
            .build();
    this.chatModel =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model(model).build())
            .retryTemplate(retry)
            .build();
  }

  /** Builds a generator from configuration; the key is read from the configured variable. */
  public static OpenAiPostGenerator fromConfig(
      AppConfig config, Map<String, String> environment, String modelOverride) {
    String key = environment.get(config.getOpenAiApiKeyEnv());
    if (key == null || key.isBlank()) {
      throw new GenerationRuntimeException(
          "OpenAI API key not found; set the "
              + config.getOpenAiApiKeyEnv()
              + " environment variable");
    }
    String model =
        modelOverride != null && !modelOverride.isBlank() ? modelOverride : config.getOpenAiModel();
    return new OpenAiPostGenerator(
        key.trim(),
        config.getOpenAiBaseUrl(),
        model,
        Duration.ofSeconds(config.getOpenAiTimeoutSeconds()));
  }

  @Override
  public String generate(String prompt) {
    return call(
        new Prompt(
            List.of(new SystemMessage(SYSTEM_FOR_PLAIN_PROMPTS), new UserMessage(prompt)),
            OpenAiChatOptions.builder().model(model).build()));
  }

  @Override
  public String generateStructured(StructuredPrompt prompt) {
    ResponseFormat format =
        ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(
                ResponseFormat.JsonSchema.builder()
                    .name(prompt.schemaName())
                    .schema(prompt.jsonSchema())
                    .strict(true)
                    .build())
            .build();
    return call(
        new Prompt(
            List.of(new SystemMessage(prompt.system()), new UserMessage(prompt.user())),
            OpenAiChatOptions.builder().model(model).responseFormat(format).build()));
  }

  @Override
  public String provider() {
    return "openai";
  }

  @Override
  public String model() {
    return model;
  }

  private String call(Prompt prompt) {
    ChatResponse response;
    try {
      response = chatModel.call(prompt);
    } catch (RuntimeException e) {
      throw new GenerationRuntimeException("OpenAI request failed: " + describe(e), e);
    }
    if (response == null || response.getResult() == null) {
      throw new GenerationRuntimeException("OpenAI returned no result");
    }
    String text = response.getResult().getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new GenerationRuntimeException("OpenAI returned an empty response");
    }
    return text;
  }

  private String describe(RuntimeException e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    message = message.replace(apiKey, "***").replaceAll("\\s+", " ");
    return message.length() > 500 ? message.substring(0, 500) + "..." : message;
  }
}
