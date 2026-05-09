package com.agenticnpc.dispatch;

/**
 * LLM 通信异常。
 * 由 CircuitBreaker 捕获并计入失败次数。
 */
public class LLMException extends RuntimeException {
    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }
}
