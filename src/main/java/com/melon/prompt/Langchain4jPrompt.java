package com.melon.prompt;

/**
 * @author: melon
 * @description: TODO
 * @date: 2025/3/18 09:26
 */
public class Langchain4jPrompt {
    // 大模型进行更复杂的分类
    public static final String QUESTION_CATEGORY =
            """
        请分析以下用户输入，并确定应该使用哪种工具处理。只返回工具名称，不要有其他内容。

        可选的工具有：
        1. github - 用于处理GitHub相关查询，如仓库信息、提交历史等
        2. fetch - 用于获取网页内容并回答问题
        3. filesystem - 用于处理文件系统操作，如列出目录、读写文件等
        4. other - 其他类型

        用户输入：%s

        工具名称：
        """;

    // 大模型分析用户意图
    public static final String USER_INTENT =
            """
        请分析以下用户输入，并确定用户想要执行的文件系统操作。

        可选的操作有：
        1. list - 列出目录内容
        2. read - 读取文件内容
        3. write - 写入文件内容
        4. delete - 删除文件或目录
        5. search - 搜索文件
        6. info - 获取文件系统信息

        用户输入：%s

        请返回操作类型和相关参数，格式为：操作类型|参数1|参数2...
        """;

    // mcp返回整理
    public static final String MCP_RESULT =
            """
        基于以下网页内容回答问题：
        
        %s

        问题：%s
        
        """;
    
    // RAG文档查询问答
    public static final String RAG_QUERY =
            """
        你是一个专业的文档问答助手。请基于提供的文档内容，准确回答用户的问题。
        如果文档内容中没有足够的信息来回答问题，请明确指出，不要编造信息。
        在回答时，引用相关的文档段落，并解释你的推理过程。
        文档内容: %s
        
        问题：%s
        
        """;
}
