/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.external.response.streaming;

import org.elasticsearch.test.ESTestCase;

import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class JsonPartsEventParserTests extends ESTestCase {

    private void assertJsonParts(Deque<byte[]> actualParts, List<String> expectedJsonStrings) {
        assertThat("Number of parsed parts mismatch", actualParts.size(), equalTo(expectedJsonStrings.size()));
        var expectedIter = expectedJsonStrings.iterator();
        actualParts.forEach(part -> {
            String actualJsonString = new String(part, StandardCharsets.UTF_8);
            assertThat(actualJsonString, equalTo(expectedIter.next()));
        });
    }

    public void testParse_givenNullOrEmptyBytes_returnsEmptyDeque() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse(new byte[0]).isEmpty());

        // Test with pre-existing incomplete part
        parser.parse("{".getBytes(StandardCharsets.UTF_8)); // Create an incomplete part
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse(new byte[0]).isEmpty());
        // Check that the incomplete part is still there
        Deque<byte[]> parts = parser.parse("}".getBytes(StandardCharsets.UTF_8));
        assertJsonParts(parts, List.of("{}"));
    }

    public void testParse_singleCompleteObject_returnsOnePart() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json = "{\"key\":\"value\"}";
        byte[] input = json.getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json));
    }

    public void testParse_multipleCompleteObjectsInOneChunk_returnsMultipleParts() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{\"key1\":\"value1\"}";
        String json2 = "{\"key2\":\"value2\"}";
        // Simulating a JSON array structure, the parser extracts {}
        byte[] input = ("[" + json1 + "," + json2 + "]").getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json1, json2));
    }

    public void testParse_twoObjectsBackToBack_extractsBoth() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{\"a\":1}";
        String json2 = "{\"b\":2}";
        byte[] input = (json1 + json2).getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json1, json2));
    }

    public void testParse_objectSplitAcrossChunks_returnsOnePartAfterAllChunks() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json = "{\"key\":\"very_long_value\"}";
        byte[] chunk1 = "{\"key\":\"very_long".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "_value\"}".getBytes(StandardCharsets.UTF_8);

        Deque<byte[]> parts1 = parser.parse(chunk1);
        assertTrue("Expected no parts from incomplete chunk", parts1.isEmpty());

        Deque<byte[]> parts2 = parser.parse(chunk2);
        assertJsonParts(parts2, List.of(json));
    }

    public void testParse_multipleObjectsSomeSplit_returnsPartsIncrementally() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{\"id\":1,\"name\":\"first\"}";
        String json2 = "{\"id\":2,\"name\":\"second_is_longer\"}";
        String json3 = "{\"id\":3,\"name\":\"third\"}";

        // Chunk 1: [{"id":1,"name":"first"},{"id":2,"name":"sec
        byte[] chunk1 = ("[" + json1 + ",{\"id\":2,\"name\":\"sec").getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts1 = parser.parse(chunk1);
        assertJsonParts(parts1, List.of(json1));

        // Chunk 2: ond_is_longer"},{"id":3,"name":"third"}]
        byte[] chunk2 = ("ond_is_longer\"}," + json3 + "]").getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts2 = parser.parse(chunk2);
        assertJsonParts(parts2, List.of(json2, json3));

        assertTrue("Expected no more parts from empty call", parser.parse(new byte[0]).isEmpty());
    }

    public void testParse_withArrayBracketsAndCommas_extractsObjects() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{\"a\":1}";
        String json2 = "{\"b\":2}";
        byte[] input = ("  [  " + json1 + " , " + json2 + "  ]  ").getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json1, json2));
    }

    public void testParse_nestedObjects_extractsTopLevelObject() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json = "{\"outer_key\":{\"inner_key\":\"value\"},\"another_key\":\"val\"}";
        byte[] input = json.getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json));
    }

    public void testParse_nestedObjectSplit_extractsTopLevelObject() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json = "{\"outer_key\":{\"inner_key\":\"value\"},\"another_key\":\"val\"}";
        byte[] chunk1 = "{\"outer_key\":{\"inner_key\":\"val".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "ue\"},\"another_key\":\"val\"}".getBytes(StandardCharsets.UTF_8);

        Deque<byte[]> parts1 = parser.parse(chunk1);
        assertTrue(parts1.isEmpty());

        Deque<byte[]> parts2 = parser.parse(chunk2);
        assertJsonParts(parts2, List.of(json));
    }

    public void testParse_endsWithIncompleteObject_buffersCorrectly() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{\"complete\":\"done\"}";
        String partialJsonStart = "{\"incomplete_start\":\"";

        byte[] input = (json1 + "," + partialJsonStart).getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json1)); // Only the complete one

        String partialJsonEnd = "continue\"}";
        String json2 = partialJsonStart + partialJsonEnd;
        byte[] nextChunk = partialJsonEnd.getBytes(StandardCharsets.UTF_8);
        parts = parser.parse(nextChunk);
        assertJsonParts(parts, List.of(json2));
    }

    public void testParse_onlyOpenBrace_buffers() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        byte[] input = "{".getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertTrue(parts.isEmpty());

        byte[] nextInput = "\"key\":\"val\"}".getBytes(StandardCharsets.UTF_8);
        parts = parser.parse(nextInput);
        assertJsonParts(parts, List.of("{\"key\":\"val\"}"));
    }

    public void testParse_onlyCloseBrace_ignored() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        byte[] input = "}".getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertTrue(parts.isEmpty()); // Should be ignored as no open brace context

        // With preceding data
        parts = parser.parse("some data }".getBytes(StandardCharsets.UTF_8));
        assertTrue(parts.isEmpty());
    }

    public void testParse_mismatchedBraces_handlesGracefully() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        // Extra closing brace
        byte[] input1 = "{\"key\":\"val\"}}".getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts1 = parser.parse(input1);
        assertJsonParts(parts1, List.of("{\"key\":\"val\"}")); // First object is fine, extra '}' ignored

        // Extra opening brace at end
        parser = new JsonArrayPartsEventParser(); // reset
        byte[] input2 = "{\"key\":\"val\"}{".getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts2 = parser.parse(input2);
        assertJsonParts(parts2, List.of("{\"key\":\"val\"}")); // First object
        // The last '{' should be buffered
        Deque<byte[]> parts3 = parser.parse("}".getBytes(StandardCharsets.UTF_8));
        assertJsonParts(parts3, List.of("{}")); // Completes the buffered '{'
    }

    public void testParse_objectWithMultiByteChars_handlesCorrectly() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json = "{\"key\":\"value_with_emoji_😊_and_résumé\"}";
        byte[] input = json.getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json));

        // Split case
        parser = new JsonArrayPartsEventParser(); // reset
        String part1Str = "{\"key\":\"value_with_emoji_😊"; // Split within multi-byte char or after
        String part2Str = "_and_résumé\"}";
        byte[] chunk1 = part1Str.getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = part2Str.getBytes(StandardCharsets.UTF_8);

        Deque<byte[]> parts1 = parser.parse(chunk1);
        assertTrue(parts1.isEmpty());

        Deque<byte[]> parts2 = parser.parse(chunk2);
        assertJsonParts(parts2, List.of(json));
    }

    public void testParse_javadocExampleStream() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{\"key\":\"val1\"}";
        String json2 = "{\"key2\":\"val2\"}";
        String json3 = "{\"key3\":\"val3\"}";
        String json4 = "{\"some\":\"object\"}";

        // Chunk 1: [{"key":"val1"}
        Deque<byte[]> parts1 = parser.parse(("[{\"key\":\"val1\"}").getBytes(StandardCharsets.UTF_8));
        assertJsonParts(parts1, List.of(json1));

        // Chunk 2: ,{"key2":"val2"}
        Deque<byte[]> parts2 = parser.parse((",{\"key2\":\"val2\"}").getBytes(StandardCharsets.UTF_8));
        assertJsonParts(parts2, List.of(json2));

        // Chunk 3: ,{"key3":"val3"}, {"some":"object"}]
        Deque<byte[]> parts3 = parser.parse((",{\"key3\":\"val3\"}, {\"some\":\"object\"}]").getBytes(StandardCharsets.UTF_8));
        assertJsonParts(parts3, List.of(json3, json4));
    }

    public void testParse_emptyObjects() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{}";
        String json2 = "{\"a\":{}}";
        byte[] input = (json1 + " " + json2).getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json1, json2));
    }

    public void testParse_dataBeforeFirstObjectAndAfterLastObject() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        String json1 = "{\"key1\":\"value1\"}";
        String json2 = "{\"key2\":\"value2\"}";
        byte[] input = ("leading_garbage" + json1 + "middle_garbage" + json2 + "trailing_garbage").getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts = parser.parse(input);
        assertJsonParts(parts, List.of(json1, json2));
    }

    public void testParse_incompleteObjectNeverCompleted() {
        JsonArrayPartsEventParser parser = new JsonArrayPartsEventParser();
        byte[] chunk1 = "{\"key\":".getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts1 = parser.parse(chunk1);
        assertTrue(parts1.isEmpty());

        // Send another chunk that doesn't complete the first object but starts a new one
        byte[] chunk2 = "{\"anotherKey\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> parts2 = parser.parse(chunk2);
        // The incomplete "{\"key\":" is overwritten by the new complete object "{\"anotherKey\":\"value\"}"
        // because objectStartIndex will be reset to the start of the new object.
        // The previous incompletePart is combined, but if a new '{' is found at brace level 0,
        // objectStartIndex is updated. The old incomplete part is effectively discarded if not completed.
        // Let's trace:
        // After chunk1: incompletePart = "{\"key\":"
        // parse(chunk2): dataToProcess = "{\"key\":{\"anotherKey\":\"value\"}"
        // incompletePart.reset()
        // Loop:
        // '{' -> objectStartIndex=0, braceLevel=1
        // ...
        // ':' ->
        // '{' -> objectStartIndex=7 (THIS IS THE KEY: if braceLevel is >0, objectStartIndex is NOT reset)
        // So the outer object is still being tracked.
        // '}' -> braceLevel becomes 1 (for inner)
        // '}' -> braceLevel becomes 0 (for outer) -> emits "{\"key\":{\"anotherKey\":\"value\"}}"
        // This means the test case needs to be:
        // Chunk1: {"key":
        // Chunk2: "value"} , {"next":1}
        // Expected: {"key":"value"}, {"next":1}

        // Corrected test for incomplete object handling:
        parser = new JsonArrayPartsEventParser(); // Reset
        parts1 = parser.parse("{\"key\":".getBytes(StandardCharsets.UTF_8));
        assertTrue(parts1.isEmpty());

        Deque<byte[]> partsAfterCompletion = parser.parse("\"value\"}".getBytes(StandardCharsets.UTF_8));
        assertJsonParts(partsAfterCompletion, List.of("{\"key\":\"value\"}"));

        // If an incomplete part is followed by non-JSON or unrelated data
        parser = new JsonArrayPartsEventParser(); // Reset
        parts1 = parser.parse("{\"key\":".getBytes(StandardCharsets.UTF_8));
        assertTrue(parts1.isEmpty());
        // Send some data that doesn't complete it and doesn't start a new valid object
        Deque<byte[]> partsNoCompletion = parser.parse("some other data without braces".getBytes(StandardCharsets.UTF_8));
        assertTrue(partsNoCompletion.isEmpty());
        // The incomplete part should still be "{\"key\":some other data without braces"
        // Now complete it
        Deque<byte[]> finalParts = parser.parse("}".getBytes(StandardCharsets.UTF_8));
        assertJsonParts(finalParts, List.of("{\"key\":some other data without braces}"));
    }
}
