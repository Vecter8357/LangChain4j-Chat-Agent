package com.melon.workflow;

import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.ModelRole;
import dev.langchain4j.model.image.ImageModel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkflowImageGatewayTest {
    @Test
    void dedicatedImageModelTakesPriorityOverChatRoleRoutes() {
        var properties = new RewriteWorkflowProperties();
        properties.getModelByRole().put(ModelRole.FINAL_EDITOR, "textOnlyChat");
        properties.getProviderByRole().put(ModelRole.FINAL_EDITOR, "ollama");
        var models = new LangChain4jModelProperties();
        var dedicated = models.getChat().getImage();
        dedicated.setEnabled(true);
        dedicated.setApiKey("dedicated-test-key");
        dedicated.setBaseUrl("http://127.0.0.1:1/v1");
        dedicated.setModelName("dedicated-image-model");
        var gateway = new WorkflowImageGateway(properties, models, Map.of());

        assertThat(gateway.model(ModelRole.FINAL_EDITOR)).isPresent();
        assertThat(gateway.model(ModelRole.FINAL_EDITOR)).containsSame(gateway.model(ModelRole.DRAFT_WRITER).orElseThrow());
    }

    @Test
    void invalidDedicatedImageConfigurationDoesNotSilentlyUseOtherModel() {
        var models = enabledOpenAi();
        models.getChat().getImage().setEnabled(true);
        var gateway = new WorkflowImageGateway(new RewriteWorkflowProperties(), models, Map.of());
        assertThatThrownBy(() -> gateway.model(ModelRole.FINAL_EDITOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("langchain4j.chat.image");
    }

    @Test
    void springCanStartWithoutAnyImageModelBeans() {
        try (var context = new AnnotationConfigApplicationContext(RewriteWorkflowProperties.class,
                LangChain4jModelProperties.class, WorkflowImageGateway.class)) {
            assertThat(context.getBean(WorkflowImageGateway.class).model(ModelRole.FINAL_EDITOR)).isEmpty();
        }
    }

    @Test
    void followsFinalEditorProviderInsteadOfDefaultProvider() {
        var properties = new RewriteWorkflowProperties();
        var models = enabledOpenAi();
        models.getChat().setProvider("openai");
        properties.getProviderByRole().put(ModelRole.FINAL_EDITOR, "ollama");
        var gateway = new WorkflowImageGateway(properties, models, Map.of());
        assertThat(gateway.model(ModelRole.FINAL_EDITOR)).isEmpty();
        assertThat(gateway.model(ModelRole.DRAFT_WRITER)).isPresent();
    }

    @Test
    void namedChatBeanDoesNotInheritDefaultImageCapabilities() {
        var properties = new RewriteWorkflowProperties();
        properties.getModelByRole().put(ModelRole.FINAL_EDITOR, "customChat");
        properties.getProviderByRole().put(ModelRole.FINAL_EDITOR, "openai");
        var gateway = new WorkflowImageGateway(properties, enabledOpenAi(), Map.of());
        assertThat(gateway.model(ModelRole.FINAL_EDITOR)).isEmpty();
    }

    @Test
    void usesExplicitImageBeanForNamedChatBean() {
        var properties = new RewriteWorkflowProperties();
        properties.getModelByRole().put(ModelRole.FINAL_EDITOR, "customChat");
        properties.getImageModelByChatModel().put("customChat", "customImage");
        var image = mock(ImageModel.class);
        var gateway = new WorkflowImageGateway(properties, enabledOpenAi(), Map.of("customImage", image));
        assertThat(gateway.model(ModelRole.FINAL_EDITOR)).contains(image);
    }

    @Test
    void reportsInvalidImageBeanMapping() {
        var properties = new RewriteWorkflowProperties();
        properties.getImageModelByChatModel().put("chatLanguageModel", "missing");
        var gateway = new WorkflowImageGateway(properties, enabledOpenAi(), Map.of());
        assertThatThrownBy(() -> gateway.model(ModelRole.FINAL_EDITOR))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("missing");
    }

    @Test
    void disabledGenerationRequiresNoCredentials() {
        var models = new LangChain4jModelProperties();
        models.getChat().setProvider("openai");
        var gateway = new WorkflowImageGateway(new RewriteWorkflowProperties(), models, Map.of());
        assertThat(gateway.model(ModelRole.FINAL_EDITOR)).isEmpty();
    }

    private LangChain4jModelProperties enabledOpenAi() {
        var models = new LangChain4jModelProperties();
        models.getChat().setProvider("openai");
        var config = models.getChat().getOpenai();
        config.setApiKey("test-key");
        config.setBaseUrl("http://127.0.0.1:1/v1");
        config.getImageGeneration().setEnabled(true);
        config.getImageGeneration().setModelName("test-image-model");
        return models;
    }
}
