/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.multivalue;

import org.elasticsearch.xpack.esql.expression.AbstractExpressionSerializationTests;

import java.io.IOException;

public class MvCountSerializationTests extends AbstractExpressionSerializationTests<MvCount> {
    @Override
    protected MvCount createTestInstance() {
        return new MvCount(randomSource(), randomChild());
    }

    @Override
    protected MvCount mutateInstance(MvCount instance) throws IOException {
        return new MvCount(instance.source(), randomValueOtherThan(instance.field(), AbstractExpressionSerializationTests::randomChild));
    }
}
