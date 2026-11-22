package br.com.exames.inferenceexperimental;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Full final-channel JSON object only. Never repairs, unwraps, trims values or accepts duplicate keys. */
final class StrictFinalJson {
    static JSONObject parse(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            JSONObject object = new JSONObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key) || reader.peek() != JsonToken.STRING) return null;
                object.put(key, reader.nextString());
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT ? object : null;
        } catch (Exception ignored) {
            // Parser messages may contain model text. Never attach them to assertions/logs.
            return null;
        }
    }
}
