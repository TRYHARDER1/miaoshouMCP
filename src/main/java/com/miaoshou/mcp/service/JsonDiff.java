package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.domain.JsonChange;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class JsonDiff {
    private JsonDiff() { }

    public static List<JsonChange> compare(JsonNode original, JsonNode draft) {
        List<JsonChange> changes = new ArrayList<>();
        visit("", original, draft, changes);
        return List.copyOf(changes);
    }

    private static void visit(String path, JsonNode a, JsonNode b, List<JsonChange> changes) {
        if (a.equals(b)) {
            return;
        }
        if (a.isObject() && b.isObject()) {
            TreeSet<String> keys = new TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                visit(path + "/" + key.replace("~", "~0").replace("/", "~1"), a.path(key), b.path(key), changes);
            }
        } else if (a.isArray() && b.isArray() && a.size() == b.size()) {
            for (int index = 0; index < a.size(); index++) {
                visit(path + "/" + index, a.get(index), b.get(index), changes);
            }
        } else {
            changes.add(new JsonChange(path, !a.isMissingNode(), a.deepCopy(), !b.isMissingNode(), b.deepCopy()));
        }
    }
}
