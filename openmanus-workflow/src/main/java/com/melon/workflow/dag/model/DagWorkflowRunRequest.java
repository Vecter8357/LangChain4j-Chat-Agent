package com.melon.workflow.dag.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class DagWorkflowRunRequest {
    private DagWorkflowDefinition definition;
    private JsonNode input;
}
