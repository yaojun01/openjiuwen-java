package com.openjiuwen.examples.deepagent;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 子 Agent 1：库存检查 Agent。
 *
 * 被 OrderDeepAgent 通过 AgentKernel.invoke() 动态调用。
 * 独立的 @Agent，有专属的 systemPrompt 和工具集。
 */
@Agent(
    name = "inventory-check-agent",
    description = "库存检查 Agent — 查询商品库存、锁定库存、释放锁定",
    autonomyLevel = AutonomyLevel.GUIDED,
    systemPrompt = "你是库存管理助手。根据商品信息查询库存状态，支持锁定和释放库存。"
)
public class InventoryCheckAgent {

    @Tool("查询指定商品的库存信息")
    public String checkStock(@Param("商品SKU") String sku) {
        return String.format("""
            {
              "sku": "%s",
              "available": 150,
              "locked": 20,
              "warehouse": "华东仓",
              "replenishDate": "2026-06-15"
            }
            """, sku);
    }

    @Tool("锁定指定数量的库存")
    public String lockStock(
            @Param("商品SKU") String sku,
            @Param("锁定数量") int quantity) {
        return String.format("""
            {
              "lockId": "LOCK-%s-%d",
              "sku": "%s",
              "quantity": %d,
              "status": "LOCKED",
              "expiresAt": "2026-06-08T23:59:59Z"
            }
            """, sku, quantity, sku, quantity);
    }
}
