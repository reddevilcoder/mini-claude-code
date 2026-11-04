package com.minicc.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PlanModeState 的单元测试：进入/退出只切这个布尔开关，默认关闭。 */
class PlanModeStateTest {

    @Test
    void defaultsToOff() {
        assertFalse(new PlanModeState().isPlanMode(), "新建的计划态默认关闭");
    }

    @Test
    void enterAndExitTogglesFlag() {
        PlanModeState state = new PlanModeState();
        state.enterPlanMode();
        assertTrue(state.isPlanMode(), "进入后应处于计划态");
        state.exitPlanMode();
        assertFalse(state.isPlanMode(), "退出后应恢复非计划态");
    }
}
