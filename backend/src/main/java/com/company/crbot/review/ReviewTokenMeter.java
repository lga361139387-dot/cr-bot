package com.company.crbot.review;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReviewTokenMeter {

    private final ThreadLocal<Accumulator> current = new ThreadLocal<>();

    public void start() {
        // 一次评审单独累计，避免和索引向量的调用混在一起
        current.set(new Accumulator());
    }

    public void beginPhase(String name) {
        Accumulator accumulator = current.get();
        if (accumulator == null) {
            return;
        }
        closePhase(accumulator);
        accumulator.phase = name;
        accumulator.phaseStart = System.nanoTime();
        accumulator.phasePrompt = accumulator.prompt;
        accumulator.phaseCompletion = accumulator.completion;
        accumulator.phaseTotal = accumulator.total;
    }

    public List<Phase> phases() {
        Accumulator accumulator = current.get();
        if (accumulator == null) {
            return List.of();
        }
        closePhase(accumulator);
        return List.copyOf(accumulator.phases);
    }

    public Object share() {
        return current.get();
    }

    public void use(Object shared) {
        if (shared == null) {
            current.remove();
            return;
        }
        current.set((Accumulator) shared);
    }

    public void record(JsonNode usage) {
        Accumulator accumulator = current.get();
        if (accumulator == null) {
            // 不在评审线程里的调用，例如上传文档建索引，不记到评审上
            return;
        }
        synchronized (accumulator) {
            recordLocked(accumulator, usage);
        }
    }

    private void recordLocked(Accumulator accumulator, JsonNode usage) {
        accumulator.calls++;
        if (usage == null || usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return;
        }
        if (!usage.has("prompt_tokens") && !usage.has("completion_tokens") && !usage.has("total_tokens")) {
            return;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        // 网关给了 total 就用它，否则用输入加输出
        long total = usage.hasNonNull("total_tokens") ? usage.get("total_tokens").asLong() : prompt + completion;
        accumulator.prompt += prompt;
        accumulator.completion += completion;
        accumulator.total += total;
        accumulator.reported = true;
    }

    public Snapshot finish() {
        Accumulator accumulator = current.get();
        current.remove();
        if (accumulator == null) {
            return null;
        }
        if (!accumulator.reported) {
            if (accumulator.calls == 0) {
                // 这次没有调用模型，消耗就是 0
                return new Snapshot(0L, 0L, 0L);
            }
            // 调用了但响应里没有 usage，不把未知记成 0
            return null;
        }
        return new Snapshot(accumulator.prompt, accumulator.completion, accumulator.total);
    }

    public record Snapshot(long promptTokens, long completionTokens, long totalTokens) {
    }

    public record Phase(String name, long elapsedMs, long promptTokens, long completionTokens, long totalTokens) {
    }

    private static void closePhase(Accumulator accumulator) {
        if (accumulator.phase == null) {
            return;
        }
        long elapsed = Math.max(0, (System.nanoTime() - accumulator.phaseStart) / 1_000_000);
        accumulator.phases.add(new Phase(accumulator.phase, elapsed,
                accumulator.prompt - accumulator.phasePrompt,
                accumulator.completion - accumulator.phaseCompletion,
                accumulator.total - accumulator.phaseTotal));
        accumulator.phase = null;
    }

    private static final class Accumulator {
        private long prompt;
        private long completion;
        private long total;
        private int calls;
        private boolean reported;
        private String phase;
        private long phaseStart;
        private long phasePrompt;
        private long phaseCompletion;
        private long phaseTotal;
        private final List<Phase> phases = new ArrayList<>();
    }
}
