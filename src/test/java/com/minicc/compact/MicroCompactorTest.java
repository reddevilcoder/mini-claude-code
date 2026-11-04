package com.minicc.compact;

import com.minicc.config.MccConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第③层微压缩的单元测试：被调用时清理较早可再取结果、保留最近 N 个。是否执行由 CompactionManager 判定。 */
class MicroCompactorTest {

    private MccConfig config() {
        return new MccConfig("m", 40, 200000, "", "u", false, 1000, MccConfig.DEFAULT_STATE_DIR);
    }

    private ToolResponseMessage bashResult(String id, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "Bash", data)))
                .build();
    }

    @Test
    void clearsOldReFetchableResultsKeepingRecent() {
        MicroCompactor micro = new MicroCompactor(config());   // keepRecent 默认 5

        List<Message> window = new ArrayList<>();
        window.add(new UserMessage("跑一堆命令"));
        for (int i = 0; i < 8; i++) {
            window.add(bashResult("b" + i, "output-" + i));
        }

        List<Message> out = micro.apply(window);

        // 8 个 Bash 结果里，最早的 3 个被清成占位符，最近 5 个保留原文。
        List<String> datas = new ArrayList<>();
        for (Message m : out) {
            if (m instanceof ToolResponseMessage trm) {
                datas.add(trm.getResponses().get(0).responseData());
            }
        }
        assertEquals(8, datas.size());
        long cleared = datas.stream().filter(d -> d.equals("[Old tool result content cleared]")).count();
        assertEquals(3, cleared, "最早 3 个应被清空");
        assertTrue(datas.get(datas.size() - 1).equals("output-7"), "最近的结果应保留原文");
    }

}
