/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.googlevertexai.completion;

import org.apache.commons.lang3.NotImplementedException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.inference.TaskSettings;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;

// TODO: This class may no be needed. Keeping this class here to keep the compiler happy, but if not needed we could replace it with `EmptyTaskSettings`
public class GoogleVertexAiChatCompletionTaskSettings implements TaskSettings {
    public static final String NAME = "google_vertex_ai_chatcompletion_task_settings";

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public TaskSettings updatedTaskSettings(Map<String, Object> newSettings) {
        return null;
    }

    @Override
    public String getWriteableName() {
        return "";
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {

    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return null;
    }

    public static GoogleVertexAiChatCompletionTaskSettings fromMap(Map<String, Object> map) {
        return new GoogleVertexAiChatCompletionTaskSettings();
    }
}
