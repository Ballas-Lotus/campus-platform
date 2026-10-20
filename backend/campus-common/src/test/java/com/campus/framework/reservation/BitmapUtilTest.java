package com.campus.framework.reservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位图编解码单元测试（提示词防坑 4：阶段 5 交付）。
 */
class BitmapUtilTest {

    @Test
    @DisplayName("整点时段 → 位图：14:00~16:00 = bit56..63")
    void toBitmapShouldMatchGridRange() {
        BigInteger bits = BitmapUtil.toBitmap(LocalTime.of(14, 0), LocalTime.of(16, 0));
        // bit56..63 = 0xFF << 56
        assertEquals(new BigInteger("FF", 16).shiftLeft(56), bits);
        // hex 长度固定 24
        assertEquals("00000000ff00000000000000", BitmapUtil.toHex(bits));
        assertEquals(24, BitmapUtil.toHex(bits).length());
    }

    @Test
    @DisplayName("相邻时段不重叠：8~10 与 10~12 AND = 0")
    void adjacentIntervalsShouldNotIntersect() {
        BigInteger a = BitmapUtil.toBitmap(LocalTime.of(8, 0), LocalTime.of(10, 0));
        BigInteger b = BitmapUtil.toBitmap(LocalTime.of(10, 0), LocalTime.of(12, 0));
        assertFalse(BitmapUtil.intersects(a, b));
    }

    @Test
    @DisplayName("重叠时段冲突：14~16 与 15~17")
    void overlappingIntervalsShouldIntersect() {
        BigInteger a = BitmapUtil.toBitmap(LocalTime.of(14, 0), LocalTime.of(16, 0));
        BigInteger b = BitmapUtil.toBitmap(LocalTime.of(15, 0), LocalTime.of(17, 0));
        assertTrue(BitmapUtil.intersects(a, b));
    }

    @Test
    @DisplayName("半小时粒度：14:30~15:30 与 14:00~14:30 不重叠，与 14:00~15:00 重叠")
    void halfHourGranularity() {
        BigInteger half = BitmapUtil.toBitmap(LocalTime.of(14, 30), LocalTime.of(15, 30));
        assertFalse(BitmapUtil.intersects(half, BitmapUtil.toBitmap(LocalTime.of(14, 0), LocalTime.of(14, 30))));
        assertTrue(BitmapUtil.intersects(half, BitmapUtil.toBitmap(LocalTime.of(14, 0), LocalTime.of(15, 0))));
    }

    @Test
    @DisplayName("清除位：取消预约后 AND = 0")
    void clearShouldReleaseBits() {
        BigInteger a = BitmapUtil.toBitmap(LocalTime.of(8, 0), LocalTime.of(12, 0));
        BigInteger b = BitmapUtil.toBitmap(LocalTime.of(9, 0), LocalTime.of(10, 0));
        BigInteger merged = BitmapUtil.merge(a, b);
        BigInteger cleared = BitmapUtil.clear(merged, b);
        assertFalse(BitmapUtil.intersects(cleared, b));
        assertTrue(BitmapUtil.intersects(cleared, BitmapUtil.toBitmap(LocalTime.of(8, 0), LocalTime.of(9, 0))));
    }

    @Test
    @DisplayName("hex 往返编解码一致；空位图 = 24 个 0")
    void hexRoundTrip() {
        BigInteger bits = BitmapUtil.toBitmap(LocalTime.of(8, 0), LocalTime.of(22, 0));
        assertEquals(bits, BitmapUtil.fromHex(BitmapUtil.toHex(bits)));
        assertEquals("000000000000000000000000", BitmapUtil.toHex(BitmapUtil.EMPTY));
    }

    @Test
    @DisplayName("非法时段抛异常")
    void invalidRangeShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> BitmapUtil.toBitmap(LocalTime.of(16, 0), LocalTime.of(14, 0)));
    }

    // ---------- 阶段 10：位图反解（个人中心"当日日程"） ----------

    @Test
    @DisplayName("反解：空位图 → 无占用段")
    void decodeEmptyBitmap() {
        assertTrue(BitmapUtil.decodeSegments(BitmapUtil.EMPTY).isEmpty());
        assertTrue(BitmapUtil.decodeSegments(null).isEmpty());
    }

    @Test
    @DisplayName("反解：14:00~16:00 → 一段 [56, 64)，边界时刻还原")
    void decodeSingleSegment() {
        BigInteger bits = BitmapUtil.toBitmap(LocalTime.of(14, 0), LocalTime.of(16, 0));
        var segments = BitmapUtil.decodeSegments(bits);
        assertEquals(1, segments.size());
        assertEquals(56, segments.get(0)[0]);
        assertEquals(64, segments.get(0)[1]);
        assertEquals(LocalTime.of(14, 0), BitmapUtil.gridToTime(segments.get(0)[0]));
        assertEquals(LocalTime.of(16, 0), BitmapUtil.gridToTime(segments.get(0)[1]));
    }

    @Test
    @DisplayName("反解：相邻预约（8~9 与 9~10）合并为一段 [32, 40)；不相邻则为两段")
    void decodeAdjacentAndGappedSegments() {
        BigInteger merged = BitmapUtil.merge(
                BitmapUtil.toBitmap(LocalTime.of(8, 0), LocalTime.of(9, 0)),
                BitmapUtil.toBitmap(LocalTime.of(9, 0), LocalTime.of(10, 0)));
        var adjacent = BitmapUtil.decodeSegments(merged);
        assertEquals(1, adjacent.size());
        assertEquals(32, adjacent.get(0)[0]);
        assertEquals(40, adjacent.get(0)[1]);

        BigInteger gapped = BitmapUtil.merge(
                BitmapUtil.toBitmap(LocalTime.of(8, 0), LocalTime.of(9, 0)),
                BitmapUtil.toBitmap(LocalTime.of(14, 0), LocalTime.of(15, 0)));
        var two = BitmapUtil.decodeSegments(gapped);
        assertEquals(2, two.size());
        assertEquals(32, two.get(0)[0]);
        assertEquals(36, two.get(0)[1]);
        assertEquals(56, two.get(1)[0]);   // 14:00 = 第 56 格
        assertEquals(60, two.get(1)[1]);
    }
}
