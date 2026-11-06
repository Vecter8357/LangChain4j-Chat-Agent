package com.melon.knowledge;

import com.melon.knowledge.KnowledgeModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 知识库管理、版本、文档生命周期、来源下载及检索调试接口。 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    private final KnowledgeSearchService search;
    private final Executor executor;
    private final KnowledgeChatService chat;
    /** 装配知识库管理、检索、问答服务及有界聊天线程池。 */
    public KnowledgeController(KnowledgeService service, KnowledgeSearchService search, KnowledgeChatService chat,
                               @Qualifier("knowledgeChatExecutor") Executor executor) {
        this.service=service;this.search=search;this.executor=executor;this.chat=chat;
    }
    /** 列出尚未删除的知识库。 */
    @GetMapping("/bases")
    public List<Base> bases(){return service.bases();}
    /** 校验名称和描述长度后创建知识库。 */
    @PostMapping("/bases")
    public Base create(@RequestBody Map<String,String> body){
        String name=Optional.ofNullable(body.get("name")).orElse("").trim();String description=Optional.ofNullable(body.get("description")).orElse("");
        if(name.isBlank() || name.length()>200 || description.length()>10000)throw new IllegalArgumentException("名称必填且不超过200字，描述不超过10000字");
        return service.createBase(name,description);
    }
    /** 提交异步知识库删除请求并返回删除中状态。 */
    @DeleteMapping("/bases/{baseId}")
    public ResponseEntity<?> deleteBase(@PathVariable String baseId){service.deleteBase(baseId);return ResponseEntity.accepted().body(Map.of("status","DELETING"));}
    /** 列出指定知识库中尚未删除的文档。 */
    @GetMapping("/bases/{baseId}/documents")
    public List<Doc> documents(@PathVariable String baseId){return service.documents(baseId);}
    /** 接收新文档并返回待处理的文档状态。 */
    @PostMapping("/bases/{baseId}/documents")
    public ResponseEntity<Doc> upload(@PathVariable String baseId,@RequestParam MultipartFile file) throws IOException {
        return ResponseEntity.accepted().body(service.upload(baseId,null,file));
    }
    /** 为指定知识库中的文档上传新版本并返回受理结果。 */
    @PostMapping("/bases/{baseId}/documents/{documentId}/versions")
    public ResponseEntity<Doc> replace(@PathVariable String baseId,@PathVariable String documentId,@RequestParam MultipartFile file) throws IOException {
        return ResponseEntity.accepted().body(service.replace(baseId,documentId,file));
    }
    /** 提交文档重新索引请求，并保留当前生效索引供检索使用。 */
    @PostMapping("/bases/{baseId}/documents/{documentId}/rebuild")
    public ResponseEntity<Doc> rebuild(@PathVariable String baseId,@PathVariable String documentId){return ResponseEntity.accepted().body(service.rebuild(baseId,documentId));}
    /** 提交指定文档的异步删除请求。 */
    @DeleteMapping("/bases/{baseId}/documents/{documentId}")
    public ResponseEntity<?> delete(@PathVariable String baseId,@PathVariable String documentId){
        service.requestDelete(baseId, documentId);
        return ResponseEntity.accepted().body(Map.of("status","DELETING"));
    }
    /** 查询文档原文和索引的版本记录。 */
    @GetMapping("/bases/{baseId}/documents/{documentId}/versions")
    public Map<String,Object> versions(@PathVariable String baseId,@PathVariable String documentId){return service.versions(baseId,documentId);}
    /** 预览指定索引的分块，省略索引 ID 时使用当前生效索引。 */
    @GetMapping("/bases/{baseId}/documents/{documentId}/chunks")
    public List<Chunk> chunks(@PathVariable String baseId,@PathVariable String documentId,@RequestParam(required=false) String indexId){
        return service.chunks(baseId, documentId, indexId);
    }
    /** 根据引用中的分块 ID 查询分块及其索引和文档版本。 */
    @GetMapping("/chunks/{chunkId}")
    public Map<String,Object> chunk(@PathVariable String chunkId){
        return service.chunk(chunkId);
    }
    /** 将指定版本的原文流式下载，省略版本号时使用当前文档版本。 */
    @GetMapping("/bases/{baseId}/documents/{documentId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String baseId,@PathVariable String documentId,@RequestParam(required=false) Integer version){
        var source = service.content(baseId, documentId, version);
        StreamingResponseBody body = output -> {
            try (var input = source.stream().getInputStream()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(source.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(source.name(),StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
    /** 在请求指定的知识库范围内执行混合检索并返回引用信息。 */
    @PostMapping("/search")
    public SearchResult search(@RequestBody SearchRequest request){return search.search(request);}

    public record ChatRequestBody(String input,List<String> knowledgeBaseIds,List<String> fileIds) { }
    /** 校验问题和来源范围，在知识问答线程池中执行并以 SSE 返回答案。 */
    @PostMapping(value="/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequestBody body){
        if(body.input()==null || body.input().isBlank() || body.input().length()>2000 || body.knowledgeBaseIds()==null
                || body.knowledgeBaseIds().isEmpty() || body.knowledgeBaseIds().size()>20 || (body.fileIds()!=null && body.fileIds().size()>3))
            throw new IllegalArgumentException("请指定知识库与有效问题");
        SseEmitter emitter = new SseEmitter(600000L);
        executor.execute(() -> {
            try {
                emitter.send(chat.answer(body.input(), body.knowledgeBaseIds(), body.fileIds(), List.of(), null));
                emitter.complete();
            } catch (Exception error) {
                try {
                    emitter.send("知识库问答失败，请检查文档状态与模型服务。");
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(error);
                }
            }
        });
        return emitter;
    }

    /** 将知识库接口的参数错误转换为带消息的 400 响应。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error){return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()==null?"参数错误":error.getMessage()));}
}
