/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.googlevertexai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.inference.results.StreamingUnifiedChatCompletionResults;
import org.elasticsearch.xpack.inference.common.DelegatingProcessor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiFunction;

import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.elasticsearch.xpack.inference.external.response.XContentUtils.moveToFirstToken;

public class GoogleVertexAiUnifiedStreamingProcessor extends DelegatingProcessor<
    Deque<byte[]>,
    StreamingUnifiedChatCompletionResults.Results> {

    private static final Logger logger = LogManager.getLogger(GoogleVertexAiUnifiedStreamingProcessor.class);

    // Response Fields
    private static final String CANDIDATES_FIELD = "candidates";
    private static final String CONTENT_FIELD = "content";
    private static final String ROLE_FIELD = "role";
    private static final String PARTS_FIELD = "parts";
    private static final String TEXT_FIELD = "text";
    private static final String FINISH_REASON_FIELD = "finishReason";
    private static final String INDEX_FIELD = "index";
    private static final String USAGE_METADATA_FIELD = "usageMetadata";
    private static final String PROMPT_TOKEN_COUNT_FIELD = "promptTokenCount";
    private static final String CANDIDATES_TOKEN_COUNT_FIELD = "candidatesTokenCount";
    private static final String TOTAL_TOKEN_COUNT_FIELD = "totalTokenCount";
    private static final String ERROR_FIELD = "error";
    private static final String ERROR_CODE_FIELD = "code";
    private static final String ERROR_MESSAGE_FIELD = "message";
    private static final String ERROR_STATUS_FIELD = "status";

    // Internal representation fields mapping to StreamingUnifiedChatCompletionResults
    // Note: Google Vertex AI doesn't provide chunk ID, model, or object per chunk like OpenAI.
    // We will construct the Choice.Delta based on the Candidate's content.

    private final BiFunction<String, Exception, Exception> errorParser;
    private final Deque<StreamingUnifiedChatCompletionResults.ChatCompletionChunk> buffer = new LinkedBlockingDeque<>();

    public GoogleVertexAiUnifiedStreamingProcessor(BiFunction<String, Exception, Exception> errorParser) {
        this.errorParser = errorParser;
    }

    @Override
    protected void upstreamRequest(long n) {
        if (buffer.isEmpty()) {
            super.upstreamRequest(n);
        } else {
            // Drain buffer first
            downstream().onNext(new StreamingUnifiedChatCompletionResults.Results(singleItem(buffer.poll())));
        }
    }

    @Override
    protected void next(Deque<byte[]> item) throws Exception {

        var parserConfig = XContentParserConfiguration.EMPTY.withDeprecationHandler(LoggingDeprecationHandler.INSTANCE);
        var results = new ArrayDeque<StreamingUnifiedChatCompletionResults.ChatCompletionChunk>(item.size());

        for (var event : item) {
            var completionChunk = parse(parserConfig, event);
            completionChunk.forEachRemaining(results::offer);
        }

        if (results.isEmpty()) {
            // Request more if we didn't produce anything
            upstream().request(1);
        } else if (results.size() == 1) {
            // Common case: one event produced one chunk
            downstream().onNext(new StreamingUnifiedChatCompletionResults.Results(results));
        } else {
            // Unlikely for Vertex AI, but handle buffering just in case
            logger.warn("Received multiple chunks ({}) from a single SSE batch, buffering.", results.size());
            var firstItem = singleItem(results.poll());
            while (results.isEmpty() == false) {
                buffer.offer(results.poll());
            }
            downstream().onNext(new StreamingUnifiedChatCompletionResults.Results(firstItem));
            // If buffer has items, the next upstreamRequest will handle sending them.
        }
    }

    // TODO: This method is already called with valid Json in event. Maybe we dont need the validation logic, just parse the event
    // Leaving this for now but highly guaranteed that this will be removed
    private Iterator<StreamingUnifiedChatCompletionResults.ChatCompletionChunk> parse(
        XContentParserConfiguration parserConfig,
        byte[] event
    ) throws IOException {
        // Google Vertex AI doesn't have a specific "[DONE]" message like OpenAI.
        // The stream ends when the connection closes or a chunk with a final finishReason arrives.

        try (XContentParser jsonParser = XContentFactory.xContent(XContentType.JSON).createParser(parserConfig, event)) {
            moveToFirstToken(jsonParser);
            ensureExpectedToken(XContentParser.Token.START_OBJECT, jsonParser.currentToken(), jsonParser);

            // Check for top-level error first
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = jsonParser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = jsonParser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (ERROR_FIELD.equals(currentFieldName)) {
                        VertexAiError error = VertexAiErrorParser.parse(jsonParser);
                        // Map Google's error to ElasticsearchStatusException
                        // Status mapping might need refinement based on Google's codes
                        RestStatus status = RestStatus.fromCode(error.code() != 0 ? error.code() : 500);
                        throw new ElasticsearchStatusException(
                            "Error from Google Vertex AI: [{}] {}",
                            status,
                            error.message() != null ? error.message() : "Unknown error",
                            status
                        );
                    } else {
                        // If it's not the error field, parse as a regular chunk
                        // We need to reset the parser or re-parse, as we consumed tokens.
                        // Easiest is to re-parse the original data.
                        try (XContentParser chunkParser = XContentFactory.xContent(XContentType.JSON).createParser(parserConfig, event)) {
                            moveToFirstToken(chunkParser);
                            StreamingUnifiedChatCompletionResults.ChatCompletionChunk chunk = GoogleVertexAiChatCompletionChunkParser.parse(
                                chunkParser
                            );
                            // If parsing succeeds but yields no candidates (e.g., empty response), return empty.
                            if (chunk.choices() == null || chunk.choices().isEmpty()) {
                                return Collections.emptyIterator();
                            }
                            return Collections.singleton(chunk).iterator();
                        }
                    }
                } else {
                    // Ignore other top-level fields if any, besides "error" and the main structure
                    jsonParser.skipChildren();
                }
            }
            // If we reach here, it means the object was parsed but didn't match the error structure
            // and didn't trigger the re-parse logic (e.g., empty object {}). Re-parse to be sure.
            try (XContentParser chunkParser = XContentFactory.xContent(XContentType.JSON).createParser(parserConfig, event)) {
                moveToFirstToken(chunkParser);
                StreamingUnifiedChatCompletionResults.ChatCompletionChunk chunk = GoogleVertexAiChatCompletionChunkParser.parse(
                    chunkParser
                );
                // If parsing succeeds but yields no candidates (e.g., empty response), return empty.
                if (chunk.choices() == null || chunk.choices().isEmpty()) {
                    return Collections.emptyIterator();
                }
                return Collections.singleton(chunk).iterator();
            }
        }
    }

    // Helper class to represent Google Vertex AI error structure
    private record VertexAiError(int code, String message, String status) {}

    private static class VertexAiErrorParser {
        private static final ConstructingObjectParser<VertexAiError, Void> PARSER = new ConstructingObjectParser<>(
            ERROR_FIELD,
            true,
            args -> new VertexAiError((int) args[0], (String) args[1], (String) args[2])
        );

        static {
            PARSER.declareInt(ConstructingObjectParser.optionalConstructorArg(), new ParseField(ERROR_CODE_FIELD));
            PARSER.declareString(ConstructingObjectParser.optionalConstructorArg(), new ParseField(ERROR_MESSAGE_FIELD));
            PARSER.declareString(ConstructingObjectParser.optionalConstructorArg(), new ParseField(ERROR_STATUS_FIELD));
            // Ignore unknown fields
        }

        public static VertexAiError parse(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }
    }

    // Main parser for the chunk structure
    private static class GoogleVertexAiChatCompletionChunkParser {
        @SuppressWarnings("unchecked")
        private static final ConstructingObjectParser<StreamingUnifiedChatCompletionResults.ChatCompletionChunk, Void> PARSER =
            new ConstructingObjectParser<>(
                "google_vertexai_chat_completion_chunk",
                true,
                // Args: candidates, usageMetadata
                args -> {
                    List<Candidate> candidates = (List<Candidate>) args[0];
                    UsageMetadata usage = (UsageMetadata) args[1];

                    if (candidates == null || candidates.isEmpty()) {
                        // If there are no candidates, but usage info exists, create a chunk just for usage.
                        if (usage != null) {
                            return new StreamingUnifiedChatCompletionResults.ChatCompletionChunk(
                                null, // No ID from Vertex AI
                                Collections.emptyList(),
                                null, // No model per chunk
                                null, // No object per chunk
                                new StreamingUnifiedChatCompletionResults.ChatCompletionChunk.Usage(
                                    usage.candidatesTokenCount(),
                                    usage.promptTokenCount(),
                                    usage.totalTokenCount()
                                )
                            );
                        }
                        // Return a mostly empty chunk if no candidates and no usage
                        return new StreamingUnifiedChatCompletionResults.ChatCompletionChunk(
                            null,
                            Collections.emptyList(),
                            null,
                            null,
                            null
                        );
                    }

                    // Map candidates to choices
                    List<StreamingUnifiedChatCompletionResults.ChatCompletionChunk.Choice> choices = candidates.stream().map(candidate -> {
                        String contentText = null;
                        String role = null;
                        if (candidate.content() != null
                            && candidate.content().parts() != null
                            && candidate.content().parts().isEmpty() == false) {
                            // Assuming only one part with text for now
                            contentText = candidate.content().parts().get(0).text();
                            role = candidate.content().role();
                        }

                        var delta = new StreamingUnifiedChatCompletionResults.ChatCompletionChunk.Choice.Delta(
                            contentText,
                            null, // No refusal field in Vertex AI
                            role,
                            null // TODO: Handle tool/function calls if they appear in streaming
                        );

                        return new StreamingUnifiedChatCompletionResults.ChatCompletionChunk.Choice(
                            delta,
                            candidate.finishReason(),
                            candidate.index()
                        );
                    }).toList();

                    StreamingUnifiedChatCompletionResults.ChatCompletionChunk.Usage usageResult = null;
                    if (usage != null) {
                        usageResult = new StreamingUnifiedChatCompletionResults.ChatCompletionChunk.Usage(
                            usage.candidatesTokenCount(),
                            usage.promptTokenCount(),
                            usage.totalTokenCount()
                        );
                    }

                    return new StreamingUnifiedChatCompletionResults.ChatCompletionChunk(
                        null, // No ID from Vertex AI
                        choices,
                        null, // No model per chunk
                        null, // No object per chunk
                        usageResult
                    );
                }
            );

        static {
            PARSER.declareObjectArray(
                ConstructingObjectParser.optionalConstructorArg(), // Candidates might be absent
                (p, c) -> CandidateParser.parse(p),
                new ParseField(CANDIDATES_FIELD)
            );
            PARSER.declareObject(
                ConstructingObjectParser.optionalConstructorArg(), // Usage might be absent until the end
                (p, c) -> UsageMetadataParser.parse(p),
                new ParseField(USAGE_METADATA_FIELD)
            );
            // Ignore other top-level fields like safetyRatings, citationMetadata etc.
        }

        public static StreamingUnifiedChatCompletionResults.ChatCompletionChunk parse(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }
    }

    // --- Nested Parsers for Google Vertex AI structure ---

    private record Candidate(Content content, String finishReason, int index) {}

    private static class CandidateParser {
        private static final ConstructingObjectParser<Candidate, Void> PARSER = new ConstructingObjectParser<>(
            "candidate",
            true,
            args -> new Candidate((Content) args[0], (String) args[1], args[2] == null ? 0 : (int) args[2]) // index might be null
        );

        static {
            PARSER.declareObject(
                ConstructingObjectParser.optionalConstructorArg(),
                (p, c) -> ContentParser.parse(p),
                new ParseField(CONTENT_FIELD)
            );
            PARSER.declareString(ConstructingObjectParser.optionalConstructorArg(), new ParseField(FINISH_REASON_FIELD));
            PARSER.declareInt(ConstructingObjectParser.optionalConstructorArg(), new ParseField(INDEX_FIELD));
            // Ignore safetyRatings, citationMetadata, etc.
        }

        public static Candidate parse(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }
    }

    private record Content(String role, List<Part> parts) {}

    private static class ContentParser {
        @SuppressWarnings("unchecked")
        private static final ConstructingObjectParser<Content, Void> PARSER = new ConstructingObjectParser<>(
            CONTENT_FIELD,
            true,
            args -> new Content((String) args[0], (List<Part>) args[1])
        );

        static {
            PARSER.declareString(ConstructingObjectParser.optionalConstructorArg(), new ParseField(ROLE_FIELD));
            PARSER.declareObjectArray(
                ConstructingObjectParser.optionalConstructorArg(),
                (p, c) -> PartParser.parse(p),
                new ParseField(PARTS_FIELD)
            );
        }

        public static Content parse(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }
    }

    private record Part(String text) {} // Assuming only text parts for now

    private static class PartParser {
        private static final ConstructingObjectParser<Part, Void> PARSER = new ConstructingObjectParser<>(
            "part",
            true,
            args -> new Part((String) args[0])
        );

        static {
            PARSER.declareString(ConstructingObjectParser.optionalConstructorArg(), new ParseField(TEXT_FIELD));
            // Ignore other part types like functionCall, functionResponse, fileData, etc. for now
        }

        public static Part parse(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }
    }

    private record UsageMetadata(int promptTokenCount, int candidatesTokenCount, int totalTokenCount) {}

    private static class UsageMetadataParser {
        private static final ConstructingObjectParser<UsageMetadata, Void> PARSER = new ConstructingObjectParser<>(
            USAGE_METADATA_FIELD,
            true,
            args -> new UsageMetadata(
                args[0] == null ? 0 : (int) args[0],
                args[1] == null ? 0 : (int) args[1],
                args[2] == null ? 0 : (int) args[2]
            )
        );

        static {
            PARSER.declareInt(ConstructingObjectParser.optionalConstructorArg(), new ParseField(PROMPT_TOKEN_COUNT_FIELD));
            PARSER.declareInt(ConstructingObjectParser.optionalConstructorArg(), new ParseField(CANDIDATES_TOKEN_COUNT_FIELD));
            PARSER.declareInt(ConstructingObjectParser.optionalConstructorArg(), new ParseField(TOTAL_TOKEN_COUNT_FIELD));
        }

        public static UsageMetadata parse(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }
    }

    // Helper to wrap a single chunk in a Deque
    private Deque<StreamingUnifiedChatCompletionResults.ChatCompletionChunk> singleItem(
        StreamingUnifiedChatCompletionResults.ChatCompletionChunk result
    ) {
        var deque = new ArrayDeque<StreamingUnifiedChatCompletionResults.ChatCompletionChunk>(1);
        deque.offer(result);
        return deque;
    }
}
