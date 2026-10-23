package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException;
import cn.edu.nju.Iot_Verify.component.ai.provider.LlmProvider;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Facade for one-shot (system + user) prompt completions that return text.
 *
 * <p>Template Method for the identical "build system+user request → call model → read text"
 * pattern the recommend/duplicate-check AI tools all repeated against the raw SDK. Those tools
 * now depend only on this facade and the domain model, not on any LLM client or SDK.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptCompletionService {

    private final LlmProvider llmProvider;
    private final LlmConfig llmConfig;

    /**
     * Run a single system+user completion and return the assistant text.
     *
     * @param systemPrompt system role content
     * @param userPrompt   user role content
     * @param temperature  sampling temperature
     * @param maxTokens    max completion tokens
     * @return the assistant's text content, or empty string if the model returned nothing
     */
    public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return complete(systemPrompt, userPrompt, temperature, maxTokens, null, "general", null);
    }

    /** The bounded authorization JSON budget must remain available for the decision itself. */
    public String completeClassification(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        String effort = LlmConfig.requiresResponsesForToolReasoning(llmConfig.getModel()) ? "none" : null;
        return complete(systemPrompt, userPrompt, temperature, maxTokens, null, "classification", effort);
    }

    public String completeRecommendation(
            String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        String recommendationModel = llmConfig.getRecommendationModel();
        String model = recommendationModel == null || recommendationModel.isBlank()
                ? null : recommendationModel.trim();
        return complete(systemPrompt, userPrompt, temperature, maxTokens, model, "recommendation", null);
    }

    private String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens,
                            String model, String purpose, String reasoningEffort) {
        long startedAt = System.nanoTime();
        LlmChatRequest request = LlmChatRequest.builder()
                .messages(List.of(
                        LlmMessage.system(systemPrompt),
                        LlmMessage.user(userPrompt)))
                .temperature(temperature)
                .maxTokens(maxTokens)
                .model(model)
                .reasoningEffort(reasoningEffort)
                .build();

        LlmRequestControl control = LlmRequestControlHolder.currentOrNew();
        LlmChatResponse response;
        try {
            response = llmProvider.chat(request, control);
        } catch (LlmStreamInterruptedException failure) {
            if (!"classification".equals(purpose) || Thread.currentThread().isInterrupted()
                    || control.isCancellationRequested()) {
                throw failure;
            }
            // Classifiers execute no tools; one retry keeps the same cancellation and transport state.
            log.info("Retrying interrupted read-only classification once");
            response = llmProvider.chat(request, control);
        }
        String text = response.text();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("LLM {} completion finished: promptChars={}, outputChars={}, elapsedMs={}",
                purpose,
                (systemPrompt == null ? 0 : systemPrompt.length()) + (userPrompt == null ? 0 : userPrompt.length()),
                text == null ? 0 : text.length(),
                elapsedMs);
        return text;
    }
}
