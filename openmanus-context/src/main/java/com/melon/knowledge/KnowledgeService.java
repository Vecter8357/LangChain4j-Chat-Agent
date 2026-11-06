package com.melon.knowledge;

import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RagProperties;
import com.melon.entity.UploadFile;
import com.melon.knowledge.KnowledgeModels.*;
import com.melon.service.DoclingDocumentProcessor;
import com.melon.service.MinioObjectStorageService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.InputStreamSource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** 文档版本、索引构建和外部对象清理。版本提交和可见性由 MySQL 控制。 */
@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final MinioObjectStorageService storage;
    private final DoclingDocumentProcessor parser;
    private final EmbeddingModel embedding;
    private final EmbeddingStore<TextSegment> vectors;
    private final RagProperties rag;
    private final KnowledgeProperties properties;
    private final LangChain4jModelProperties models;
    private final long maxSize;
    private final Set<String> extensions;

    /** 装配文档原文存储、解析、索引以及上传格式和大小限制。 */
    public KnowledgeService(KnowledgeRepository repository,MinioObjectStorageService storage,
                            DoclingDocumentProcessor parser,EmbeddingModel embedding,
                            EmbeddingStore<TextSegment> vectors,RagProperties rag,KnowledgeProperties properties,
                            LangChain4jModelProperties models,@Value("${file.upload.max-size}") long maxSize,
                            @Value("${file.upload.allowed-types}") String extensions) {
        this.repository=repository;this.storage=storage;this.parser=parser;this.embedding=embedding;
        this.vectors=vectors;this.rag=rag;this.properties=properties;this.models=models;this.maxSize=maxSize;
        this.extensions=new HashSet<>(Arrays.asList(extensions.toLowerCase(Locale.ROOT).split(",")));
    }

    /** 列出尚未删除的知识库。 */
    public List<Base> bases() { return repository.bases(); }

    /** 创建知识库并返回持久化记录。 */
    public Base createBase(String name, String description) { return repository.createBase(name, description); }

    /** 确认知识库存在后提交异步删除。 */
    public void deleteBase(String baseId) {
        repository.base(baseId);
        repository.requestDeleteBase(baseId);
    }

    /** 在确认知识库存在后列出其中的文档。 */
    public List<Doc> documents(String baseId) {
        repository.base(baseId);
        return repository.documents(baseId);
    }

    /** 验证文档属于可访问的知识库，再提交新原文版本。 */
    public Doc replace(String baseId, String documentId, MultipartFile file) throws IOException {
        requireDoc(baseId, documentId);
        return upload(baseId, documentId, file);
    }

    /** 校验文档归属和可访问性后提交索引重建。 */
    public Doc rebuild(String baseId, String documentId) {
        requireDoc(baseId, documentId);
        return rebuild(documentId);
    }

    /** 校验文档归属后提交删除，允许重新提交删除失败的文档。 */
    public void requestDelete(String baseId, String documentId) {
        repository.base(baseId);
        if (!repository.document(documentId).baseId().equals(baseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        repository.requestDelete(documentId);
    }

    /** 校验文档可访问性后返回原文版本和索引版本。 */
    public Map<String, Object> versions(String baseId, String documentId) {
        requireDoc(baseId, documentId);
        return Map.of("documents", repository.versions(documentId), "indexes", repository.indexes(documentId));
    }

    /** 校验索引归属后读取其分块，省略索引时读取当前生效索引。 */
    public List<Chunk> chunks(String baseId, String documentId, String indexId) {
        Doc doc = requireDoc(baseId, documentId);
        String selected = indexId == null ? doc.activeIndexId() : indexId;
        if (selected == null) return List.of();
        if (!repository.index(selected).documentId().equals(documentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return repository.chunkList(selected);
    }

    /** 根据分块 ID 读取引用详情，并校验源文档仍然可访问。 */
    public Map<String, Object> chunk(String chunkId) {
        Chunk chunk = repository.byIds(List.of(chunkId)).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireDoc(chunk.baseId(), chunk.documentId());
        return Map.of("chunk", chunk, "index", repository.index(chunk.indexId()),
                "version", repository.version(chunk.documentId(), chunk.documentVersion()));
    }

    public record DocumentContent(String name, long size, InputStreamSource stream) { }

    /** 解析请求的原文版本并返回文件名、长度和延迟打开的读取入口。 */
    public DocumentContent content(String baseId, String documentId, Integer version) {
        Doc doc = requireDoc(baseId, documentId);
        Version source = repository.version(documentId, version == null ? doc.documentVersion() : version);
        return new DocumentContent(source.name(), source.size(), () -> storage.openObject(source.bucket(), source.objectKey()));
    }

    /** 确认文档属于指定的有效知识库，且其状态允许读取。 */
    private Doc requireDoc(String baseId, String documentId) {
        Doc doc = repository.document(documentId);
        Base base = repository.base(baseId);
        if (!doc.baseId().equals(baseId) || !"ACTIVE".equals(base.status())
                || Set.of("DELETING", "DELETE_FAILED", "DELETED").contains(doc.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不可访问");
        }
        return doc;
    }

    /**
     * 校验文件，预留原文与待处理索引记录，并写入对象存储。
     * 上传请求返回持久化任务，原文解析及向量化由后台执行。
     */
    public Doc upload(String baseId,String existingId,MultipartFile file) throws IOException {
        if(!"ACTIVE".equals(repository.base(baseId).status())) throw new IllegalArgumentException("知识库不可写");
        if(file==null || file.isEmpty() || file.getSize()>maxSize) throw new IllegalArgumentException("文件为空或超过大小限制");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1);
        String ext=extension(name);
        if(name.length()>255 || name.contains("..") || !extensions.contains(ext)) throw new IllegalArgumentException("不支持的文件名或类型");
        String id=existingId==null?UUID.randomUUID().toString():existingId;
        int revision=1;
        if(existingId!=null) {
            Doc current=repository.document(id);
            if(!current.baseId().equals(baseId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不属于该知识库");
            revision=current.documentVersion()+1;
        }
        byte[] bytes=file.getBytes();
        Version version=new Version(id,revision,name,storage.bucket(),"knowledge/"+baseId+"/"+id+"/"+UUID.randomUUID()+"."+ext,
                file.getContentType()==null?"application/octet-stream":file.getContentType(),bytes.length,sha(bytes),null);
        Index index=newIndex(id,baseId,revision);
        repository.reserve(version,index,existingId==null);
        try {
            storage.putObject(version.objectKey(),new ByteArrayInputStream(bytes),bytes.length,version.contentType());
            repository.uploaded(id,index.id());
        } catch(RuntimeException error) {
            // 保留版本和对象位置；上传结果不确定时也可通过删除任务清理。
            repository.uploadFailed(id,"上传失败，可重试上传或删除文档");
            throw error;
        } finally {
            // 上传与删除共用文档租约，防止对象刚被删除又被尚未完成的上传写回。
            repository.release(id,index.id());
        }
        return repository.document(id);
    }

    /** 为当前原文版本创建新的待处理索引。 */
    public Doc rebuild(String id) {
        Doc doc=repository.document(id);
        if(!"ACTIVE".equals(repository.base(doc.baseId()).status())) throw new IllegalArgumentException("知识库不可写");
        repository.rebuild(newIndex(id,doc.baseId(),doc.documentVersion()));
        return repository.document(id);
    }

    /** Embedding 版本包含提供方、地址、模型名和显式修订号；不包含 API Key。 */
    public String embeddingVersion() {
        var config=models.getEmbedding();
        return sha((config.resolvedProvider()+"|"+config.resolvedBaseUrl()+"|"+config.resolvedModelName()+"|"+properties.getEmbeddingRevision())
                .getBytes(StandardCharsets.UTF_8));
    }
    /** 根据当前分块规则、Embedding 配置和 Collection 生成索引记录。 */
    private Index newIndex(String docId,String baseId,int version) {
        var split=rag.getSplitter();var q=rag.getVectorStore().getQdrant();
        return new Index(UUID.randomUUID().toString(),docId,baseId,version,"QUEUED",
                "recursive-v1:"+split.getMaxSegmentSize()+":"+split.getMaxOverlapSize(),split.getMaxSegmentSize(),split.getMaxOverlapSize(),
                models.getEmbedding().resolvedModelName(),embeddingVersion(),q.getDimension(),q.getCollectionName(),null,null);
    }

    /** 后台任务分批写入新索引，所有批次完成后原子切换 active_index_id。 */
    public void build(Doc doc,String owner) throws IOException {
        Index index=repository.index(doc.pendingIndexId());
        if(!index.embeddingVersion().equals(embeddingVersion()) || !index.collectionName().equals(rag.getVectorStore().getQdrant().getCollectionName()))
            throw new IllegalStateException("任务模型配置已变化，请重建索引");
        Version version=repository.version(doc.id(),index.documentVersion());
        Document parsed;
        try(var source=storage.materialize(version.bucket(),version.objectKey(),extension(version.name()))) {
            String ext=extension(version.name());
            if(Set.of("txt","md").contains(ext)) {
                try(InputStream input=java.nio.file.Files.newInputStream(source.path())) {parsed=new TextDocumentParser().parse(input);}
            } else {
                UploadFile metadata=UploadFile.builder().id(doc.id()).originalFileName(version.name()).size(version.size()).build();
                var converted=parser.convertToDocument(metadata,source.path(),ext);
                if(converted.isPresent()) parsed=converted.get();
                else if(parser.shouldFallbackToNativeParser() && "pdf".equals(ext)) {
                    try(InputStream input=java.nio.file.Files.newInputStream(source.path())) {parsed=new ApachePdfBoxDocumentParser().parse(input);}
                } else throw new IllegalStateException("文档解析失败，请检查 Docling 或更换文档格式");
            }
        }
        List<TextSegment> segments=DocumentSplitters.recursive(index.chunkSize(),index.chunkOverlap()).split(parsed);
        if(segments.isEmpty()) throw new IllegalStateException("文档无可索引内容");
        checkLease(doc.id(),owner);
        vectors.removeAll(metadataKey("scope").isEqualTo("knowledge").and(metadataKey("index_id").isEqualTo(index.id())));
        List<Chunk> chunks=new ArrayList<>();
        for(int n=0;n<segments.size();n++) {
            String chunkId=UUID.nameUUIDFromBytes((index.id()+":"+n).getBytes(StandardCharsets.UTF_8)).toString();
            chunks.add(new Chunk(chunkId,index.id(),doc.id(),doc.baseId(),index.documentVersion(),n,segments.get(n).text()));
        }
        repository.chunks(index.id(),chunks);
        for(int start=0;start<chunks.size();start+=16) {
            checkLease(doc.id(),owner);
            var batch=chunks.subList(start,Math.min(start+16,chunks.size()));
            var texts=batch.stream().map(c->TextSegment.from(c.content(),new Metadata().put("scope","knowledge")
                    .put("kb_id",doc.baseId()).put("kb_document_id",doc.id()).put("index_id",index.id()).put("chunk_id",c.id()))).toList();
            var embedded=embedding.embedAll(texts).content();
            if(embedded.size()!=texts.size() || embedded.stream().anyMatch(e->e.dimension()!=index.dimension()))
                throw new IllegalStateException("Embedding 数量或维度与索引配置不匹配");
            checkLease(doc.id(),owner);
            vectors.addAll(batch.stream().map(Chunk::id).toList(),embedded,texts);
        }
        checkLease(doc.id(),owner);
        repository.activate(doc.id(),index.id(),owner);
    }

    /** 在执行索引或删除操作前检查文档任务租约。 */
    private void checkLease(String id,String owner) {
        if(!repository.owns(id,owner)) throw new IllegalStateException("任务租约已过期");
        if(!"PROCESSING".equals(repository.document(id).status())) throw new IllegalStateException("索引任务已取消");
    }

    /** 清理全部历史索引的向量，再清理所有原文版本；失败保留状态以供重试。 */
    public void delete(Doc doc,String owner) {
        for(String collection:repository.indexes(doc.id()).stream().map(Index::collectionName).distinct().toList()) {
            if(!repository.owns(doc.id(),owner)) throw new IllegalStateException("删除租约已过期");
            if(collection.equals(rag.getVectorStore().getQdrant().getCollectionName())) {
                vectors.removeAll(metadataKey("scope").isEqualTo("knowledge").and(metadataKey("kb_document_id").isEqualTo(doc.id())));
            } else {
                var config=rag.getVectorStore().getQdrant();
                var builder=QdrantGrpcClient.newBuilder(config.getHost(),config.getPort(),config.isUseTls()).withTimeout(config.getTimeout());
                if(config.getApiKey()!=null && !config.getApiKey().isBlank()) builder.withApiKey(config.getApiKey());
                try(QdrantClient client=new QdrantClient(builder.build())) {
                    if(client.collectionExistsAsync(collection).get()) {
                        QdrantEmbeddingStore.builder().client(client).collectionName(collection).build()
                                .removeAll(metadataKey("scope").isEqualTo("knowledge").and(metadataKey("kb_document_id").isEqualTo(doc.id())));
                    }
                } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                catch(Exception e){throw new IllegalStateException("历史向量清理失败",e);}
            }
        }
        for(Version version:repository.versions(doc.id())) {
            if(!repository.owns(doc.id(),owner)) throw new IllegalStateException("删除租约已过期");
            storage.removeObject(version.bucket(),version.objectKey());
        }
        repository.deleted(doc.id(),owner);
    }
    /** 计算原文字节的 SHA-256 十六进制摘要。 */
    public static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    /** 从文件名提取小写扩展名。 */
    private String extension(String name) {return name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);}
}
