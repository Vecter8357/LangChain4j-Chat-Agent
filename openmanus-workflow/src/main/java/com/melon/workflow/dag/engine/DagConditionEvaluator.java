package com.melon.workflow.dag.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.workflow.dag.model.DagWorkflowDefinition.ConditionOperator;
import com.melon.workflow.dag.model.DagWorkflowDefinition.EdgeCondition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DagConditionEvaluator {

    private final DagVariableResolver variableResolver;

    /** 注入解析条件路径所需的变量解析器。 */
    public DagConditionEvaluator(DagVariableResolver variableResolver) {
        this.variableResolver = variableResolver;
    }

    /** 读取条件路径对应的值并按运算符求值，无条件边默认命中。 */
    public boolean matches(JsonNode context, EdgeCondition condition) {
        if (condition == null) {
            return true;
        }
        JsonNode actual = variableResolver.resolve(context, condition.getPath());
        JsonNode expected = condition.getValue();
        ConditionOperator operator = condition.getOperator();
        return switch (operator) {
            case EXISTS -> !actual.isMissingNode() && !actual.isNull();
            case NOT_EXISTS -> actual.isMissingNode() || actual.isNull();
            case TRUTHY -> truthy(actual);
            case FALSY -> !truthy(actual);
            case EQ -> valuesEqual(actual, expected);
            case NE -> !valuesEqual(actual, expected);
            case GT -> compareNumbers(actual, expected) > 0;
            case GTE -> compareNumbers(actual, expected) >= 0;
            case LT -> compareNumbers(actual, expected) < 0;
            case LTE -> compareNumbers(actual, expected) <= 0;
            case CONTAINS -> contains(actual, expected);
        };
    }

    /** 将非零数字、非空白字符串和非空容器视为真值。 */
    private boolean truthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.decimalValue().compareTo(BigDecimal.ZERO) != 0;
        }
        if (value.isTextual()) {
            return !value.asText().isBlank();
        }
        return value.size() > 0;
    }

    /** 按数值大小比较数字节点，并对缺失值和空值使用约定的相等规则。 */
    private boolean valuesEqual(JsonNode actual, JsonNode expected) {
        if (actual == null || actual.isMissingNode()) {
            return expected == null || expected.isNull();
        }
        if (expected == null) {
            return actual.isNull();
        }
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        return actual.equals(expected);
    }

    /** 比较两个 JSON 数字，其他类型触发条件错误。 */
    private int compareNumbers(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null || !actual.isNumber() || !expected.isNumber()) {
            throw new IllegalArgumentException("GT/GTE/LT/LTE 条件只能比较数字");
        }
        return actual.decimalValue().compareTo(expected.decimalValue());
    }

    /** 对字符串检查子串，对数组检查成员，对对象检查属性名。 */
    private boolean contains(JsonNode actual, JsonNode expected) {
        if (actual == null || actual.isMissingNode() || expected == null) {
            return false;
        }
        if (actual.isTextual()) {
            return actual.asText().contains(expected.asText());
        }
        if (actual.isArray()) {
            for (JsonNode item : actual) {
                if (valuesEqual(item, expected)) {
                    return true;
                }
            }
        }
        return actual.isObject() && expected.isTextual() && actual.has(expected.asText());
    }
}
