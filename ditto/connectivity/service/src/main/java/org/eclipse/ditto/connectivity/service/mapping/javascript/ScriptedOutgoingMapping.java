/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.connectivity.service.mapping.javascript;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.script.Bindings;

import org.eclipse.ditto.connectivity.api.ExternalMessage;
import org.eclipse.ditto.connectivity.api.ExternalMessageBuilder;
import org.eclipse.ditto.connectivity.api.ExternalMessageFactory;
import org.eclipse.ditto.connectivity.model.MessageMappingFailedException;
import org.eclipse.ditto.connectivity.service.mapping.MessageMapper;
import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonArrayBuilder;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonObjectBuilder;
import org.eclipse.ditto.json.JsonParseException;
import org.eclipse.ditto.protocol.Adaptable;
import org.eclipse.ditto.protocol.JsonifiableAdaptable;
import org.eclipse.ditto.protocol.ProtocolFactory;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.ConsString;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.typedarrays.NativeArrayBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mapping function for outgoing messages based on JavaScript.
 */
public final class ScriptedOutgoingMapping implements MappingFunction<Adaptable, List<ExternalMessage>> {

    private static final String EXTERNAL_MESSAGE_HEADERS = "headers";
    private static final String EXTERNAL_MESSAGE_CONTENT_TYPE = "contentType";
    private static final String EXTERNAL_MESSAGE_TEXT_PAYLOAD = "textPayload";
    private static final String EXTERNAL_MESSAGE_BYTE_PAYLOAD = "bytePayload";

    private static final String OUTGOING_FUNCTION_NAME = "mapFromDittoProtocolMsgWrapper";
    private static final Logger log = LoggerFactory.getLogger(ScriptedOutgoingMapping.class);

    @Nullable private final ContextFactory contextFactory;
    @Nullable private final Scriptable scope;

    ScriptedOutgoingMapping(@Nullable final ContextFactory contextFactory, @Nullable final Scriptable scope) {
        this.contextFactory = contextFactory;
        this.scope = scope;
    }

    @Override
    public List<ExternalMessage> apply(final Adaptable adaptable) {
        try {
            final JsonifiableAdaptable jsonifiableAdaptable = ProtocolFactory.wrapAsJsonifiableAdaptable(adaptable);
            return contextFactory.call(cx -> {
                final Object dittoProtocolMessage =
                        NativeJSON.parse(cx, scope, jsonifiableAdaptable.toJsonString(), new NullCallable());

                final org.mozilla.javascript.Function mapFromDittoProtocolMsgWrapper =
                        (org.mozilla.javascript.Function) scope.get(OUTGOING_FUNCTION_NAME, scope);
                final Object result =
                        mapFromDittoProtocolMsgWrapper.call(cx, scope, scope, new Object[]{dittoProtocolMessage});

                if (result == null) {
                    // return empty list if result is null
                    return Collections.emptyList();
                } else if (result instanceof NativeArray nativeArray) {
                    // handle array
                    final List<ExternalMessage> list = new ArrayList<>();
                    for (Object idxObj : nativeArray.getIds()) {
                        int index = (Integer) idxObj;
                        final Object element = nativeArray.get(index, null);
                        list.add(getExternalMessageFromObject(adaptable, (NativeObject) element));
                    }
                    return list;
                }
                return Collections.singletonList(getExternalMessageFromObject(adaptable, (NativeObject) result));
            });
        } catch (final RhinoException e) {
            throw buildMessageMappingFailedException(e, MessageMapper.findContentType(adaptable).orElse(""),
                    adaptable.getDittoHeaders());
        } catch (final Throwable e) {
            throw MessageMappingFailedException.newBuilder(MessageMapper.findContentType(adaptable).orElse(""))
                    .description(e.getMessage())
                    .dittoHeaders(adaptable.getDittoHeaders())
                    .cause(e)
                    .build();
        }
    }

    private ExternalMessage getExternalMessageFromObject(final Adaptable adaptable, final NativeObject result) {
        final Object contentType = result.get(EXTERNAL_MESSAGE_CONTENT_TYPE);
        final Object textPayload = result.get(EXTERNAL_MESSAGE_TEXT_PAYLOAD);
        final Object bytePayload = result.get(EXTERNAL_MESSAGE_BYTE_PAYLOAD);
        final Object mappingHeaders = result.get(EXTERNAL_MESSAGE_HEADERS);

        final Map<String, String> headers = new HashMap<>();
        if (mappingHeaders instanceof NativeObject nativeHeaders) {
            toJsonObject(nativeHeaders)
                    .stream()
                    .forEach(header ->
                        headers.put(header.getKeyName(), header.getValue().formatAsString())
                    );
        } else if (mappingHeaders != null && !(mappingHeaders instanceof Undefined)) {
            final Map<?,?> jsHeaders = (Map<?,?>) mappingHeaders;
            jsHeaders.forEach((key, value) -> headers.put(String.valueOf(key), String.valueOf(value)));
        }

        final ExternalMessageBuilder messageBuilder =
                ExternalMessageFactory.newExternalMessageBuilder(headers);

        if (!(contentType instanceof Undefined)) {
            messageBuilder.withAdditionalHeaders(ExternalMessage.CONTENT_TYPE_HEADER,
                    ((CharSequence) contentType).toString());
        }

        final Optional<ByteBuffer> byteBuffer = convertToByteBuffer(bytePayload);
        if (byteBuffer.isPresent()) {
            messageBuilder.withBytes(byteBuffer.get());
        } else if (!(textPayload instanceof Undefined)) {
            messageBuilder.withText(((CharSequence) textPayload).toString());
        } else {
            throw MessageMappingFailedException.newBuilder("")
                    .description("Neither <bytePayload> nor <textPayload> were defined in the outgoing script")
                    .dittoHeaders(adaptable.getDittoHeaders())
                    .build();
        }

        return messageBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private static JsonObject toJsonObject(final NativeObject nativeObject) {
        final JsonObjectBuilder objectBuilder = JsonObject.newBuilder();
        nativeObject.forEach((key, value) -> {
            try {
                if (value instanceof String) {
                    objectBuilder.set(key.toString(), JsonFactory.newValue(value.toString()));
                } else if (value instanceof ConsString consString) {
                    objectBuilder.set(key.toString(), JsonFactory.newValue(consString.toString()));
                } else if (value instanceof NativeArray nativeArray) {
                    objectBuilder.set(key.toString(), toJsonArray(nativeArray));
                } else if (value instanceof NativeObject nativeSubObject) {
                    objectBuilder.set(key.toString(), toJsonObject(nativeSubObject));
                } else if (value instanceof Boolean boolValue) {
                    objectBuilder.set(key.toString(), JsonFactory.newValue(boolValue));
                } else if (value instanceof Integer intValue) {
                    objectBuilder.set(key.toString(), JsonFactory.newValue(intValue));
                } else if (value instanceof Double doubleValue) {
                    objectBuilder.set(key.toString(), JsonFactory.newValue(doubleValue));
                } else if (value == null || value instanceof Undefined) {
                    objectBuilder.set(key.toString(), JsonFactory.nullLiteral());
                } else {
                    if (log.isDebugEnabled()){
                        log.debug("Unsupported type: {}, adding as string: {}", value.getClass().getName(), value);
                    }
                    objectBuilder.set(key.toString(), value.toString());
                }
            } catch (final JsonParseException e) {
                if (value != null) {
                    objectBuilder.set(key.toString(), value.toString());
                } else {
                    objectBuilder.set(key.toString(), JsonFactory.nullLiteral());
                }
            }
        });
        return objectBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private static JsonArray toJsonArray(final NativeArray nativeArray) {
        final JsonArrayBuilder arrayBuilder = JsonArray.newBuilder();
        nativeArray.forEach(item -> {
            switch (item) {
                case NativeObject nativeObject -> arrayBuilder.add(toJsonObject(nativeObject));
                case NativeArray nativeSubArray -> arrayBuilder.add(toJsonArray(nativeSubArray));
                default -> {
                    try {
                        arrayBuilder.add(JsonFactory.readFrom(item.toString()));
                    } catch (final JsonParseException e) {
                        arrayBuilder.add(item.toString());
                    }
                }
            }
        });
        return arrayBuilder.build();
    }

    private static Optional<ByteBuffer> convertToByteBuffer(final Object obj) {
        if (obj instanceof NativeArrayBuffer nativeArrayBuffer) {
            return Optional.of(ByteBuffer.wrap(nativeArrayBuffer.getBuffer()));
        } else if (obj instanceof Bindings) {
            try {
                final Class<?> cls = Class.forName("jdk.nashorn.api.scripting.ScriptObjectMirror");
                if (cls.isAssignableFrom(obj.getClass())) {
                    final Method isArray = cls.getMethod("isArray");
                    final Object result = isArray.invoke(obj);
                    if (result != null && result.equals(true)) {
                        final Method values = cls.getMethod("values");
                        final Object vals = values.invoke(obj);
                        if (vals instanceof Collection) {
                            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            final Iterable<?> coll = (Iterable<?>) vals;
                            coll.forEach(e -> baos.write(((Number) e).intValue()));
                            return Optional.of(ByteBuffer.wrap(baos.toByteArray()));
                        }
                    }
                }
            } catch (final ClassNotFoundException | NoSuchMethodException | SecurityException
                           | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new IllegalStateException("Could not retrieve array values", e);
            }
        } else if (obj instanceof List<?>) {
            final Iterable<?> list = (Iterable<?>) obj;
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            list.forEach(e -> baos.write(((Number) e).intValue()));
            return Optional.of(ByteBuffer.wrap(baos.toByteArray()));
        }
        return Optional.empty();
    }

    private static final class NullCallable implements Callable {

        @Override
        public Object call(final Context context, final Scriptable scope, final Scriptable holdable,
                final Object[] objects) {
            return objects[1];
        }
    }

}
