package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.config.MccConfig;
import com.minicc.model.UsageTracker;

/**
 * {@code /cost}（M9）：本次进程内的模型请求次数、Token 用量与折算费用。
 *
 * <p>数据来自服务端返回的真实 usage（主循环、自动压缩、记忆提取全部计入），不是本地估算。
 * 价格来自配置 {@code pricing.<model>}；未配置时只显示 Token，不臆测费用。
 */
public final class CostCommand implements SlashCommand {

    private final UsageTracker usage;
    private final MccConfig config;
    private final Renderer renderer;

    public CostCommand(UsageTracker usage, MccConfig config, Renderer renderer) {
        this.usage = usage;
        this.config = config;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "cost";
    }

    @Override
    public String description() {
        return "查看本次会话的模型请求、Token 用量与费用";
    }

    @Override
    public boolean run(String args) {
        renderer.info("模型请求：" + usage.requests() + " 次"
                + (usage.requestsWithoutUsage() > 0 ? "（其中 " + usage.requestsWithoutUsage() + " 次服务端未返回用量）" : ""));
        renderer.info("  输入 Token   " + usage.promptTokens()
                + (usage.cacheReadTokens() > 0 ? "（缓存命中 " + usage.cacheReadTokens() + "）" : ""));
        renderer.info("  输出 Token   " + usage.completionTokens());
        Double cost = usage.costUsd(config.price());
        renderer.info(cost == null
                ? "  费用         未配置价格（在 mcc.yml 的 pricing." + config.model() + " 下设置 input/output，单位美元/百万 Token）"
                : String.format("  费用         $%.4f", cost));
        return false;
    }
}
