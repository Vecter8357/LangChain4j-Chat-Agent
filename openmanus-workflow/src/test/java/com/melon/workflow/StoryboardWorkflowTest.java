package com.melon.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.DirectionSelectionRequest;
import com.melon.workflow.model.RewriteOutputStyle;
import com.melon.workflow.model.RewriteWorkflowState;
import com.melon.workflow.model.RewriteWorkflowState.*;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.melon.workflow.model.ModelRole.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryboardWorkflowTest {
    private final WorkflowModelGateway models = mock(WorkflowModelGateway.class);
    private final WorkflowImageGateway images = mock(WorkflowImageGateway.class);
    private final ImageModel imageModel = mock(ImageModel.class);
    private final RewriteWorkflowRepository repository = mock(RewriteWorkflowRepository.class);
    private final RewriteWorkflowState state = new RewriteWorkflowState();

    @Test
    void dedicatedImageConfigurationGeneratesEveryShotThroughTheRealGateway() throws Exception {
        List<String> requestedModels = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            requestedModels.add(request.path("model").asText());
            prompts.add(request.path("prompt").asText());
            byte[] result = "{\"created\":1,\"data\":[{\"b64_json\":\"c3Rvcnlib2FyZA==\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
            exchange.close();
        });
        server.start();
        try {
            var config = new LangChain4jModelProperties();
            config.getChat().setProvider("ollama");
            var image = config.getChat().getImage();
            image.setEnabled(true);
            image.setApiKey("local-image-key");
            image.setModelName("dedicated-storyboard-model");
            image.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            prepare(RewriteOutputStyle.DRAMA_SCRIPT);

            run(new WorkflowImageGateway(new RewriteWorkflowProperties(), config, Map.of()));

            assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(requestedModels).containsExactly("dedicated-storyboard-model", "dedicated-storyboard-model");
            assertThat(prompts).hasSize(2);
            assertThat(prompts.getFirst()).contains("旧镜头一");
            assertThat(prompts.get(1)).contains("旧镜头二");
            assertThat(state.getStoryboardShots()).allSatisfy(shot -> {
                assertThat(shot.getImageStatus()).isEqualTo("COMPLETED");
                assertThat(shot.getImageUrl()).startsWith("data:image/png;base64,");
            });
            assertThat(mapper.readTree(mapper.writeValueAsString(state.getStoryboardShots())).get(1).path("imageUrl").asText())
                    .startsWith("data:image/png;base64,");
            verify(repository, atLeast(2)).save(state);
        } finally {
            server.stop(0);
        }
    }

    /** 显式启用才会请求真实图像服务；分析写稿和存储使用测试替身。 */
    @Test
    @EnabledIfSystemProperty(named = "rewrite.image.live-test", matches = "true")
    void generatesActualImagesForBothShotsUsingTheProjectConfiguration() throws Exception {
        Path configFile = Path.of(System.getProperty("rewrite.image.config",
                "../openmanus-app/src/main/resources/application.yml"));
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("project-image-config", new FileSystemResource(configFile))) {
            environment.getPropertySources().addLast(source);
        }
        var config = Binder.get(environment).bind("langchain4j", Bindable.of(LangChain4jModelProperties.class)).get();
        assertThat(config.getChat().getImage().isEnabled()).isTrue();
        prepare(RewriteOutputStyle.DRAMA_SCRIPT);
        when(models.json(eq(FINAL_EDITOR), anyString(), anyString(), eq(StoryboardDraft.class)))
                .thenReturn(draft("雨夜空旷街道，远景，路灯与红伞", "街道积水中的红伞近景，雨滴和倒影"));

        System.out.println("开始工作流真实出图验证，model=" + config.getChat().getImage().getModelName() + "，分镜数=2");
        run(new WorkflowImageGateway(new RewriteWorkflowProperties(), config, Map.of()));

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.getStoryboardShots()).hasSize(2);
        Path output = Path.of(System.getProperty("rewrite.image.output-directory", "../data/image-probe/workflow"));
        Files.createDirectories(output);
        for (StoryboardShot shot : state.getStoryboardShots()) {
            assertThat(shot.getImageStatus()).isEqualTo("COMPLETED");
            String url = shot.getImageUrl();
            byte[] content;
            if (url.startsWith("data:image/")) {
                content = Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
            } else {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(60)).GET().build();
                var response = java.net.http.HttpClient.newHttpClient().send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(200);
                content = response.body();
            }
            assertThat(content.length).isGreaterThan(100);
            String extension = content[0] == (byte) 0xff && content[1] == (byte) 0xd8 ? ".jpg"
                    : content[0] == 'R' && content[1] == 'I' ? ".webp" : ".png";
            Files.write(output.resolve("shot-" + shot.getNumber() + extension), content);
        }
        new ObjectMapper().writeValue(output.resolve("workflow-result.json").toFile(), Map.of(
                "workflowId", state.getWorkflowId(), "status", state.getStatus(),
                "currentNode", state.getCurrentNode(), "finalDraft", state.getFinalDraft(),
                "storyboardShots", state.getStoryboardShots()));
        System.out.println("已通过工作流出图流程生成两段真实分镜图，结果保存至 " + output.toAbsolutePath().normalize());
    }

    @Test
    void createsOneImageForEachShotAndPersistsResults() {
        prepare(RewriteOutputStyle.DRAMA_SCRIPT);
        when(images.model(FINAL_EDITOR)).thenReturn(Optional.of(imageModel));
        when(imageModel.generate(anyString())).thenReturn(
                Response.from(Image.builder().url("https://example.com/1.png").build()),
                Response.from(Image.builder().base64Data("aW1hZ2U=").mimeType("image/png").build()));

        run();

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.getFinalDraft()).contains("### 分镜 1", "旧镜头一", "### 分镜 2", "旧镜头二");
        assertThat(state.getStoryboardShots()).extracting(StoryboardShot::getImageStatus)
                .containsExactly("COMPLETED", "COMPLETED");
        assertThat(state.getStoryboardShots()).extracting(StoryboardShot::getImageUrl)
                .containsExactly("https://example.com/1.png", "data:image/png;base64,aW1hZ2U=");
        verify(imageModel, times(2)).generate(anyString());
        verify(repository, atLeast(2)).save(state);
    }

    @Test
    void skipsImagesWhenCurrentModelDoesNotSupportGeneration() {
        prepare(RewriteOutputStyle.DRAMA_SCRIPT);
        when(images.model(FINAL_EDITOR)).thenReturn(Optional.empty());

        run();

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.getStoryboardShots()).extracting(StoryboardShot::getImageStatus)
                .containsExactly("SKIPPED", "SKIPPED");
        assertThat(state.getStoryboardShots()).extracting(StoryboardShot::getImageUrl).containsOnlyNulls();
        verify(models, never()).text(eq(FINAL_EDITOR), anyString(), anyString());
        verify(repository, atLeast(2)).save(state);
        verifyNoInteractions(imageModel);
    }

    @Test
    void readsLegacySvgResultsWithoutRestoringSvgSupport() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StoryboardShot shot = mapper.readValue("""
                {"number":1,"script":"已有分镜剧本","imageSvg":"<svg/>","imageStatus":"COMPLETED"}
                """, StoryboardShot.class);
        assertThat(shot.getScript()).isEqualTo("已有分镜剧本");
        assertThat(mapper.writeValueAsString(shot)).doesNotContain("imageSvg", "<svg");
    }

    @Test
    void continuesAfterOneImageFailsAndKeepsScriptForReview() {
        prepare(RewriteOutputStyle.DRAMA_SCRIPT);
        when(images.model(FINAL_EDITOR)).thenReturn(Optional.of(imageModel));
        when(imageModel.generate(anyString())).thenThrow(new IllegalStateException("service unavailable"))
                .thenReturn(Response.from(Image.builder().url("https://example.com/2.png").build()));

        run();

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.NEEDS_REVIEW);
        assertThat(state.getFinalDraft()).contains("旧镜头一", "旧镜头二");
        assertThat(state.getStoryboardShots()).extracting(StoryboardShot::getImageStatus)
                .containsExactly("FAILED", "COMPLETED");
        assertThat(state.getStoryboardShots().getFirst().getImageError()).isNotBlank();
        verify(imageModel, times(2)).generate(anyString());
    }

    @Test
    void generatesImagesOnlyForRevisedFinalShots() {
        prepare(RewriteOutputStyle.DRAMA_SCRIPT);
        when(models.json(eq(QUALITY_REVIEWER), anyString(), anyString(), eq(QualityReport.class)))
                .thenReturn(quality(false), quality(true));
        when(models.json(eq(FINAL_EDITOR), anyString(), anyString(), eq(StoryboardDraft.class)))
                .thenReturn(draft("旧镜头一", "旧镜头二"), draft("重写后的镜头"));
        when(images.model(FINAL_EDITOR)).thenReturn(Optional.of(imageModel));
        when(imageModel.generate(anyString())).thenReturn(
                Response.from(Image.builder().url("https://example.com/revised.png").build()));

        run();

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.getFinalDraft()).contains("重写后的镜头").doesNotContain("旧镜头");
        assertThat(state.getStoryboardShots()).hasSize(1);
        verify(imageModel).generate(contains("重写后的镜头"));
        verify(imageModel, never()).generate(contains("旧镜头"));
    }

    @Test
    void leavesOtherGenresOnTextOnlyPath() {
        prepare(RewriteOutputStyle.FICTION);
        when(models.text(eq(FINAL_EDITOR), anyString(), anyString())).thenReturn("小说终稿");

        run();

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.getFinalDraft()).isEqualTo("小说终稿");
        assertThat(state.getStoryboardShots()).isEmpty();
        verifyNoInteractions(images, imageModel);
    }

    @Test
    void rejectsEmptyStoryboardInsteadOfCompletingWithoutImages() {
        prepare(RewriteOutputStyle.DRAMA_SCRIPT);
        when(models.json(eq(FINAL_EDITOR), anyString(), anyString(), eq(StoryboardDraft.class)))
                .thenReturn(new StoryboardDraft());

        run();

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(state.getFailureReason()).contains("未返回分镜头剧本");
        verifyNoInteractions(images);
    }

    private void prepare(RewriteOutputStyle style) {
        state.setWorkflowId("storyboard-test");
        state.setStatus(WorkflowStatus.AWAITING_DIRECTION_SELECTION);
        state.setOutputStyle(style);
        state.setAnalysis(new ProseAnalysis());
        RewriteDirection direction = new RewriteDirection();
        direction.setId("direction-1");
        state.setDirections(List.of(direction));
        when(repository.findById(state.getWorkflowId())).thenReturn(Optional.of(state));
        when(models.toJson(any())).thenReturn("{}");
        when(models.text(eq(DRAFT_WRITER), anyString(), anyString())).thenReturn("候选剧本");
        when(models.json(eq(CRITIC), anyString(), anyString(), eq(DraftReview.class))).thenReturn(new DraftReview());
        FinalSelection selection = new FinalSelection();
        selection.setCandidateId("draft-1");
        when(models.json(eq(CREATIVE_DIRECTOR), anyString(), anyString(), eq(FinalSelection.class))).thenReturn(selection);
        when(models.json(eq(FINAL_EDITOR), anyString(), anyString(), eq(StoryboardDraft.class)))
                .thenReturn(draft("旧镜头一", "旧镜头二"));
        when(models.json(eq(QUALITY_REVIEWER), anyString(), anyString(), eq(QualityReport.class)))
                .thenReturn(quality(true));
    }

    private void run() {
        run(images);
    }

    private void run(WorkflowImageGateway selectedImages) {
        var service = new RewriteWorkflowService(models, selectedImages, mock(McpResearchGateway.class), repository,
                new RewriteWorkflowProperties(), Runnable::run);
        DirectionSelectionRequest request = new DirectionSelectionRequest();
        request.setDirectionIds(List.of("direction-1"));
        service.selectDirections(state.getWorkflowId(), request);
    }

    private StoryboardDraft draft(String... scripts) {
        StoryboardDraft draft = new StoryboardDraft();
        for (String script : scripts) {
            StoryboardShot shot = new StoryboardShot();
            shot.setTitle(script);
            shot.setScript(script);
            shot.setImagePrompt("雨夜街景：" + script);
            // 模型声称已出图的字段必须被程序丢弃。
            shot.setImageUrl("https://example.com/fabricated.png");
            shot.setImageStatus("COMPLETED");
            draft.getShots().add(shot);
        }
        return draft;
    }

    private QualityReport quality(boolean passed) {
        QualityReport report = new QualityReport();
        report.setPassed(passed);
        report.setOverallScore(90);
        report.setGenreComplianceScore(90);
        return report;
    }
}
