package com.melon.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 证据范围是会话不变量，防止历史答案和任务结果绕过本轮知识库/附件选择。 */
public final class MemoryEvidenceScope {
    /** 将会话证据键计算保留为静态操作。 */
    private MemoryEvidenceScope() { }
    /** 对归一化后的知识库与附件 ID 集合计算稳定的会话证据键。 */
    public static String key(List<String> bases,List<String> files) {
        String input="bases="+normalized(bases)+";files="+normalized(files);
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    /** 校验资源 ID，去重并排序，使证据键与请求顺序无关。 */
    private static List<String> normalized(List<String> ids) {
        if(ids==null)return List.of();
        return ids.stream().map(id->{if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("资源 ID 无效");return id;}).distinct().sorted().toList();
    }
}
