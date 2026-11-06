package com.melon.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class JsonSchemaValidator {

    /** 校验 JSON 值并聚合错误路径，省略 Schema 时跳过校验。 */
    public void validate(JsonNode value, JsonNode schema, String subject) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return;
        }
        List<String> errors = new ArrayList<>();
        validateValue(value, schema, "$", errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(subject + " 不符合 JSON Schema: " + String.join("；", errors));
        }
    }

    /** 递归检查当前值的类型、对象必填属性和数组元素，并记录失败位置。 */
    private void validateValue(JsonNode value, JsonNode schema, String path, List<String> errors) {
        String type = schema.path("type").asText();
        if (!type.isBlank() && !matchesType(value, type)) {
            errors.add(path + " 应为 " + type);
            return;
        }
        if ("object".equals(type) && value != null && value.isObject()) {
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                required.forEach(field -> {
                    if (!value.has(field.asText()) || value.get(field.asText()).isNull()) {
                        errors.add(path + "." + field.asText() + " 为必填字段");
                    }
                });
            }
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                properties.properties().forEach(entry -> {
                    if (value.has(entry.getKey())) {
                        validateValue(value.get(entry.getKey()), entry.getValue(), path + "." + entry.getKey(), errors);
                    }
                });
            }
        }
        if ("array".equals(type) && value != null && value.isArray() && schema.has("items")) {
            for (int index = 0; index < value.size(); index++) {
                validateValue(value.get(index), schema.get("items"), path + "[" + index + "]", errors);
            }
        }
    }

    /** 判断 JSON 值是否符合当前支持的 Schema 类型。 */
    private boolean matchesType(JsonNode value, String type) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }
}
