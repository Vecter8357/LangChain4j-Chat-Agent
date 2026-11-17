package com.melon.workflow;

public class WorkflowExecutionException extends RuntimeException {

    /** 创建只有可读错误消息的工作流异常。 */
    public WorkflowExecutionException(String message) {
        super(message);
    }

    /** 创建保留原始异常原因的工作流异常。 */
    public WorkflowExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
