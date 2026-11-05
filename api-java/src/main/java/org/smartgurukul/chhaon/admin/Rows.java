package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns JDBC row values into plain JSON-friendly values: timestamps as UTC ISO-8601 strings ("2026-11-05T08:30:00Z"), dates and
 * times as ISO text, Postgres arrays as lists, json/jsonb as parsed JSON, uuids as text. The web app formats times itself
 * (IST and the viewer's own zone).
 */
final class Rows {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Rows() {}

    static List<Map<String, Object>> clean(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(clean(r));
        return out;
    }

    static Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, value(v)));
        return out;
    }

    static Object value(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts.toInstant().toString();
        if (v instanceof Date d) return d.toLocalDate().toString();
        if (v instanceof Time t) return t.toLocalTime().toString();
        if (v instanceof UUID u) return u.toString();
        if (v instanceof Array a) {
            try {
                Object[] items = (Object[]) a.getArray();
                List<Object> list = new ArrayList<>(items.length);
                for (Object i : items) list.add(value(i));
                return list;
            } catch (SQLException | ClassCastException e) {
                return List.of();
            }
        }
        if ("org.postgresql.util.PGobject".equals(v.getClass().getName())) {   // the driver is a runtime-only dependency
            String type;
            String text;
            try {
                type = (String) v.getClass().getMethod("getType").invoke(v);
                text = (String) v.getClass().getMethod("getValue").invoke(v);
            } catch (ReflectiveOperationException e) {
                return null;
            }
            if (text == null) return null;
            if ("json".equals(type) || "jsonb".equals(type)) {
                try {
                    return JSON.readTree(text);
                } catch (Exception e) {
                    return null;
                }
            }
            return text;
        }
        return v;
    }

    static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
