/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.googlevertexai.request;

import org.apache.commons.lang3.NotImplementedException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.inference.UnifiedCompletionRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.inference.external.http.sender.UnifiedChatInput;
import org.elasticsearch.xpack.inference.services.googlevertexai.completion.GoogleVertexAiChatCompletionModel;
import org.elasticsearch.xpack.inference.services.googlevertexai.completion.GoogleVertexAiChatCompletionModelTests;
import org.elasticsearch.xpack.inference.services.googlevertexai.completion.GoogleVertexAiChatCompletionServiceSettings;
import org.elasticsearch.xpack.inference.services.googlevertexai.completion.GoogleVertexAiChatCompletionTaskSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.xpack.inference.Utils.assertJsonEquals;
import static org.hamcrest.Matchers.containsString;

public class GoogleVertexAiUnifiedChatCompletionRequestEntityTests extends ESTestCase {

    private static final String USER_ROLE = "user";
    private static final String MODEL_ROLE = "model";

    private GoogleVertexAiChatCompletionModel createModel() {
        // The actual values here don't matter for serialization logic,
        // as the model isn't directly used for generating the request body fields in this entity.
        return GoogleVertexAiChatCompletionModelTests.createCompletionModel("projectID", "location", "modelId", "modelName", null);
    }

    public void testBasicSerialization_SingleMessage() throws IOException {
        UnifiedCompletionRequest.Message message = new UnifiedCompletionRequest.Message(
            new UnifiedCompletionRequest.ContentString("Hello, Vertex AI!"),
            USER_ROLE,
            null,
            null
        );
        var messageList = new ArrayList<UnifiedCompletionRequest.Message>();
        messageList.add(message);

        var unifiedRequest = UnifiedCompletionRequest.of(messageList);
        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(unifiedRequest, true); // stream doesn't affect VertexAI request body
        var model = createModel();
        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        entity.toXContent(builder, ToXContent.EMPTY_PARAMS);

        String jsonString = Strings.toString(builder);
        String expectedJson = """
            {
                "contents": [
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": "Hello, Vertex AI!"
                            }
                        ]
                    }
                ]
            }
            """;
        assertJsonEquals(jsonString, expectedJson);
    }

    public void testSerialization_MultipleMessages() throws IOException {
        var messages = List.of(
            new UnifiedCompletionRequest.Message(
                new UnifiedCompletionRequest.ContentString("Previous user message."),
                USER_ROLE,
                null,
                null
            ),
            new UnifiedCompletionRequest.Message(
                new UnifiedCompletionRequest.ContentString("Previous model response."),
                MODEL_ROLE,
                null,
                null
            ),
            new UnifiedCompletionRequest.Message(new UnifiedCompletionRequest.ContentString("Current user query."), USER_ROLE, null, null)
        );

        var unifiedRequest = UnifiedCompletionRequest.of(messages);
        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(unifiedRequest, false);
        GoogleVertexAiChatCompletionModel model = createModel();

        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        entity.toXContent(builder, ToXContent.EMPTY_PARAMS);

        String jsonString = Strings.toString(builder);
        String expectedJson = """
            {
                "contents": [
                    {
                        "role": "user",
                        "parts": [ { "text": "Previous user message." } ]
                    },
                    {
                        "role": "model",
                        "parts": [ { "text": "Previous model response." } ]
                    },
                    {
                        "role": "user",
                        "parts": [ { "text": "Current user query." } ]
                    }
                ]
            }
            """;
        assertJsonEquals(jsonString, expectedJson);
    }

    public void testSerialization_WithAllGenerationConfig() throws IOException {
        List<UnifiedCompletionRequest.Message> messages = List.of(
            new UnifiedCompletionRequest.Message(new UnifiedCompletionRequest.ContentString("Hello Gemini!"), USER_ROLE, null, null)
        );
        var completionRequestWithGenerationConfig = new UnifiedCompletionRequest(
            messages,
            "modelId",
            100L,
            List.of("stop1", "stop2"),
            0.5f,
            null,
            null,
            0.9F
        );

        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(completionRequestWithGenerationConfig, true);
        GoogleVertexAiChatCompletionModel model = createModel();

        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        entity.toXContent(builder, ToXContent.EMPTY_PARAMS);

        String jsonString = Strings.toString(builder);
        String expectedJson = """
            {
                "contents": [
                    {
                        "role": "user",
                        "parts": [ { "text": "Hello Gemini!" } ]
                    }
                ],
                "generationConfig": {
                    "stopSequences": ["stop1", "stop2"],
                    "temperature": 0.5,
                    "maxOutputTokens": 100,
                    "topP": 0.9
                }
            }
            """;
        assertJsonEquals(jsonString, expectedJson);
    }

    public void testSerialization_WithSomeGenerationConfig() throws IOException {
        UnifiedCompletionRequest.Message message = new UnifiedCompletionRequest.Message(
            new UnifiedCompletionRequest.ContentString("Partial config."),
            USER_ROLE,
            null,
            null
        );
        var completionRequestWithGenerationConfig = new UnifiedCompletionRequest(
            List.of(message),
            "modelId",
            50L,
            null,
            0.7f,
            null,
            null,
            null
        );

        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(completionRequestWithGenerationConfig, true);
        GoogleVertexAiChatCompletionModel model = createModel();

        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        entity.toXContent(builder, ToXContent.EMPTY_PARAMS);

        String jsonString = Strings.toString(builder);
        String expectedJson = """
            {
                "contents": [
                    {
                        "role": "user",
                        "parts": [ { "text": "Partial config." } ]
                    }
                ],
                "generationConfig": {
                    "temperature": 0.7,
                    "maxOutputTokens": 50
                }
            }
            """;
        assertJsonEquals(jsonString, expectedJson);
    }

    public void testSerialization_NoGenerationConfig() throws IOException {
        UnifiedCompletionRequest.Message message = new UnifiedCompletionRequest.Message(
            new UnifiedCompletionRequest.ContentString("No extra config."),
            USER_ROLE,
            null,
            null
        );
        // No generation config fields set on unifiedRequest
        var unifiedRequest = UnifiedCompletionRequest.of(List.of(message));

        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(unifiedRequest, true);
        GoogleVertexAiChatCompletionModel model = createModel();

        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        entity.toXContent(builder, ToXContent.EMPTY_PARAMS);

        String jsonString = Strings.toString(builder);
        String expectedJson = """
            {
                "contents": [
                    {
                        "role": "user",
                        "parts": [ { "text": "No extra config." } ]
                    }
                ]
            }
            """;
        assertJsonEquals(jsonString, expectedJson);
    }

    public void testSerialization_WithContentObjects() throws IOException {
        var contentObjects = List.of(
            new UnifiedCompletionRequest.ContentObject("First part. ", "text"),
            new UnifiedCompletionRequest.ContentObject("Second part.", "text")
        );
        UnifiedCompletionRequest.Message message = new UnifiedCompletionRequest.Message(
            new UnifiedCompletionRequest.ContentObjects(contentObjects),
            USER_ROLE,
            null,
            null
        );
        var messageList = new ArrayList<UnifiedCompletionRequest.Message>();
        messageList.add(message);

        var unifiedRequest = UnifiedCompletionRequest.of(messageList);
        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(unifiedRequest, true);
        GoogleVertexAiChatCompletionModel model = createModel();

        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        entity.toXContent(builder, ToXContent.EMPTY_PARAMS);

        String jsonString = Strings.toString(builder);
        String expectedJson = """
            {
                "contents": [
                    {
                        "role": "user",
                        "parts": [
                            { "text": "First part. " },
                            { "text": "Second part." }
                        ]
                    }
                ]
            }
            """;
        assertJsonEquals(jsonString, expectedJson);
    }

    public void testError_UnsupportedRole() throws IOException {
        var unsupportedRole = "system";
        UnifiedCompletionRequest.Message message = new UnifiedCompletionRequest.Message(
            new UnifiedCompletionRequest.ContentString("Test"),
            unsupportedRole,
            null,
            null
        );
        var unifiedRequest = UnifiedCompletionRequest.of(List.of(message));
        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(unifiedRequest, false);
        GoogleVertexAiChatCompletionModel model = createModel();

        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        var statusException = assertThrows(ElasticsearchStatusException.class, () -> entity.toXContent(builder, ToXContent.EMPTY_PARAMS));

        assertEquals(RestStatus.BAD_REQUEST, statusException.status());
        assertThat(statusException.toString(), containsString("Role [system] not supported by Google VertexAI ChatCompletion"));
    }

    public void testError_UnsupportedContentObjectType() throws IOException {
        var contentObjects = List.of(new UnifiedCompletionRequest.ContentObject("http://example.com/image.png", "image_url"));
        UnifiedCompletionRequest.Message message = new UnifiedCompletionRequest.Message(
            new UnifiedCompletionRequest.ContentObjects(contentObjects),
            USER_ROLE,
            null,
            null
        );
        var unifiedRequest = UnifiedCompletionRequest.of(List.of(message));
        UnifiedChatInput unifiedChatInput = new UnifiedChatInput(unifiedRequest, false);
        GoogleVertexAiChatCompletionModel model = createModel();

        GoogleVertexAiUnifiedChatCompletionRequestEntity entity = new GoogleVertexAiUnifiedChatCompletionRequestEntity(
            unifiedChatInput,
            model
        );

        XContentBuilder builder = JsonXContent.contentBuilder();
        var statusException = assertThrows(ElasticsearchStatusException.class, () -> entity.toXContent(builder, ToXContent.EMPTY_PARAMS));

        assertEquals(RestStatus.BAD_REQUEST, statusException.status());
        assertThat(statusException.toString(), containsString("Type [image_url] not supported by Google VertexAI ChatCompletion"));

    }

}
