package dev.llmgateway.api.openai;

import java.util.List;

public record ModelList(String object, List<Model> data) {

    public record Model(String id, String object, long created, String ownedBy) {}

    public static ModelList of(List<String> ids) {
        return new ModelList("list", ids.stream().map(id -> new Model(id, "model", 0L, "llm-gateway")).toList());
    }
}
