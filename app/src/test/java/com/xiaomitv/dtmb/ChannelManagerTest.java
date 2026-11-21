package com.xiaomitv.dtmb;

import com.xiaomitv.dtmb.TvListings.Channel;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ChannelManagerTest {

    public static void main(String[] args) {
        testParseAndSerializeOrder();
        testApplyDefaultOrder();
        testApplyCustomOrder();
        testApplyCustomOrderWithNewChannels();
        testHiddenChannels();
        testRestoreHiddenChannels();
        testAllHiddenChannels();
        System.out.println("所有 ChannelManager 测试用例全部通过！");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError(message + " - 期望: " + expected + ", 实际: " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void testParseAndSerializeOrder() {
        List<Long> empty = ChannelManager.parseOrder("");
        assertTrue(empty.isEmpty(), "空字符串应该解析为空列表");

        List<Long> invalid = ChannelManager.parseOrder("  , abc, 123, -5, def, 456 ");
        assertEquals(Arrays.asList(123L, -5L, 456L), invalid, "应忽略非法字符并提取数字");

        List<Long> order = Arrays.asList(101L, 102L, 103L);
        String serialized = ChannelManager.serializeOrder(order);
        assertEquals("101,102,103", serialized, "序列化格式应正确");

        List<Long> parsed = ChannelManager.parseOrder(serialized);
        assertEquals(order, parsed, "序列化与反序列化应往返一致");
    }

    private static void testApplyDefaultOrder() {
        Channel c1 = new Channel(1, "1", "CCTV-1");
        Channel c2 = new Channel(2, "2", "CCTV-2");
        Channel c3 = new Channel(3, "3", "CCTV-3");
        List<Channel> raw = Arrays.asList(c1, c2, c3);

        ChannelManager.Result result = ChannelManager.apply(raw, Collections.emptyList(), Collections.emptySet());
        assertEquals(raw, result.allSorted(), "默认排序应该与 raw 列表相同");
        assertEquals(raw, result.visible(), "无隐藏时可见频道应与 allSorted 相同");
    }

    private static void testApplyCustomOrder() {
        Channel c1 = new Channel(1, "1", "CCTV-1");
        Channel c2 = new Channel(2, "2", "CCTV-2");
        Channel c3 = new Channel(3, "3", "CCTV-3");
        List<Channel> raw = Arrays.asList(c1, c2, c3);

        // 自定义排序：3, 1, 2
        List<Long> custom = Arrays.asList(3L, 1L, 2L);
        ChannelManager.Result result = ChannelManager.apply(raw, custom, Collections.emptySet());
        assertEquals(Arrays.asList(c3, c1, c2), result.allSorted(), "应按照自定义顺序排列");
        assertEquals(Arrays.asList(c3, c1, c2), result.visible(), "可见频道应保持自定义顺序");
    }

    private static void testApplyCustomOrderWithNewChannels() {
        Channel c1 = new Channel(1, "1", "CCTV-1");
        Channel c2 = new Channel(2, "2", "CCTV-2");
        Channel c3 = new Channel(3, "3", "CCTV-3");
        Channel c4 = new Channel(4, "4", "CCTV-4"); // 新搜出来的台
        List<Channel> raw = Arrays.asList(c1, c2, c3, c4);

        // 自定义排序只有 2 和 1
        List<Long> custom = Arrays.asList(2L, 1L);
        ChannelManager.Result result = ChannelManager.apply(raw, custom, Collections.emptySet());
        assertEquals(Arrays.asList(c2, c1, c3, c4), result.allSorted(), "未记录的新频道应追加在末尾");
    }

    private static void testHiddenChannels() {
        Channel c1 = new Channel(1, "1", "CCTV-1");
        Channel c2 = new Channel(2, "2", "CCTV-2");
        Channel c3 = new Channel(3, "3", "CCTV-3");
        List<Channel> raw = Arrays.asList(c1, c2, c3);

        Set<String> hidden = new HashSet<>(Collections.singletonList("2"));
        ChannelManager.Result result = ChannelManager.apply(raw, Collections.emptyList(), hidden);

        assertEquals(Arrays.asList(c1, c2, c3), result.allSorted(), "allSorted 应该包含被隐藏的频道");
        assertEquals(Arrays.asList(c1, c3), result.visible(), "visible 应该排除被隐藏的频道");
    }

    private static void testRestoreHiddenChannels() {
        Channel c1 = new Channel(1, "1", "CCTV-1");
        Channel c2 = new Channel(2, "2", "CCTV-2");
        List<Channel> raw = Arrays.asList(c1, c2);

        Set<String> hidden = new HashSet<>(Collections.singletonList("1"));
        ChannelManager.Result hiddenResult = ChannelManager.apply(raw, Collections.emptyList(), hidden);
        assertEquals(Collections.singletonList(c2), hiddenResult.visible(), "隐藏频道 1");

        hidden.remove("1");
        ChannelManager.Result restoredResult = ChannelManager.apply(raw, Collections.emptyList(), hidden);
        assertEquals(raw, restoredResult.visible(), "恢复显示频道 1");
    }

    private static void testAllHiddenChannels() {
        Channel c1 = new Channel(1, "1", "CCTV-1");
        Channel c2 = new Channel(2, "2", "CCTV-2");
        List<Channel> raw = Arrays.asList(c1, c2);

        Set<String> hidden = new HashSet<>(Arrays.asList("1", "2"));
        ChannelManager.Result result = ChannelManager.apply(raw, Collections.emptyList(), hidden);
        assertEquals(Arrays.asList(c1, c2), result.allSorted(), "全隐藏时 allSorted 依然包含所有频道");
        assertTrue(result.visible().isEmpty(), "全隐藏时 visible 为空");
    }
}
