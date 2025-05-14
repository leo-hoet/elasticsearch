/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.googlevertexai.request;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpPost;
import org.elasticsearch.core.Strings;
import org.elasticsearch.inference.UnifiedCompletionRequest;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.inference.external.http.sender.UnifiedChatInput;
import org.elasticsearch.xpack.inference.services.googlevertexai.completion.GoogleVertexAiChatCompletionModel;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.inference.services.settings.RateLimitSettings;

import java.util.List;

import static org.elasticsearch.xpack.inference.services.googlevertexai.completion.GoogleVertexAiChatCompletionModelTests.createCompletionModel;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.is;

public class GoogleVertexAiUnifiedChatCompletionRequestTests extends ESTestCase {

    private static final String AUTH_HEADER_VALUE = "Bearer foo";
    private static final String TEST_PROJECT_ID = "test-project";
    private static final String TEST_MODEL_ID = "chat-bison";
    private static final String TEST_LOCATION = "us-central1";
    private static final String TEST_API_KEY = "apikey";

    public void testA() {
        var model = createCompletionModel(TEST_PROJECT_ID, TEST_LOCATION, TEST_MODEL_ID, TEST_API_KEY, new RateLimitSettings(100));
        var input = buildUnifiedChatCompletionInput(List.of("Hello"));

        var request = createRequest(input, model);
        var httpRequest = request.createHttpRequest();

        var httpPost = (HttpPost) httpRequest.httpRequestBase();

        var expectedUrl = Strings.format("https://%s-aiplatform.googleapis.com", TEST_LOCATION);
        assertThat(httpPost.getURI().toString(), startsWith(expectedUrl));
        assertThat(httpPost.getLastHeader(HttpHeaders.CONTENT_TYPE).getValue(), is(XContentType.JSON.mediaType()));
        assertThat(httpPost.getLastHeader(HttpHeaders.AUTHORIZATION).getValue(), is(AUTH_HEADER_VALUE));

    }

    private static GoogleVertexAiUnifiedChatCompletionRequest createRequest(
        UnifiedChatInput input,
        GoogleVertexAiChatCompletionModel model
    ) {
        return new GoogleVertexAiUnifiedChatCompletionRequestWithoutAuth(input, model);
    }

    private static UnifiedChatInput buildUnifiedChatCompletionInput(List<String> messages) {
        var requestMessages = messages.stream()
            .map(
                (userStringMessage) -> new UnifiedCompletionRequest.Message(
                    new UnifiedCompletionRequest.ContentString(userStringMessage),
                    "user",
                    null,
                    null
                )
            )
            .toList();

        var request = new UnifiedCompletionRequest(requestMessages, "gemini-2.0", null, null, null, null, null, null);
        return new UnifiedChatInput(request, true);
    }

    private static class GoogleVertexAiUnifiedChatCompletionRequestWithoutAuth extends GoogleVertexAiUnifiedChatCompletionRequest {
        GoogleVertexAiUnifiedChatCompletionRequestWithoutAuth(UnifiedChatInput unifiedChatInput, GoogleVertexAiChatCompletionModel model) {
            super(unifiedChatInput, model);
        }

        @Override
        public void decorateWithAuth(HttpPost httpPost) {
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, AUTH_HEADER_VALUE);
        }
    }
}
