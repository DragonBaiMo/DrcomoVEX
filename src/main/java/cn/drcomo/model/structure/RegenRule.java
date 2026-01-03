package cn.drcomo.model.structure;

import cn.drcomo.corelib.util.DebugUtil;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 渐进恢复规则
 * - 仅用于数值型变量
 * - 解析格式: amount/interval[@HH:mm-HH:mm][;...]
 */
public class RegenRule {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final String raw;
    private final List<Segment> segments;

    public RegenRule(String raw, List<Segment> segments) {
        this.raw = raw;
        this.segments = segments == null ? Collections.emptyList() : Collections.unmodifiableList(segments);
    }

    public String getRaw() {
        return raw;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /**
     * 计算在给定时间段内应恢复的总量
     */
    public double calculateGain(long fromMillis, long toMillis, ZoneId zone) {
        if (fromMillis <= 0 || toMillis <= fromMillis || segments.isEmpty()) {
            return 0D;
        }
        ZoneId useZone = zone == null ? ZoneId.systemDefault() : zone;
        ZonedDateTime from = Instant.ofEpochMilli(fromMillis).atZone(useZone);
        ZonedDateTime to = Instant.ofEpochMilli(toMillis).atZone(useZone);
        double total = 0D;
        for (Segment s : segments) {
            total += calculateSegmentGain(s, from, to);
        }
        return total;
    }

    /**
     * 计算距离下一个恢复点的毫秒数；无可用恢复则返回 -1
     */
    public long calculateNextMillis(long fromMillis, long nowMillis, ZoneId zone) {
        if (fromMillis <= 0 || nowMillis <= 0 || segments.isEmpty()) {
            return -1L;
        }
        ZoneId useZone = zone == null ? ZoneId.systemDefault() : zone;
        ZonedDateTime from = Instant.ofEpochMilli(fromMillis).atZone(useZone);
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(useZone);
        long best = Long.MAX_VALUE;

        for (Segment s : segments) {
            Long candidate = nextTickForSegment(s, from, now);
            if (candidate != null && candidate > nowMillis) {
                long delta = candidate - nowMillis;
                if (delta < best) {
                    best = delta;
                }
            }
        }
        return best == Long.MAX_VALUE ? -1L : best;
    }

    private Long nextTickForSegment(Segment s, ZonedDateTime from, ZonedDateTime now) {
        if (s.intervalMillis <= 0) return null;

        if (s.isAllDay()) {
            long elapsed = Math.max(0, Duration.between(from, now).toMillis());
            long times = elapsed / s.intervalMillis;
            long nextMillis = from.toInstant().toEpochMilli() + (times + 1) * s.intervalMillis;
            if (nextMillis <= now.toInstant().toEpochMilli()) {
                nextMillis += s.intervalMillis;
            }
            return nextMillis;
        }

        ZoneId zone = now.getZone();
        LocalDate cursor = now.toLocalDate();
        LocalDate fromDate = from.toLocalDate();
        int guard = 0;

        // 最多向后探查 7 天，防止异常配置导致无限循环
        while (guard++ < 7) {
            ZonedDateTime winStart = ZonedDateTime.of(cursor, s.start, zone);
            ZonedDateTime winEnd = ZonedDateTime.of(cursor, s.end, zone);
            if (!winEnd.isAfter(winStart)) {
                cursor = cursor.plusDays(1);
                continue;
            }

            ZonedDateTime anchor;
            if (cursor.equals(fromDate)) {
                anchor = winStart.isAfter(from) ? winStart : from;
            } else if (cursor.isAfter(fromDate)) {
                anchor = winStart;
            } else {
                anchor = from;
            }

            if (anchor.isAfter(winEnd)) {
                cursor = cursor.plusDays(1);
                continue;
            }

            ZonedDateTime effectiveNow = cursor.equals(now.toLocalDate())
                    ? (now.isBefore(winEnd) ? now : winEnd)
                    : winEnd;
            if (effectiveNow.isBefore(anchor)) {
                effectiveNow = anchor;
            }

            long elapsed = Math.max(0, Duration.between(anchor, effectiveNow).toMillis());
            long times = elapsed / s.intervalMillis;
            ZonedDateTime nextTick = anchor.plus(Duration.ofMillis((times + 1) * s.intervalMillis));
            if (!nextTick.isAfter(winEnd) && nextTick.isAfter(now)) {
                return nextTick.toInstant().toEpochMilli();
            }

            cursor = cursor.plusDays(1);
        }
        return null;
    }

    private double calculateSegmentGain(Segment s, ZonedDateTime from, ZonedDateTime to) {
        if (s.intervalMillis <= 0) return 0D;
        if (s.isAllDay()) {
            long dur = Duration.between(from, to).toMillis();
            if (dur <= 0) return 0D;
            long times = dur / s.intervalMillis;
            return times * s.amount;
        }

        double total = 0D;
        LocalDate cursor = from.toLocalDate();
        LocalDate endDate = to.toLocalDate();
        while (!cursor.isAfter(endDate)) {
            ZonedDateTime winStart = ZonedDateTime.of(cursor, s.start, from.getZone());
            ZonedDateTime winEnd = ZonedDateTime.of(cursor, s.end, from.getZone());
            if (!winEnd.isAfter(winStart)) {
                cursor = cursor.plusDays(1);
                continue;
            }
            ZonedDateTime overlapStart = winStart.isAfter(from) ? winStart : from;
            ZonedDateTime overlapEnd = winEnd.isBefore(to) ? winEnd : to;
            if (overlapEnd.isAfter(overlapStart)) {
                long dur = Duration.between(overlapStart, overlapEnd).toMillis();
                if (dur > 0) {
                    long times = dur / s.intervalMillis;
                    total += times * s.amount;
                }
            }
            cursor = cursor.plusDays(1);
        }
        return total;
    }

    /**
     * 解析规则字符串
     */
    public static RegenRule parse(String raw, DebugUtil logger) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        List<Segment> list = new ArrayList<>();
        String[] parts = raw.split(";");
        for (String part : parts) {
            Segment seg = parseSegment(part.trim(), logger);
            if (seg != null) {
                list.add(seg);
            }
        }
        if (list.isEmpty()) {
            return null;
        }
        return new RegenRule(raw, list);
    }

    private static Segment parseSegment(String part, DebugUtil logger) {
        if (part.isEmpty()) return null;
        try {
            String main = part;
            String window = null;
            int at = part.indexOf('@');
            if (at >= 0) {
                main = part.substring(0, at).trim();
                window = part.substring(at + 1).trim();
            }

            String[] split = main.split("/");
            if (split.length != 2) {
                warn(logger, "恢复规则格式无效: " + part);
                return null;
            }
            double amount = Double.parseDouble(split[0].trim());
            long interval = parseIntervalMillis(split[1].trim());
            if (interval <= 0) {
                warn(logger, "恢复间隔无效: " + part);
                return null;
            }

            if (window == null || window.isEmpty()) {
                return new Segment(amount, interval, null, null);
            }

            String[] win = window.split("-");
            if (win.length != 2) {
                warn(logger, "时间段格式无效: " + part);
                return null;
            }
            LocalTime start = LocalTime.parse(win[0].trim(), TIME_FMT);
            LocalTime end = LocalTime.parse(win[1].trim(), TIME_FMT);
            if (!end.isAfter(start)) {
                warn(logger, "时间段需满足结束晚于开始: " + part);
                return null;
            }
            return new Segment(amount, interval, start, end);
        } catch (Exception e) {
            warn(logger, "解析恢复规则失败: " + part + "，" + e.getMessage());
            return null;
        }
    }

    private static long parseIntervalMillis(String expr) {
        if (expr == null || expr.isEmpty()) return -1;
        expr = expr.trim().toLowerCase();
        try {
            if (expr.endsWith("ms")) {
                return Long.parseLong(expr.substring(0, expr.length() - 2));
            }
            if (expr.endsWith("s")) {
                return Long.parseLong(expr.substring(0, expr.length() - 1)) * 1000L;
            }
            if (expr.endsWith("m")) {
                return Long.parseLong(expr.substring(0, expr.length() - 1)) * 60_000L;
            }
            if (expr.endsWith("h")) {
                return Long.parseLong(expr.substring(0, expr.length() - 1)) * 3_600_000L;
            }
            return Long.parseLong(expr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void warn(DebugUtil logger, String msg) {
        if (logger != null) {
            logger.warn(msg);
        }
    }

    public static class Segment {
        private final double amount;
        private final long intervalMillis;
        private final LocalTime start;
        private final LocalTime end;

        public Segment(double amount, long intervalMillis, LocalTime start, LocalTime end) {
            this.amount = amount;
            this.intervalMillis = intervalMillis;
            this.start = start;
            this.end = end;
        }

        public double getAmount() {
            return amount;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public LocalTime getStart() {
            return start;
        }

        public LocalTime getEnd() {
            return end;
        }

        public boolean isAllDay() {
            return start == null || end == null;
        }
    }
}
