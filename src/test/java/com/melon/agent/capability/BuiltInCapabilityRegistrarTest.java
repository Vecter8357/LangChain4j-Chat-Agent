package com.melon.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.ToolSecurityProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInCapabilityRegistrarTest {

    @Test
    void registersSafeFilesystemAndKnowledgeCapabilities() {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        BuiltInCapabilityRegistrar registrar = new BuiltInCapabilityRegistrar(
                registry, new ToolSecurityProperties(), new ObjectMapper());

        registrar.register();

        assertThat(registry.list()).extracting(CapabilityDescriptor::id)
                .contains("local.filesystem.list_directory",
                        "local.filesystem.read_file",
                        "local.filesystem.search_files",
                        "local.filesystem.info",
                        BuiltInCapabilityRegistrar.RAG_ANSWER)
                .doesNotContain("local.filesystem.write_file", "local.filesystem.delete");
        assertThat(registry.findById(BuiltInCapabilityRegistrar.RAG_ANSWER)
                .orElseThrow().inputSchema().path("required").toString())
                .contains("question", "fileIds");
    }
}
