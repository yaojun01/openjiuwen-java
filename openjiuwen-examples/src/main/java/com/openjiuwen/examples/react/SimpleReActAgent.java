package com.openjiuwen.examples.react;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 示例 1：简单 ReAct Agent — 天气查询。
 *
 * 最小化的 Agent 定义：一个 @Agent 注解 + 多个 @Tool 方法。
 * Runtime 会自动识别并将工具注册到 LLM 的 function calling 列表。
 *
 * 运行方式：
 * <pre>
 * // 注入 AgentClient，直接调用
 * agentClient.invoke("weather-agent", "北京明天天气怎么样？")
 * </pre>
 */
@Agent(
    name = "weather-agent",
    description = "天气查询 Agent — 根据城市名查询当前和未来天气",
    autonomyLevel = AutonomyLevel.GUIDED,
    systemPrompt = "你是一个天气查询助手。用户会问你各种城市的天气情况，" +
                   "你需要使用提供的工具来查询天气信息，然后用自然语言回答用户。"
)
public class SimpleReActAgent {

    @Tool("查询指定城市的当前天气")
    public String getCurrentWeather(
            @Param("城市名称，如 北京、上海") String city,
            @Param(value = "温度单位", required = false) String unit) {
        // 实际场景中这里调用外部天气 API
        String tempUnit = (unit != null && "fahrenheit".equalsIgnoreCase(unit)) ? "°F" : "°C";
        return switch (city) {
            case "北京" -> "晴天，25" + tempUnit + "，空气质量良好";
            case "上海" -> "多云，28" + tempUnit + "，湿度 75%";
            case "深圳" -> "阵雨，30" + tempUnit + "，湿度 85%";
            default -> city + "：晴转多云，22" + tempUnit;
        };
    }

    @Tool("查询指定城市未来几天的天气预报")
    public String getForecast(
            @Param("城市名称") String city,
            @Param("预报天数，1-7") int days) {
        // 模拟天气 API
        return String.format("""
            %s 未来 %d 天预报：
            - 第1天：晴，24°C
            - 第2天：多云，22°C
            - 第3天：小雨，19°C
            """, city, Math.min(days, 3));
    }
}
