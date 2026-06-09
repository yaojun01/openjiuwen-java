package com.openjiuwen.runtime.spring;

import com.openjiuwen.core.alpha.model.ExecutionPolicy;
import com.openjiuwen.core.alpha.model.PlanningMode;
import com.openjiuwen.core.alpha.model.VerifyMode;
import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.core.kernel.model.Budget;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Openjiuwen Runtime 配置属性。
 *
 * 前缀：openjiuwen
 * 完整配置路径示例见 src/main/resources/application-openjiuwen-example.yml
 *
 * <pre>
 * openjiuwen:
 *   kernel:
 *     default-model: deepseek-chat
 *     default-autonomy-level: GUIDED
 *     default-budget:
 *       max-llm-calls: 10
 *       max-tool-calls: 20
 *       max-tokens: 100000
 *       timeout-millis: 300000
 *   alpha:
 *     planning-mode: SEMI_AUTO
 *     verify-mode: STRICT
 *     max-retries: 3
 *     max-parallelism: 4
 *     adaptive-replanning: true
 *   security:
 *     enabled: true
 *     sensitive-patterns:
 *       - "api[_-]?key"
 *       - "password"
 *     mcp:
 *       mtls-enabled: false
 *       dev-mode-allowed: true
 *       trust-store-path: ""
 *       key-store-path: ""
 *   checkpoint:
 *     store-type: memory
 *     retention-hours: 24
 *   agents:
 *     order-refund:
 *       model: deepseek-chat
 *       autonomy-level: ASSISTED
 *       budget:
 *         max-llm-calls: 15
 *         max-tool-calls: 30
 * </pre>
 */
@ConfigurationProperties(prefix = "openjiuwen")
public class OpenjiuwenProperties {

    /** 内核配置 */
    private KernelConfig kernel = new KernelConfig();

    /** Alpha 策略配置 */
    private AlphaConfig alpha = new AlphaConfig();

    /** 安全配置 */
    private SecurityConfig security = new SecurityConfig();

    /** 检查点配置 */
    private CheckpointConfig checkpoint = new CheckpointConfig();

    /** Agent 列表配置：agentName → 配置 */
    private Map<String, AgentConfig> agents = new HashMap<>();

    /** 是否启用自动扫描 @Agent 注解 */
    private boolean autoScanAgents = true;

    /** 是否启用 Runtime（设为 false 完全禁用自动配置） */
    private boolean enabled = true;

    // ==================== 内嵌配置类 ====================

    /**
     * 内核配置——AgentKernel 的全局默认参数。
     */
    public static class KernelConfig {
        /** 默认 LLM 模型名称 */
        private String defaultModel = "deepseek-chat";

        /** 默认自主度级别 */
        private AutonomyLevel defaultAutonomyLevel = AutonomyLevel.GUIDED;

        /** 默认预算 */
        private BudgetConfig defaultBudget = new BudgetConfig();

        /** 上下文窗口最大 token 数 */
        private int contextWindowMaxTokens = 128_000;

        /** 上下文压缩阈值（0.0-1.0，达到此比例触发压缩） */
        private double contextCompactionThreshold = 0.7;

        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public AutonomyLevel getDefaultAutonomyLevel() { return defaultAutonomyLevel; }
        public void setDefaultAutonomyLevel(AutonomyLevel level) { this.defaultAutonomyLevel = level; }
        public BudgetConfig getDefaultBudget() { return defaultBudget; }
        public void setDefaultBudget(BudgetConfig defaultBudget) { this.defaultBudget = defaultBudget; }
        public int getContextWindowMaxTokens() { return contextWindowMaxTokens; }
        public void setContextWindowMaxTokens(int tokens) { this.contextWindowMaxTokens = tokens; }
        public double getContextCompactionThreshold() { return contextCompactionThreshold; }
        public void setContextCompactionThreshold(double threshold) { this.contextCompactionThreshold = threshold; }
    }

    /**
     * Alpha 策略配置——PEV 引擎参数。
     */
    public static class AlphaConfig {
        /** 规划模式 */
        private PlanningMode planningMode = PlanningMode.SEMI_AUTO;

        /** 验证模式 */
        private VerifyMode verifyMode = VerifyMode.STRICT;

        /** 验证失败最大重试次数 */
        private int maxRetries = 3;

        /** 同层节点最大并行数 */
        private int maxParallelism = 4;

        /** 是否启用自适应重规划 */
        private boolean adaptiveReplanning = true;

        public PlanningMode getPlanningMode() { return planningMode; }
        public void setPlanningMode(PlanningMode planningMode) { this.planningMode = planningMode; }
        public VerifyMode getVerifyMode() { return verifyMode; }
        public void setVerifyMode(VerifyMode verifyMode) { this.verifyMode = verifyMode; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxParallelism() { return maxParallelism; }
        public void setMaxParallelism(int maxParallelism) { this.maxParallelism = maxParallelism; }
        public boolean isAdaptiveReplanning() { return adaptiveReplanning; }
        public void setAdaptiveReplanning(boolean adaptiveReplanning) { this.adaptiveReplanning = adaptiveReplanning; }

        public ExecutionPolicy toExecutionPolicy() {
            return new ExecutionPolicy(planningMode, verifyMode, maxRetries, maxParallelism, adaptiveReplanning);
        }
    }

    /**
     * 安全配置——SafetyBoundary + MCP 安全。
     */
    public static class SecurityConfig {
        /** 是否启用安全检查 */
        private boolean enabled = true;

        /** 敏感信息正则模式列表 */
        private List<String> sensitivePatterns = new ArrayList<>();

        /** MCP 安全配置 */
        private McpConfig mcp = new McpConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getSensitivePatterns() { return sensitivePatterns; }
        public void setSensitivePatterns(List<String> sensitivePatterns) { this.sensitivePatterns = sensitivePatterns; }
        public McpConfig getMcp() { return mcp; }
        public void setMcp(McpConfig mcp) { this.mcp = mcp; }
    }

    /**
     * MCP 安全配置——Runtime 与 SDK 之间的通信加密。
     */
    public static class McpConfig {
        /** 是否启用 mTLS 双向认证 */
        private boolean mtlsEnabled = false;

        /** 是否允许开发模式降级（明文通信） */
        private boolean devModeAllowed = true;

        /** TrustStore 路径（PKCS12 格式） */
        private String trustStorePath;

        /** KeyStore 路径（PKCS12 格式） */
        private String keyStorePath;

        /** TrustStore 密码 */
        private String trustStorePassword;

        /** KeyStore 密码 */
        private String keyStorePassword;

        public boolean isMtlsEnabled() { return mtlsEnabled; }
        public void setMtlsEnabled(boolean mtlsEnabled) { this.mtlsEnabled = mtlsEnabled; }
        public boolean isDevModeAllowed() { return devModeAllowed; }
        public void setDevModeAllowed(boolean devModeAllowed) { this.devModeAllowed = devModeAllowed; }
        public String getTrustStorePath() { return trustStorePath; }
        public void setTrustStorePath(String trustStorePath) { this.trustStorePath = trustStorePath; }
        public String getKeyStorePath() { return keyStorePath; }
        public void setKeyStorePath(String keyStorePath) { this.keyStorePath = keyStorePath; }
        public String getTrustStorePassword() { return trustStorePassword; }
        public void setTrustStorePassword(String trustStorePassword) { this.trustStorePassword = trustStorePassword; }
        public String getKeyStorePassword() { return keyStorePassword; }
        public void setKeyStorePassword(String keyStorePassword) { this.keyStorePassword = keyStorePassword; }
    }

    /**
     * 检查点存储配置。
     */
    public static class CheckpointConfig {
        /** 存储类型：memory / redis / jdbc */
        private String storeType = "memory";

        /** Redis URL（store-type=redis 时使用） */
        private String redisUrl;

        /** 检查点保留时长（小时），超过后自动清理 */
        private int retentionHours = 24;

        /** JDBC 数据源名称（store-type=jdbc 时使用） */
        private String dataSourceName;

        public String getStoreType() { return storeType; }
        public void setStoreType(String storeType) { this.storeType = storeType; }
        public String getRedisUrl() { return redisUrl; }
        public void setRedisUrl(String redisUrl) { this.redisUrl = redisUrl; }
        public int getRetentionHours() { return retentionHours; }
        public void setRetentionHours(int retentionHours) { this.retentionHours = retentionHours; }
        public String getDataSourceName() { return dataSourceName; }
        public void setDataSourceName(String dataSourceName) { this.dataSourceName = dataSourceName; }
    }

    /**
     * 预算配置——可复用于全局默认和 Agent 级别覆盖。
     */
    public static class BudgetConfig {
        private int maxLlmCalls = 10;
        private int maxToolCalls = 20;
        private long maxTokens = 100_000L;
        private long timeoutMillis = 300_000L;

        public int getMaxLlmCalls() { return maxLlmCalls; }
        public void setMaxLlmCalls(int maxLlmCalls) { this.maxLlmCalls = maxLlmCalls; }
        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
        public long getMaxTokens() { return maxTokens; }
        public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
        public long getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }

        public Budget toBudget() {
            return new Budget.Fixed(maxLlmCalls, maxToolCalls, maxTokens, timeoutMillis);
        }
    }

    /**
     * 单个 Agent 的配置覆盖。
     */
    public static class AgentConfig {
        /** Agent 使用的模型（覆盖全局默认） */
        private String model;

        /** Agent 自主度级别 */
        private AutonomyLevel autonomyLevel;

        /** Agent 预算 */
        private BudgetConfig budget;

        /** Agent 描述 */
        private String description;

        /** Agent 系统提示 */
        private String systemPrompt;

        /** Agent 可用工具列表 */
        private List<String> tools = new ArrayList<>();

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public AutonomyLevel getAutonomyLevel() { return autonomyLevel; }
        public void setAutonomyLevel(AutonomyLevel autonomyLevel) { this.autonomyLevel = autonomyLevel; }
        public BudgetConfig getBudget() { return budget; }
        public void setBudget(BudgetConfig budget) { this.budget = budget; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
    }

    // ==================== Getter / Setter ====================

    public KernelConfig getKernel() { return kernel; }
    public void setKernel(KernelConfig kernel) { this.kernel = kernel; }

    public AlphaConfig getAlpha() { return alpha; }
    public void setAlpha(AlphaConfig alpha) { this.alpha = alpha; }

    public SecurityConfig getSecurity() { return security; }
    public void setSecurity(SecurityConfig security) { this.security = security; }

    public CheckpointConfig getCheckpoint() { return checkpoint; }
    public void setCheckpoint(CheckpointConfig checkpoint) { this.checkpoint = checkpoint; }

    public Map<String, AgentConfig> getAgents() { return agents; }
    public void setAgents(Map<String, AgentConfig> agents) { this.agents = agents; }

    public boolean isAutoScanAgents() { return autoScanAgents; }
    public void setAutoScanAgents(boolean autoScanAgents) { this.autoScanAgents = autoScanAgents; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
