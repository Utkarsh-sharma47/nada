package nada.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nada.error.BadRequestException;

public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private JsonCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize response", exception);
        }
    }

    public static JsonNode readObject(byte[] body) {
        if (body.length == 0) {
            throw new BadRequestException("invalid JSON");
        }
        final JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception exception) {
            throw new BadRequestException("invalid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new BadRequestException("invalid JSON");
        }
        return node;
    }
}
