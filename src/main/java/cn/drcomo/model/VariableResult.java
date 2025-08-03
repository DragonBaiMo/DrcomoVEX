package cn.drcomo.model;

import java.util.Objects;

/**
 * 变量操作结果类
 * 
 * 封装所有变量操作的结果信息，包括成功状态、返回值、错误信息等。
 * 支持链式调用和函数式编程风格。
 * 
 * @author BaiMo
 */
public class VariableResult {
    
    private final boolean success;
    private final String value;
    private final String errorMessage;
    private final String errorCode;
    private final Throwable throwable;
    private final long timestamp;
    
    // 操作元数据
    private final String operation;
    private final String variableKey;
    private final String targetPlayer;
    
    private VariableResult(Builder builder) {
        this.success = builder.success;
        this.value = builder.value;
        this.errorMessage = builder.errorMessage;
        this.errorCode = builder.errorCode;
        this.throwable = builder.throwable;
        this.timestamp = builder.timestamp;
        this.operation = builder.operation;
        this.variableKey = builder.variableKey;
        this.targetPlayer = builder.targetPlayer;
    }
    
    // Getter 方法
    public boolean isSuccess() {
        return success;
    }
    
    public boolean isFailure() {
        return !success;
    }
    
    public String getValue() {
        return value;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public Throwable getThrowable() {
        return throwable;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public String getVariableKey() {
        return variableKey;
    }
    
    public String getTargetPlayer() {
        return targetPlayer;
    }
    
    /**
     * 获取值，如果失败则返回默认值
     */
    public String getValueOrDefault(String defaultValue) {
        return success ? value : defaultValue;
    }
    
    /**
     * 获取值，如果失败则抛出异常
     */
    public String getValueOrThrow() {
        if (success) {
            return value;
        }
        throw new RuntimeException(errorMessage, throwable);
    }
    
    /**
     * 检查是否包含有效值
     */
    public boolean hasValue() {
        return success && value != null;
    }
    
    /**
     * 检查是否包含异常信息
     */
    public boolean hasThrowable() {
        return throwable != null;
    }
    
    /**
     * 获取详细的错误信息
     */
    public String getDetailedErrorMessage() {
        if (success) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        
        if (errorCode != null) {
            sb.append("[").append(errorCode).append("] ");
        }
        
        if (errorMessage != null) {
            sb.append(errorMessage);
        }
        
        if (throwable != null) {
            sb.append(" - ").append(throwable.getClass().getSimpleName());
            if (throwable.getMessage() != null) {
                sb.append(": ").append(throwable.getMessage());
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 创建新的结果，保留元数据但更改值
     */
    public VariableResult withValue(String newValue) {
        return new Builder()
                .success(true)
                .value(newValue)
                .operation(operation)
                .variableKey(variableKey)
                .targetPlayer(targetPlayer)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 创建新的错误结果，保留元数据
     */
    public VariableResult withError(String errorMessage) {
        return new Builder()
                .failure(errorMessage)
                .operation(operation)
                .variableKey(variableKey)
                .targetPlayer(targetPlayer)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VariableResult that = (VariableResult) o;
        return success == that.success &&
               timestamp == that.timestamp &&
               Objects.equals(value, that.value) &&
               Objects.equals(errorMessage, that.errorMessage) &&
               Objects.equals(errorCode, that.errorCode) &&
               Objects.equals(operation, that.operation) &&
               Objects.equals(variableKey, that.variableKey) &&
               Objects.equals(targetPlayer, that.targetPlayer);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(success, value, errorMessage, errorCode, 
                           timestamp, operation, variableKey, targetPlayer);
    }
    
    @Override
    public String toString() {
        if (success) {
            return "VariableResult{success=true, value='" + value + "', operation='" + operation + "'}";
        } else {
            return "VariableResult{success=false, error='" + errorMessage + "', operation='" + operation + "'}";
        }
    }
    
    /**
     * 结果构建器
     */
    public static class Builder {
        private boolean success = false;
        private String value;
        private String errorMessage;
        private String errorCode;
        private Throwable throwable;
        private long timestamp = System.currentTimeMillis();
        private String operation;
        private String variableKey;
        private String targetPlayer;
        
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }
        
        public Builder value(String value) {
            this.value = value;
            this.success = true;
            return this;
        }
        
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            this.success = false;
            return this;
        }
        
        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }
        
        public Builder throwable(Throwable throwable) {
            this.throwable = throwable;
            this.success = false;
            return this;
        }
        
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }
        
        public Builder variableKey(String variableKey) {
            this.variableKey = variableKey;
            return this;
        }
        
        public Builder targetPlayer(String targetPlayer) {
            this.targetPlayer = targetPlayer;
            return this;
        }
        
        public Builder failure(String errorMessage) {
            this.success = false;
            this.errorMessage = errorMessage;
            return this;
        }
        
        public Builder failure(String errorMessage, Throwable throwable) {
            this.success = false;
            this.errorMessage = errorMessage;
            this.throwable = throwable;
            return this;
        }
        
        public Builder failure(String errorCode, String errorMessage, Throwable throwable) {
            this.success = false;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.throwable = throwable;
            return this;
        }
        
        public VariableResult build() {
            return new VariableResult(this);
        }
    }
    
    // 静态工厂方法
    
    /**
     * 创建成功结果
     */
    public static VariableResult success(String value) {
        return new Builder().value(value).build();
    }
    
    /**
     * 创建成功结果并包含操作信息
     */
    public static VariableResult success(String value, String operation, String variableKey, String targetPlayer) {
        return new Builder()
                .value(value)
                .operation(operation)
                .variableKey(variableKey)
                .targetPlayer(targetPlayer)
                .build();
    }
    
    /**
     * 创建失败结果
     */
    public static VariableResult failure(String errorMessage) {
        return new Builder().failure(errorMessage).build();
    }
    
    /**
     * 创建失败结果并包含异常
     */
    public static VariableResult failure(String errorMessage, Throwable throwable) {
        return new Builder().failure(errorMessage, throwable).build();
    }
    
    /**
     * 创建失败结果并包含操作信息
     */
    public static VariableResult failure(String errorMessage, String operation, String variableKey, String targetPlayer) {
        return new Builder()
                .failure(errorMessage)
                .operation(operation)
                .variableKey(variableKey)
                .targetPlayer(targetPlayer)
                .build();
    }
    
    /**
     * 创建带错误码的失败结果
     */
    public static VariableResult failure(String errorCode, String errorMessage, Throwable throwable) {
        return new Builder().failure(errorCode, errorMessage, throwable).build();
    }
    
    /**
     * 从异常创建失败结果
     */
    public static VariableResult fromException(Throwable throwable) {
        return new Builder()
                .failure(throwable.getMessage(), throwable)
                .build();
    }
    
    /**
     * 从异常创建失败结果并包含操作信息
     */
    public static VariableResult fromException(Throwable throwable, String operation, String variableKey, String targetPlayer) {
        return new Builder()
                .failure(throwable.getMessage(), throwable)
                .operation(operation)
                .variableKey(variableKey)
                .targetPlayer(targetPlayer)
                .build();
    }
}