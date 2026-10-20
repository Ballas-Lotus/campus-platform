package com.campus.framework.reservation;

import java.math.BigInteger;
import java.time.LocalTime;

/**
 * 96 格时间位图工具（跨资源时间冲突检测的核心，对应提示词 6.3 与防坑 4）。
 *
 * <p>设计：一天粒度 15 分钟 → 96 格；bit i 表示第 i 个 15 分钟格（bit0 = 00:00~00:15）。
 * 以 24 个 hex 字符存库（与种子数据 t_user_time_occupancy.bitmap 格式一致）。</p>
 *
 * <p>为什么用 {@link BigInteger} 而不是 long：96 &gt; 64，long 放不下。</p>
 *
 * <p>冲突判断：{@code (新预约位图 AND 已有位图) != 0} 即冲突。</p>
 */
public final class BitmapUtil {

    private BitmapUtil() {
    }

    /** 每格 15 分钟 */
    public static final int GRID_MINUTES = 15;
    /** 一天 96 格 */
    public static final int GRIDS_PER_DAY = 96;
    /** 24 个 hex 字符（96 bit / 4） */
    public static final int HEX_LENGTH = 24;
    /** 全 0 位图 */
    public static final BigInteger EMPTY = BigInteger.ZERO;

    /**
     * 时段 → 位图（支持半小时粒度；如 14:00~16:00 → bit 56..63）。
     */
    public static BigInteger toBitmap(LocalTime start, LocalTime end) {
        int startGrid = toGrid(start);
        int endGrid = toGrid(end);
        if (startGrid >= endGrid) {
            throw new IllegalArgumentException("开始时间必须早于结束时间: " + start + " ~ " + end);
        }
        return gridMask(startGrid, endGrid);
    }

    /** 起止格 → 位图：低 startGrid 位清零后保留 [startGrid, endGrid) */
    private static BigInteger gridMask(int startGrid, int endGrid) {
        return BigInteger.ONE.shiftLeft(endGrid).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(startGrid).subtract(BigInteger.ONE));
    }

    /** 时间 → 格序号（向下取整；23:59 → 95） */
    public static int toGrid(LocalTime time) {
        return (time.getHour() * 60 + time.getMinute()) / GRID_MINUTES;
    }

    /** 是否存在冲突：(a AND b) != 0 */
    public static boolean intersects(BigInteger a, BigInteger b) {
        return a != null && b != null && a.and(b).signum() != 0;
    }

    /** 合并占用：a OR b */
    public static BigInteger merge(BigInteger a, BigInteger b) {
        return (a == null ? EMPTY : a).or(b == null ? EMPTY : b);
    }

    /** 清除占用：occupancy AND NOT toClear（取消预约时释放位图） */
    public static BigInteger clear(BigInteger occupancy, BigInteger toClear) {
        if (occupancy == null) {
            return EMPTY;
        }
        return occupancy.andNot(toClear == null ? EMPTY : toClear);
    }

    /**
     * 位图反解：连续占用段列表（每段 {@code [startGrid, endGrid)}）——个人中心"当日日程"用。
     *
     * <p>相邻预约（如 12:00-13:00 与 13:00-14:00）会合并为一段，调用方再按段匹配预约明细。</p>
     */
    public static java.util.List<int[]> decodeSegments(BigInteger bits) {
        java.util.List<int[]> segments = new java.util.ArrayList<>();
        if (bits == null || bits.signum() == 0) {
            return segments;
        }
        int start = -1;
        for (int grid = 0; grid < GRIDS_PER_DAY; grid++) {
            boolean occupied = bits.testBit(grid);
            if (occupied && start < 0) {
                start = grid;
            } else if (!occupied && start >= 0) {
                segments.add(new int[]{start, grid});
                start = -1;
            }
        }
        if (start >= 0) {
            segments.add(new int[]{start, GRIDS_PER_DAY});
        }
        return segments;
    }

    /** 格序号 → 时刻（反解展示用；预约开放时段 08:00~22:00 内段尾最大 88，不会触达 96） */
    public static LocalTime gridToTime(int grid) {
        int minutes = grid * GRID_MINUTES;
        return LocalTime.of((minutes / 60) % 24, minutes % 60);
    }

    /** 位图 → 24 个 hex 字符（低 bit 在右，与生成器/库内格式一致） */
    public static String toHex(BigInteger bits) {
        StringBuilder sb = new StringBuilder((bits == null ? EMPTY : bits).toString(16));
        while (sb.length() < HEX_LENGTH) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    /** 24 个 hex 字符 → 位图 */
    public static BigInteger fromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return EMPTY;
        }
        return new BigInteger(hex, 16);
    }
}
