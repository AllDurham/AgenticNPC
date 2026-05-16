package com.agenticnpc.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * Javalin JsonMapper 实现，使用项目已有的 Gson。
 * 避免引入 Jackson 依赖。
 */
public class GsonJsonMapper implements JsonMapper {

    private final Gson gson = new GsonBuilder().create();

    @Override
    public String toJsonString(Object obj, Type type) {
        return gson.toJson(obj, type);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return gson.fromJson(json, targetType);
    }
}
