package com.melon.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 统一视图控制器，处理所有页面的请求
 */
@Controller
public class ViewController {

    /**
     * 显示主页
     *
     * @return 视图名称
     */
    @GetMapping("/")
    public String showHomePage() {
        return "chat";
    }
    
    /**
     * 显示AI聊天页面
     *
     * @return 视图名称
     */
    @GetMapping("/chat")
    public String showAIChatPage() {
        return "chat";
    }

    /**
     * 显示 DAG 工作流可视化设计器。
     *
     * @return 工作流设计器视图名称
     */
    @GetMapping("/workflows")
    public String showWorkflowDesigner() {
        return "workflow";
    }

    /** 知识库、文档版本与检索调试工作台。 */
    @GetMapping("/knowledge")
    public String showKnowledge() {
        return "knowledge";
    }
}
