/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.googlevertexai.request;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.inference.UnifiedCompletionRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.inference.external.http.sender.UnifiedChatInput;
import org.elasticsearch.xpack.inference.services.googlevertexai.completion.GoogleVertexAiChatCompletionModel;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.core.Strings.format;

public class GoogleVertexAiUnifiedChatCompletionRequestEntity implements ToXContentObject {
    // Field names matching the Google Vertex AI API structure
    private static final String CONTENTS = "contents";
    private static final String ROLE = "role";
    private static final String PARTS = "parts";
    private static final String TEXT = "text";
    private static final String GENERATION_CONFIG = "generationConfig";
    private static final String TEMPERATURE = "temperature";
    private static final String MAX_OUTPUT_TOKENS = "maxOutputTokens";
    private static final String TOP_P = "topP";
    // TODO: Add other generationConfig fields if needed (e.g., stopSequences, topK)

    private final UnifiedChatInput unifiedChatInput;
    private final GoogleVertexAiChatCompletionModel model; // TODO: This is not being used?

    private static final String USER_ROLE = "user";
    private static final String MODEL_ROLE = "model";
    private static final String STOP_SEQUENCES = "stopSequences";

    public GoogleVertexAiUnifiedChatCompletionRequestEntity(UnifiedChatInput unifiedChatInput, GoogleVertexAiChatCompletionModel model) {
        this.unifiedChatInput = Objects.requireNonNull(unifiedChatInput);
        this.model = Objects.requireNonNull(model); // Keep the model reference
    }

    private String messageRoleToGoogleVertexAiSupportedRole(String messageRole) throws IOException {
        var messageRoleLowered = messageRole.toLowerCase();

        if (messageRoleLowered.equals(USER_ROLE) || messageRoleLowered.equals(MODEL_ROLE)) {
            return messageRoleLowered;
        }

        var errorMessage =
            format(
                "Role [%s] not supported by Google VertexAI ChatCompletion. Supported roles: [%s, %s]",
                messageRole,
                USER_ROLE,
                MODEL_ROLE
            );
        throw new ElasticsearchStatusException(errorMessage, RestStatus.BAD_REQUEST);
    }

    private void validateAndAddContentObjectsToBuilder(XContentBuilder builder, UnifiedCompletionRequest.ContentObjects contentObjects)
        throws IOException {

        for (var contentObject : contentObjects.contentObjects()) {
            if (contentObject.type().equals(TEXT) == false) {
                var errorMessage = format(
                    "Type [%s] not supported by Google VertexAI ChatCompletion. Supported types: [text]",
                    contentObject.type()
                );
                throw new ElasticsearchStatusException(errorMessage, RestStatus.BAD_REQUEST);
            }
            // We are only supporting Text messages but VertexAI supports more types:
            // https://cloud.google.com/vertex-ai/docs/reference/rest/v1/Content?_gl=1*q4uxnh*_up*MQ..&gclid=CjwKCAjwwqfABhBcEiwAZJjC3uBQNP9KUMZX8AGXvFXP2rIEQSfCX9RLP5gjzx5r-4xz1daBSxM7GBoCY64QAvD_BwE&gclsrc=aw.ds#Part
            builder.startObject();
            builder.field(TEXT, contentObject.text());
            builder.endObject();
        }

    }

    private void buildContents(XContentBuilder builder) throws IOException {
        var messages = unifiedChatInput.getRequest().messages();

        builder.startArray(CONTENTS);
        for (UnifiedCompletionRequest.Message message : messages) {
            builder.startObject();
            builder.field(ROLE, messageRoleToGoogleVertexAiSupportedRole(message.role()));
            builder.startArray(PARTS);
            switch (message.content()) {
                case UnifiedCompletionRequest.ContentString contentString -> {
                    builder.startObject();
                    builder.field(TEXT, contentString.content());
                    builder.endObject();
                }
                case UnifiedCompletionRequest.ContentObjects contentObjects -> validateAndAddContentObjectsToBuilder(
                    builder,
                    contentObjects
                );
                case null -> {
                    var errorMessage = "Google VertexAI API requires at least one text message but none were provided";
                    throw new ElasticsearchStatusException(errorMessage, RestStatus.BAD_REQUEST);
                }
            }
            builder.endArray();
            builder.endObject();
        }
        builder.endArray();
    }

    private void buildGenerationConfig(XContentBuilder builder) throws IOException {
        var request = unifiedChatInput.getRequest();

        boolean hasAnyConfig = request.stop() != null
            || request.temperature() != null
            || request.maxCompletionTokens() != null
            || request.topP() != null;

        if (hasAnyConfig == false) {
            return;
        }

        builder.startObject(GENERATION_CONFIG);

        if (request.stop() != null) {
            builder.stringListField(STOP_SEQUENCES, request.stop());
        }
        if (request.temperature() != null) {
            builder.field(TEMPERATURE, request.temperature());
        }
        if (request.maxCompletionTokens() != null) {
            builder.field(MAX_OUTPUT_TOKENS, request.maxCompletionTokens());
        }
        if (request.topP() != null) {
            builder.field(TOP_P, request.topP());
        }

        builder.endObject();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();

        buildContents(builder);
        buildGenerationConfig(builder);

        builder.endObject();
        return builder;
    }
}
