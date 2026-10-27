package com.melon.workflow.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RewriteWorkflowRequest {

    private String title;

    private String sourceText;

    private String targetAudience = "普通中文读者";

    private List<String> constraints = new ArrayList<>();

    private boolean researchEnabled = true;

    private RewriteOutputStyle outputStyle = RewriteOutputStyle.LITERARY_PROSE;
}
