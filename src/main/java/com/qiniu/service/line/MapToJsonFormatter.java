package com.qiniu.service.line;

import com.google.gson.JsonObject;
import com.qiniu.service.interfaces.IStringFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MapToJsonFormatter implements IStringFormat<Map<String, String>> {

    private List<String> rmFields;

    public MapToJsonFormatter(List<String> removeFields) {
        this.rmFields = removeFields == null ? new ArrayList<>() : removeFields;
    }

    public String toFormatString(Map<String, String> infoMap) {
        JsonObject converted = new JsonObject();
        Set<String> set = infoMap.keySet();
        set.removeAll(rmFields);
        if (set.contains("key")) {
            converted.addProperty("key", infoMap.get("key"));
            set.remove("key");
        }
        if (set.contains("hash")) {
            converted.addProperty("hash", infoMap.get("hash"));
            set.remove("hash");
        }
        if (set.contains("fsize")) {
            converted.addProperty("fsize", Long.valueOf(infoMap.get("fsize")));
            set.remove("fsize");
        }
        if (set.contains("putTime")) {
            converted.addProperty("putTime", Long.valueOf(infoMap.get("putTime")));
            set.remove("putTime");
        }
        if (set.contains("mimeType")) {
            converted.addProperty("mimeType", infoMap.get("mimeType"));
            set.remove("mimeType");
        }
        if (set.contains("type")) {
            converted.addProperty("type", Integer.valueOf(infoMap.get("type")));
            set.remove("type");
        }
        if (set.contains("status")) {
            converted.addProperty("status", Integer.valueOf(infoMap.get("status")));
            set.remove("status");
        }
        if (set.contains("endUser")) {
            converted.addProperty("endUser", infoMap.get("endUser"));
            set.remove("endUser");
        }
        for (String key : set) {
            converted.addProperty(key, infoMap.get(key));
        }
        return converted.toString();
    }
}