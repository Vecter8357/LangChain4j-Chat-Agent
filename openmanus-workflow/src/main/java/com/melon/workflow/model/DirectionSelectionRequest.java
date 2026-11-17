package com.melon.workflow.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DirectionSelectionRequest {

    private List<String> directionIds = new ArrayList<>();
}
