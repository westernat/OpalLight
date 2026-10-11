package org.mesdag.opallight.light.data;

import com.google.gson.JsonElement;

final class LightDataMatch {
    private LightDataMatch() {}

    static boolean matches(JsonElement expected, JsonElement actual) {
        if (actual == null) return false;
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) return false;
            for (var entry : expected.getAsJsonObject().entrySet()) {
                if (!matches(entry.getValue(), actual.getAsJsonObject().get(entry.getKey()))) return false;
            }
            return true;
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray()) return false;
            for (var value : expected.getAsJsonArray()) {
                boolean found = false;
                for (var candidate : actual.getAsJsonArray()) {
                    if (matches(value, candidate)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
        return expected.equals(actual);
    }
}
