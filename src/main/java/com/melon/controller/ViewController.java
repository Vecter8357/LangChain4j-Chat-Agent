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
} 