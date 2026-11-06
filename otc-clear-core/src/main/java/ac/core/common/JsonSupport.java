package ac.core.common;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Thin wrapper around the Jackson 3 mapper with unified error handling. */
@Component
public class JsonSupport {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static JsonSupport ofDefault() {
        return new JsonSupport(JsonMapper.builder().build());
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Cannot serialize JSON: " + e.getMessage());
        }
    }

    public String toPrettyJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Cannot serialize JSON: " + e.getMessage());
        }
    }

    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Cannot parse JSON: " + e.getMessage());
        }
    }

    public List<Object> toList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, LIST_TYPE);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Cannot parse JSON: " + e.getMessage());
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Cannot parse JSON: " + e.getMessage());
        }
    }

    /** Parses JSON, returning the fallback when the text is empty or invalid. */
    public Map<String, Object> toMapOrEmpty(String json) {
        try {
            return toMap(json);
        } catch (ApiException e) {
            return Map.of();
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
