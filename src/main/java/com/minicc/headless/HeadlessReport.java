package com.minicc.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minicc.agent.RunResult;
import com.minicc.model.UsageTracker;

/**
 * Headless 模式输出给机器读的结果（{@code --output json}）。Eval Harness 解析的就是这份 JSON。
 */
public record HeadlessReport(
        String status,
        String result,
        String sessionId,
        String model,
        int steps,
        int toolCalls,
        int toolErrors,
        long durationMs,
        long modelRequests,
        long promptTokens,
        long completionTokens,
        long cacheReadTokens,
        long requestsWithoutUsage,
        Double costUsd) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static HeadlessReport of(RunResult r, String sessionId, String model, long durationMs, UsageTracker u) {
        return of(r, sessionId, model, durationMs, u, null);
    }

    /** M9：带单价时附上折算费用（未配置价格则为 null，JSON 里输出 null）。 */
    public static HeadlessReport of(RunResult r, String sessionId, String model, long durationMs, UsageTracker u,
                                    com.minicc.config.MccConfig.Price price) {
        return new HeadlessReport(r.status().name().toLowerCase(), r.finalText(), sessionId, model,
                r.steps(), r.toolCalls(), r.toolErrors(), durationMs,
                u.requests(), u.promptTokens(), u.completionTokens(), u.cacheReadTokens(), u.requestsWithoutUsage(), u.costUsd(price));
    }

    public int exitCode() {
        return switch (status) {
            case "completed" -> 0;
            case "max_steps" -> 2;
            case "cancelled" -> 130;
            default -> 1;
        };
    }

    public String toJson() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("status", status);
        n.put("result", result);
        n.put("session_id", sessionId);
        n.put("model", model);
        n.put("steps", steps);
        n.put("tool_calls", toolCalls);
        n.put("tool_errors", toolErrors);
        n.put("duration_ms", durationMs);
        ObjectNode usage = n.putObject("usage");
        usage.put("model_requests", modelRequests);
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("cache_read_tokens", cacheReadTokens);
        usage.put("requests_without_usage", requestsWithoutUsage);
        if (costUsd == null) {
            usage.putNull("cost_usd");
        } else {
            usage.put("cost_usd", Math.round(costUsd * 1_000_000) / 1_000_000.0);
        }
        return n.toString();
    }
}
