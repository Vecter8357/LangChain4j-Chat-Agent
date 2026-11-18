package com.melon.workflow;

import com.melon.config.ImageModelFactory;
import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.ModelRole;
import dev.langchain4j.model.image.ImageModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorkflowImageGateway {
    private final RewriteWorkflowProperties properties;
    private final LangChain4jModelProperties modelProperties;
    private final Map<String, ImageModel> imageModels;
    private final ImageModelFactory factory;
    private final Map<String, Optional<ImageModel>> cache = new ConcurrentHashMap<>();

    public WorkflowImageGateway(RewriteWorkflowProperties properties,
                                LangChain4jModelProperties modelProperties,
                                Map<String, ImageModel> imageModels) {
        this.properties = properties;
        this.modelProperties = modelProperties;
        this.imageModels = imageModels;
        this.factory = new ImageModelFactory(modelProperties);
    }

    /** 优先使用独立 image 配置；未启用时兼容既有的角色生图路由。 */
    public Optional<ImageModel> model(ModelRole role) {
        Optional<ImageModel> dedicated = cache.computeIfAbsent("image", factory::create);
        if (dedicated.isPresent()) return dedicated;
        String chatBean = properties.getModelByRole().get(role);
        String effectiveBean = StringUtils.hasText(chatBean) ? chatBean : "chatLanguageModel";
        String imageBean = properties.getImageModelByChatModel().get(effectiveBean);
        String provider = properties.getProviderByRole().get(role);
        if (StringUtils.hasText(chatBean) || !StringUtils.hasText(provider)) {
            if (StringUtils.hasText(imageBean)) {
                ImageModel model = imageModels.get(imageBean);
                if (model == null) throw new WorkflowExecutionException("分镜图模型 Bean 不存在: " + imageBean);
                return Optional.of(model);
            }
            if (StringUtils.hasText(chatBean) && !"chatLanguageModel".equals(chatBean)) return Optional.empty();
            provider = modelProperties.getChat().resolvedProvider();
        }
        return cache.computeIfAbsent(provider, factory::create);
    }
}
