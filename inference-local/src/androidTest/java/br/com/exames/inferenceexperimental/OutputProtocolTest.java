package br.com.exames.inferenceexperimental;

import org.junit.Test;
import static org.junit.Assert.*;

public class OutputProtocolTest {
    @Test public void acceptsOnlyCompleteStringObject() throws Exception {
        assertEquals("synthetic", StrictFinalJson.parse("{\"field\":\"synthetic\"}").getString("field"));
    }

    @Test public void rejectsWrappersTrailingTextAndMultipleObjects() {
        assertNull(StrictFinalJson.parse("```json\n{\"field\":\"synthetic\"}\n```"));
        assertNull(StrictFinalJson.parse("prefix {\"field\":\"synthetic\"}"));
        assertNull(StrictFinalJson.parse("{\"field\":\"synthetic\"} trailing"));
        assertNull(StrictFinalJson.parse("{\"field\":\"synthetic\"}{\"field\":\"other\"}"));
    }

    @Test public void rejectsDuplicateKeysAndNonStringValues() {
        assertNull(StrictFinalJson.parse("{\"field\":\"synthetic\",\"field\":\"other\"}"));
        assertNull(StrictFinalJson.parse("{\"field\":12}"));
        assertNull(StrictFinalJson.parse("{\"field\":null}"));
        assertNull(StrictFinalJson.parse("{\"field\":{\"nested\":\"synthetic\"}}"));
    }

    @Test public void rejectsLenientSyntaxAndTruncation() {
        assertNull(StrictFinalJson.parse("{'field':'synthetic'}"));
        assertNull(StrictFinalJson.parse("{field:\"synthetic\"}"));
        assertNull(StrictFinalJson.parse("{/* comment */\"field\":\"synthetic\"}"));
        assertNull(StrictFinalJson.parse("{\"field\":\"synthetic\""));
    }
}
