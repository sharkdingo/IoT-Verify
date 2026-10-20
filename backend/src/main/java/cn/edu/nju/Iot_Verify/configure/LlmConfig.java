package cn.edu.nju.Iot_Verify.configure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Vendor-agnostic LLM configuration ({@code llm.*}).
 *
 * <p>Targets any OpenAI-compatible endpoint — the official OpenAI API, a self-hosted
 * gateway, or a relay ("中转站"). Point {@code base-url} at the endpoint and supply the
 * matching API key; no code change is needed to switch providers.
 *
 * <p>Env mapping (see docs/getting-started/configuration.md):
 * {@code IOT_VERIFY_OPENAI_API_KEY} → {@code llm.api-key}, {@code IOT_VERIFY_OPENAI_BASE_URL} → {@code llm.base-url},
 * {@code IOT_VERIFY_OPENAI_MODEL} → {@code llm.model}.
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    /** Provider implementation selector. Currently only {@code openai} is implemented. */
    @NotBlank
    private String provider = "openai";

    @NotBlank
    @Pattern(regexp = "auto|chat-completions|responses")
    private String api = "auto";

    @Pattern(regexp = "|none|minimal|low|medium|high|xhigh|max")
    private String reasoningEffort;

    @NotBlank
    @ToString.Exclude
    private String apiKey;

    /** Model id/deployment name passed to the endpoint (e.g. {@code gpt-5.6-luna}). */
    @NotBlank
    private String model;

    /** Optional model dedicated to recommendation generation; blank reuses {@link #model}. */
    private String recommendationModel;

    /** OpenAI-compatible base URL. Points at the official API or a relay/gateway. */
    @NotBlank
    private String baseUrl = "https://api.openai.com/v1";

    @Min(1)
    private int timeoutMinutes = 5;

    public boolean usesResponses(String effectiveModel) {
        return "responses".equals(api) || ("auto".equals(api) && requiresResponsesForToolReasoning(effectiveModel));
    }

    /** These model families require Responses to combine reasoning with function calls. */
    public static boolean requiresResponsesForToolReasoning(String model) {
        if (model == null) return false;
        return java.util.List.of("gpt-6-luna", "gpt-6-sol", "gpt-6-astra", "gpt-6.1-sol").stream()
                .anyMatch(name -> model.equals(name) || model.startsWith(name + "-"));
    }
}
