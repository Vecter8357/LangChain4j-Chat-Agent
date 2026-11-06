package com.melon.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.util.*;

/** 候选重排：默认可解释的词项覆盖/短语算法；可切换远程跨编码器模型，不伪报模型评分。 */
@Component
public class KnowledgeReranker {
    private final KnowledgeProperties properties;
    private final ObjectMapper mapper;
    /** 保存重排供应商配置和请求响应使用的 JSON 映射器。 */
    public KnowledgeReranker(KnowledgeProperties properties,ObjectMapper mapper){this.properties=properties;this.mapper=mapper;}
    public record Ranking(Map<String,Double> scores,String provider,List<String> warnings) { }

    /** 选择远程或本地重排，远程请求失败时带警告回退到本地评分。 */
    public Ranking rank(String query,List<KnowledgeModels.Chunk> candidates) {
        if("remote".equalsIgnoreCase(properties.getRerank().getProvider())) {
            try{return remote(query,candidates);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Rerank 被中断",e);}
            catch(Exception e){return local(query,candidates,List.of("远程 Rerank 不可用，本次回退本地词项重排"));}
        }
        return local(query,candidates,List.of());
    }
    /** 调用配置的远程重排端点，校验每个候选的索引、分数和结果完整性。 */
    private Ranking remote(String query,List<KnowledgeModels.Chunk> candidates) throws Exception {
        var config=properties.getRerank();
        if(config.getUrl()==null || config.getUrl().isBlank()) throw new IllegalArgumentException("未配置 rerank URL");
        var payload=Map.of("model",config.getModel(),"query",query,"documents",candidates.stream().map(KnowledgeModels.Chunk::content).toList(),"top_n",candidates.size());
        var builder=HttpRequest.newBuilder(URI.create(config.getUrl())).timeout(config.getTimeout())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if(config.getApiKey()!=null && !config.getApiKey().isBlank())builder.header("Authorization","Bearer "+config.getApiKey());
        try(HttpClient client=HttpClient.newBuilder().connectTimeout(config.getTimeout()).build()) {
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("Rerank HTTP "+response.statusCode());
            JsonNode result=mapper.readTree(response.body()).path("results");
            if(!result.isArray()) throw new IllegalStateException("Rerank 缺少 results");
            Map<String,Double> scores=new HashMap<>();
            for(JsonNode item:result){
                int index=item.path("index").asInt(-1);double score=item.path("relevance_score").asDouble(Double.NaN);
                if(index<0 || index>=candidates.size() || !Double.isFinite(score) || scores.put(candidates.get(index).id(),score)!=null)
                    throw new IllegalStateException("Rerank 返回了非法索引或分数");
            }
            if(scores.size()!=candidates.size())throw new IllegalStateException("Rerank 返回候选不完整");
            return new Ranking(scores,"remote:"+config.getModel(),List.of());
        }
    }
    /** 按查询词项覆盖率和完整短语命中为分块评分。 */
    private Ranking local(String query,List<KnowledgeModels.Chunk> candidates,List<String> warnings) {
        Set<String> terms=tokens(query);Map<String,Double> scores=new HashMap<>();
        for(var candidate:candidates){
            String text=candidate.content().toLowerCase(Locale.ROOT);Set<String> document=tokens(text);
            long matched=terms.stream().filter(document::contains).count();
            double coverage=terms.isEmpty()?0:(double)matched/terms.size();
            double phrase=text.contains(query.toLowerCase(Locale.ROOT).trim())?1:0;
            scores.put(candidate.id(),coverage*0.8+phrase*0.2);
        }
        return new Ranking(scores,"local:term-coverage-v1",warnings);
    }
    /** 中文使用二元字组，拉丁字母与数字按词处理，避免依赖分词服务。 */
    static Set<String> tokens(String text) {
        Set<String> result=new HashSet<>();
        var words=java.util.regex.Pattern.compile("[a-z0-9]+|[\\p{IsHan}]+",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text.toLowerCase(Locale.ROOT));
        while(words.find()){
            String word=words.group();
            if(word.codePoints().allMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)){
                if(word.length()==1)result.add(word);
                for(int i=0;i<word.length()-1;i++)result.add(word.substring(i,i+2));
            }else result.add(word);
        }
        return result;
    }
}
