package cn.edu.nju.Iot_Verify.component.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A complete JSON response may have one enclosing code fence, never surrounding prose. */
public final class ModelJsonOutput {
    private ModelJsonOutput() {}

    public static JsonNode parse(ObjectMapper mapper, String value) throws JsonProcessingException {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int closingFence = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd && closingFence == text.length() - 3) {
                text = text.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(text);
    }
}
