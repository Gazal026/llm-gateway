package dev.llmgateway.api.openai;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Accepts {@code "content": "hi"} as well as {@code "content": [{"type": "text", "text": "hi"}]}.
 * Non-text parts (images, audio) are rejected with a clear 400 until multimodal support is added.
 */
public class MessageContentDeserializer extends StdDeserializer<String> {

    public MessageContentDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        if (token == JsonToken.START_ARRAY) {
            JsonNode parts = context.readTree(parser);
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                String type = part.path("type").asText();
                if (!"text".equals(type)) {
                    throw JsonMappingException.from(parser,
                            "Only text content parts are supported (got '" + type + "')");
                }
                text.append(part.path("text").asText());
            }
            return text.toString();
        }
        throw JsonMappingException.from(parser, "Message content must be a string or an array of content parts");
    }
}
