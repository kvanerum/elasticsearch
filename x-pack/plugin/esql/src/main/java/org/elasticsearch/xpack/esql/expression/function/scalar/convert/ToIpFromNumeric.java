/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.convert;

import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.compute.ann.ConvertEvaluator;
import org.elasticsearch.xpack.esql.core.InvalidArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.util.NumericUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.esql.core.type.DataType.INTEGER;
import static org.elasticsearch.xpack.esql.core.type.DataType.IP;
import static org.elasticsearch.xpack.esql.core.type.DataType.LONG;
import static org.elasticsearch.xpack.esql.core.type.DataType.UNSIGNED_LONG;

public class ToIpFromNumeric extends AbstractConvertFunction {
    private static final long MAX_IPV4 = 0xFFFFFFFFL;

    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        Expression.class,
        "ToIpFromNumeric",
        ToIpFromNumeric::new
    );

    static final Map<DataType, BuildFactory> EVALUATORS = Map.ofEntries(
        Map.entry(INTEGER, ToIpFromNumericIntegerEvaluator.Factory::new),
        Map.entry(LONG, ToIpFromNumericLongEvaluator.Factory::new),
        Map.entry(UNSIGNED_LONG, ToIpFromNumericUnsignedLongEvaluator.Factory::new)
    );

    public ToIpFromNumeric(Source source, Expression field) {
        super(source, field);
    }

    private ToIpFromNumeric(StreamInput in) throws IOException {
        super(in);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    protected Map<DataType, BuildFactory> factories() {
        return EVALUATORS;
    }

    @Override
    public DataType dataType() {
        return IP;
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new ToIpFromNumeric(source(), newChildren.getFirst());
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, ToIpFromNumeric::new, field());
    }

    @Override
    public String functionName() {
        return "TO_IP";
    }

    @ConvertEvaluator(extraName = "Integer", warnExceptions = { InvalidArgumentException.class })
    static BytesRef fromInteger(int i) {
        return toBytesRef(ByteBuffer.allocate(4).putInt(i));
    }

    @ConvertEvaluator(extraName = "Long", warnExceptions = { InvalidArgumentException.class })
    static BytesRef fromLong(long l) {
        if (l < Integer.MIN_VALUE || l > MAX_IPV4) {
            throw new InvalidArgumentException("[{}] out of IPv4 range [-2147483648, 4294967295]", l);
        }

        return toBytesRef(ByteBuffer.allocate(4).putInt((int) l));
    }

    @ConvertEvaluator(extraName = "UnsignedLong", warnExceptions = { InvalidArgumentException.class })
    static BytesRef fromUnsignedLong(long ul) {
        Number value = NumericUtils.unsignedLongAsNumber(ul);
        if (value instanceof Long l && l <= MAX_IPV4) {
            return fromLong(l);
        }
        throw new InvalidArgumentException("[{}] out of IPv4 range [0, 4294967295]", value);
    }

    private static BytesRef toBytesRef(ByteBuffer buffer) {
        try {
            return new BytesRef(InetAddressPoint.encode(InetAddress.getByAddress(buffer.array())));
        } catch (UnknownHostException e) {
            throw new AssertionError("4-byte address is always valid", e);
        }
    }
}
