package com.melon.knowledge;

import com.melon.knowledge.KnowledgeModels.*;
import com.melon.config.properties.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** Qdrant 稠密召回 + MySQL ngram 全文召回，RRF 融合后重排，最终再次检查文档可见性。 */
@Service
public class KnowledgeSearchService {
    private final KnowledgeRepository repository;
    private final KnowledgeService service;
    private final EmbeddingModel embedding;
    private final EmbeddingStore<TextSegment> store;
    private final KnowledgeProperties properties;
    private final KnowledgeReranker reranker;
    private final RagProperties rag;
    /** 装配全文查询、向量召回、重排及可见索引校验所需的依赖。 */
    public KnowledgeSearchService(KnowledgeRepository repository,KnowledgeService service,EmbeddingModel embedding,
                                  EmbeddingStore<TextSegment> store,KnowledgeProperties properties,KnowledgeReranker reranker,RagProperties rag) {
        this.repository=repository;this.service=service;this.embedding=embedding;this.store=store;this.properties=properties;this.reranker=reranker;this.rag=rag;
    }
    /** 在选定知识库及可选文件范围内融合全文和向量召回，重排后重新核验索引可见性。 */
    public SearchResult search(SearchRequest request) {
        if(request==null || request.question()==null || request.question().isBlank() || request.question().length()>2000)
            throw new IllegalArgumentException("检索问题不能为空且不得超过2000字");
        if(request.knowledgeBaseIds()==null || request.knowledgeBaseIds().isEmpty() || request.knowledgeBaseIds().size()>20)
            throw new IllegalArgumentException("必须指定1到20个知识库");
        if(request.fileIds()!=null && request.fileIds().size()>200)throw new IllegalArgumentException("文件过滤最多200个");
        int limit=request.limit()==null?properties.getResultLimit():Math.max(1,Math.min(20,request.limit()));
        List<Index> indexes=repository.activeIndexes(request.knowledgeBaseIds(),request.fileIds());
        List<String> warnings=new ArrayList<>();
        if(indexes.isEmpty())return new SearchResult("hybrid","none",List.of(),List.of("所选范围尚无可检索的已完成索引"));
        if(indexes.size()>2000)throw new IllegalArgumentException("检索范围过大，请指定文件缩小范围");
        Map<String,Index> indexById=indexes.stream().collect(Collectors.toMap(Index::id,Function.identity()));
        List<Chunk> lexical=repository.lexical(request.question(),new ArrayList<>(indexById.keySet()),properties.getRecallLimit());
        Map<String,Double> denseScores=new HashMap<>();List<String> denseIds=new ArrayList<>();
        List<String> compatible=indexes.stream().filter(i->i.embeddingVersion().equals(service.embeddingVersion())
                && i.collectionName().equals(rag.getVectorStore().getQdrant().getCollectionName())).map(Index::id).toList();
        if(compatible.size()!=indexes.size())warnings.add("部分文档的 Embedding 版本不同，仅参与全文召回；请重建索引");
        if(!compatible.isEmpty()){
            try {
                var query=embedding.embed(request.question()).content();
                var filter=metadataKey("scope").isEqualTo("knowledge")
                        .and(metadataKey("kb_id").isIn(request.knowledgeBaseIds()))
                        .and(metadataKey("index_id").isIn(compatible));
                if(request.fileIds()!=null && !request.fileIds().isEmpty())filter=filter.and(metadataKey("kb_document_id").isIn(request.fileIds()));
                for(var match:store.search(EmbeddingSearchRequest.builder().queryEmbedding(query).maxResults(properties.getRecallLimit()).filter(filter).build()).matches()){
                    denseIds.add(match.embeddingId());denseScores.put(match.embeddingId(),match.score());
                }
            } catch(Exception error){warnings.add("向量召回不可用，本次仅使用 MySQL 全文召回");}
        }
        Map<String,Chunk> candidates=new LinkedHashMap<>();
        lexical.forEach(c->candidates.put(c.id(),c));
        repository.byIds(denseIds).stream().filter(c->indexById.containsKey(c.indexId())).forEach(c->candidates.put(c.id(),c));
        Map<String,Double> fusion=new HashMap<>(),lexicalRanks=new HashMap<>();
        for(int n=0;n<lexical.size();n++){String id=lexical.get(n).id();fusion.merge(id,1.0/(61+n),Double::sum);lexicalRanks.put(id,1.0/(n+1));}
        for(int n=0;n<denseIds.size();n++)fusion.merge(denseIds.get(n),1.0/(61+n),Double::sum);
        var ordered=candidates.values().stream().sorted(Comparator.<Chunk>comparingDouble(c->fusion.getOrDefault(c.id(),0.0)).reversed().thenComparing(Chunk::id)).toList();
        if(ordered.isEmpty())return new SearchResult("hybrid","none",List.of(),warnings);
        var ranking=reranker.rank(request.question(),ordered);warnings.addAll(ranking.warnings());
        Set<String> visible=repository.activeIndexes(request.knowledgeBaseIds(),request.fileIds()).stream().map(Index::id).collect(Collectors.toSet());
        List<Citation> citations=ordered.stream().filter(c->visible.contains(c.indexId()))
                .sorted(Comparator.<Chunk>comparingDouble(c->ranking.scores().getOrDefault(c.id(),0.0)).reversed()
                        .thenComparing(Comparator.<Chunk>comparingDouble(c->fusion.getOrDefault(c.id(),0.0)).reversed()).thenComparing(Chunk::id))
                .limit(limit).map(c->{
                    Index index=indexById.get(c.indexId());
                    Version version=repository.version(c.documentId(),c.documentVersion());
                    return new Citation(c.id(),c.baseId(),c.documentId(),version.name(),c.documentVersion(),c.indexId(),index.chunkVersion(),index.embeddingVersion(),c.ordinal(),c.content(),
                            "/knowledge?chunk="+c.id(),denseScores.getOrDefault(c.id(),0.0),lexicalRanks.getOrDefault(c.id(),0.0),fusion.getOrDefault(c.id(),0.0),ranking.scores().getOrDefault(c.id(),0.0));
                }).toList();
        return new SearchResult("hybrid",ranking.provider(),citations,warnings);
    }
    /** 为普通对话生成可追溯的引用上下文，链接来自受控元数据而非模型猜测。 */
    public String context(SearchResult result) {
        StringBuilder text=new StringBuilder("知识库检索方式："+result.retrievalMode()+"；重排："+result.reranker()+"\n");
        result.warnings().forEach(w->text.append("提示：").append(w).append('\n'));
        for(Citation citation:result.citations())text.append("\n[来源：").append(citation.documentName()).append(" · 文档v").append(citation.documentVersion())
                .append(" · 分块").append(citation.ordinal()+1).append("](").append(citation.sourceUrl()).append(")\n")
                .append(citation.text()).append('\n');
        if(result.citations().isEmpty())text.append("没有相关来源。请明确告知用户，不能编造知识库事实。\n");
        return text.toString();
    }
}
