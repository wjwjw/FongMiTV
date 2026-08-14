package com.github.catvod.utils;

import android.text.TextUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Json {

    public static JsonElement parse(String json) {
        String s = stripComments(json);
        try {
            return JsonParser.parseString(s);
        } catch (Throwable e) {
            return new JsonParser().parse(s);
        }
    }

    public static boolean isObj(String text) {
        try {
            if (TextUtils.isEmpty(text)) return false;
            new JSONObject(stripComments(text));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isArray(String text) {
        try {
            if (TextUtils.isEmpty(text)) return false;
            new JSONArray(stripComments(text));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 剥离 JSONC 风格的 // 行注释与块注释。
     * 仅当不在字符串字面量内时才剥离，避免误伤 http:// 之类的值。
     */
    public static String stripComments(String json) {
        if (json == null) return null;
        int n = json.length();
        StringBuilder out = new StringBuilder(n);
        boolean inStr = false;
        boolean escaped = false;
        for (int i = 0; i < n; ) {
            char c = json.charAt(i);
            if (inStr) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inStr = false;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
                i++;
            } else if (c == '/' && i + 1 < n && json.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && json.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && json.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(json.charAt(i) == '*' && json.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    public static boolean isEmpty(JsonObject obj, String key) {
        if (!obj.has(key)) return true;
        JsonElement element = obj.get(key);
        if (element.isJsonNull()) return true;
        if (element.isJsonArray()) return element.getAsJsonArray().isEmpty();
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return element.getAsString().trim().isEmpty();
        return true;
    }

    public static String safeString(JsonObject obj, String key) {
        try {
            return obj.getAsJsonPrimitive(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static List<String> safeListString(JsonObject obj, String key) {
        List<String> result = new ArrayList<>();
        if (!obj.has(key)) return result;
        if (obj.get(key).isJsonObject()) result.add(safeString(obj, key));
        else for (JsonElement opt : obj.getAsJsonArray(key)) result.add(opt.getAsString());
        return result;
    }

    public static List<JsonElement> safeListElement(JsonObject obj, String key) {
        List<JsonElement> result = new ArrayList<>();
        if (!obj.has(key)) return result;
        if (obj.get(key).isJsonObject()) result.add(obj.get(key).getAsJsonObject());
        else for (JsonElement opt : obj.getAsJsonArray(key)) result.add(opt.getAsJsonObject());
        return result;
    }

    public static JsonObject safeObject(JsonElement element) {
        try {
            if (element.isJsonPrimitive()) element = parse(element.getAsJsonPrimitive().getAsString());
            return element.getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    public static Map<String, String> toMap(String json) {
        return TextUtils.isEmpty(json) ? null : toMap(parse(json));
    }

    public static Map<String, String> toMap(JsonElement element) {
        Map<String, String> map = new HashMap<>();
        JsonObject object = safeObject(element);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) map.put(entry.getKey(), safeString(object, entry.getKey()));
        return map;
    }
}
