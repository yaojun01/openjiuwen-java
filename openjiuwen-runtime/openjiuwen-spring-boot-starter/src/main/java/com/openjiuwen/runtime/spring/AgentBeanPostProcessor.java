package com.openjiuwen.runtime.spring;

import com.openjiuwen.core.alpha.model.ExecutionPolicy;
import com.openjiuwen.runtime.core.dispatch.AgentRegistry;
import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.core.engine.DefaultAgentKernel;
import com.openjiuwen.core.kernel.model.AgentName;
import com.openjiuwen.core.kernel.model.Budget;
import com.openjiuwen.core.kernel.model.ToolName;
import com.openjiuwen.core.meta.AgentDefinition;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Bean 后处理器——扫描 @Agent 注解，自动注册到 AgentRegistry。
 *
 * 处理流程：
 * 1. 检测到 @Agent 注解的 Bean
 * 2. 扫描类中所有 @Tool 方法
 * 3. 构建 AgentDefinition
 * 4. 注册到 AgentRegistry
 * 5. 将 @Tool 方法注册到 AgentKernel 的工具执行表
 *
 * 契约：开发者只需要写 @Agent + @Tool，这个后处理器自动完成一切。
 */
public class AgentBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private AgentRegistry registry;
    private Map<ToolName, DefaultAgentKernel.ToolExecutor> toolExecutors;
    private OpenjiuwenProperties properties;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Agent agentAnnotation = AnnotationUtils.findAnnotation(bean.getClass(), Agent.class);
        if (agentAnnotation == null) {
            return bean;
        }

        // 延迟初始化依赖（避免循环依赖）
        ensureDependencies();

        // 解析 @Agent 注解
        String agentName = agentAnnotation.name();
        String description = agentAnnotation.description();
        AutonomyLevel autonomyLevel = agentAnnotation.autonomyLevel();
        String model = agentAnnotation.model().isEmpty() ? null : agentAnnotation.model();

        // 扫描 @Tool 方法
        List<AgentDefinition.ToolDefinition> toolDefs = new ArrayList<>();
        Method[] methods = bean.getClass().getDeclaredMethods();

        for (Method method : methods) {
            Tool toolAnnotation = AnnotationUtils.findAnnotation(method, Tool.class);
            if (toolAnnotation == null) continue;

            String toolName = method.getName();
            String toolDesc = toolAnnotation.value().isEmpty() ? toolName : toolAnnotation.value();

            // 解析参数
            List<AgentDefinition.ParameterDefinition> params = new ArrayList<>();
            for (Parameter param : method.getParameters()) {
                Param paramAnnotation = param.getAnnotation(Param.class);
                String paramDesc = paramAnnotation != null && !paramAnnotation.value().isEmpty()
                    ? paramAnnotation.value() : param.getName();
                boolean required = paramAnnotation == null || paramAnnotation.required();

                params.add(new AgentDefinition.ParameterDefinition(
                    param.getName(), paramDesc, param.getType().getSimpleName(), required
                ));
            }

            toolDefs.add(new AgentDefinition.ToolDefinition(toolName, toolDesc, params));

            // 注册工具执行器到 kernel
            registerToolExecutor(toolName, bean, method);
        }

        // 构建系统提示
        String systemPrompt = buildSystemPrompt(agentAnnotation, description, toolDefs);

        // 构建预算
        Budget budget = resolveBudget(agentName);

        // 构建 AgentDefinition
        AgentDefinition definition = new AgentDefinition(
            new AgentName(agentName),
            description,
            systemPrompt,
            toolDefs,
            autonomyLevel,
            budget,
            properties.getAlpha().toExecutionPolicy(),
            model,
            null,
            Map.of()
        );

        // 注册到 AgentRegistry
        registry.register(definition);

        return bean;
    }

    /**
     * 将 @Tool 方法注册为工具执行器。
     */
    private void registerToolExecutor(String toolName, Object bean, Method method) {
        method.setAccessible(true);
        DefaultAgentKernel.ToolExecutor executor = args -> {
            // 按参数名匹配参数值
            Object[] paramValues = new Object[method.getParameterCount()];
            Parameter[] params = method.getParameters();
            for (int i = 0; i < params.length; i++) {
                Object val = args.get(params[i].getName());
                if (val == null) {
                    // 尝试类型转换
                    val = args.get(params[i].getName());
                }
                paramValues[i] = val;
            }
            return method.invoke(bean, paramValues);
        };

        toolExecutors.put(new ToolName(toolName), executor);
    }

    /**
     * 构建系统提示。
     * 如果开发者显式提供了 systemPrompt，直接使用。
     * 否则根据 description + @Tool 列表自动生成。
     */
    private String buildSystemPrompt(Agent annotation, String description,
                                      List<AgentDefinition.ToolDefinition> tools) {
        String explicit = annotation.systemPrompt();
        if (!explicit.isEmpty()) {
            return explicit;
        }

        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isEmpty()) {
            sb.append("你是 ").append(description).append("。\n\n");
        } else {
            sb.append("你是一个 AI 助手。\n\n");
        }

        if (!tools.isEmpty()) {
            sb.append("你可以使用以下工具：\n");
            for (AgentDefinition.ToolDefinition tool : tools) {
                sb.append("- ").append(tool.name());
                if (tool.description() != null && !tool.description().isEmpty()) {
                    sb.append(": ").append(tool.description());
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 解析预算：优先使用 Agent 级别配置覆盖，否则用全局默认。
     */
    private Budget resolveBudget(String agentName) {
        OpenjiuwenProperties.AgentConfig agentConfig = properties.getAgents().get(agentName);
        if (agentConfig != null && agentConfig.getBudget() != null) {
            return agentConfig.getBudget().toBudget();
        }
        return properties.getKernel().getDefaultBudget().toBudget();
    }

    /**
     * 延迟获取 Spring Bean 依赖，避免循环依赖。
     */
    @SuppressWarnings("unchecked")
    private void ensureDependencies() {
        if (registry == null) {
            registry = applicationContext.getBean(AgentRegistry.class);
        }
        if (properties == null) {
            properties = applicationContext.getBean(OpenjiuwenProperties.class);
        }
        if (toolExecutors == null) {
            // 尝试获取已有的 toolExecutors Map，或创建新的
            try {
                toolExecutors = applicationContext.getBean(
                    "toolExecutors", Map.class);
            } catch (Exception e) {
                toolExecutors = new ConcurrentHashMap<>();
            }
        }
    }
}
