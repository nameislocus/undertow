/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.protocol.ajp;

import io.undertow.util.HttpString;

import java.nio.ByteBuffer;

/**
 * @author Stuart Douglas
 */
public abstract class AbstractAjpParser {

    public static final int STRING_LENGTH_MASK = 1 << 31;

    protected IntegerHolder parse16BitInteger(ByteBuffer buf, AbstractAjpParseState state) {
        if (!buf.hasRemaining()) {
            return new IntegerHolder(-1, false);
        }
        int number = state.currentIntegerPart;
        if (number == -1) {
            number = (buf.get() & 0xFF);
        }
        if (buf.hasRemaining()) {
            final byte b = buf.get();
            int result = ((0xFF & number) << 8) + (b & 0xFF);
            state.currentIntegerPart = -1;
            return new IntegerHolder(result, true);
        } else {
            state.currentIntegerPart = number;
            return new IntegerHolder(-1, false);
        }
    }

    protected StringHolder parseString(ByteBuffer buf, AbstractAjpParseState state, String encoding, boolean header) {
        boolean containsUrlCharacters = state.containsUrlCharacters;
        if (!buf.hasRemaining()) {
            return new StringHolder(null, false, false);
        }
        int stringLength = state.stringLength;
        if (stringLength == -1) {
            int number = buf.get() & 0xFF;
            if (buf.hasRemaining()) {
                final byte b = buf.get();
                stringLength = ((0xFF & number) << 8) + (b & 0xFF);
            } else {
                state.stringLength = number | STRING_LENGTH_MASK;
                return new StringHolder(null, false, false);
            }
        } else if ((stringLength & STRING_LENGTH_MASK) != 0) {
            int number = stringLength & ~STRING_LENGTH_MASK;
            stringLength = ((0xFF & number) << 8) + (buf.get() & 0xFF);
        }
        if (header && (stringLength & 0xFF00) != 0) {
            state.stringLength = -1;
            return new StringHolder(headers(stringLength & 0xFF));
        }
        if (stringLength == 0xFFFF) {
            //OxFFFF means null
            state.stringLength = -1;
            return new StringHolder(null, true, false);
        }
        StringBuilder builder = state.currentString;

        if (builder == null) {
            builder = new StringBuilder();
            state.currentString = builder;
        }
        int length = builder.length();

        byte[] b = new byte[stringLength];
        int count = 0;

        while (length < stringLength) {
            if (!buf.hasRemaining()) {
                state.stringLength = stringLength;
                state.containsUrlCharacters = containsUrlCharacters;
                return new StringHolder(null, false, false);
            }
            byte c = buf.get();
            if((char)c == '+' || (char)c == '%') {
                containsUrlCharacters = true;
            }
            b[count++] = c;
            ++length;
        }

        try {
            builder.append(new String(b, encoding));
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (buf.hasRemaining()) {
            buf.get(); //null terminator
            state.currentString = null;
            state.stringLength = -1;
            state.containsUrlCharacters = false;
            return new StringHolder(builder.toString(), true, containsUrlCharacters);
        } else {
            state.stringLength = stringLength;
            state.containsUrlCharacters = containsUrlCharacters;
            return new StringHolder(null, false, false);
        }
    }

    protected abstract HttpString headers(int offset);

    protected static class IntegerHolder {
        public final int value;
        public final boolean readComplete;

        private IntegerHolder(int value, boolean readComplete) {
            this.value = value;
            this.readComplete = readComplete;
        }
    }

    protected static class StringHolder {
        public final String value;
        public final HttpString header;
        public final boolean readComplete;
        public final boolean containsUrlCharacters;

        private StringHolder(String value, boolean readComplete, boolean containsUrlCharacters) {
            this.value = value;
            this.readComplete = readComplete;
            this.containsUrlCharacters = containsUrlCharacters;
            this.header = null;
        }

        private StringHolder(HttpString value) {
            this.value = null;
            this.readComplete = true;
            this.header = value;
            this.containsUrlCharacters = false;
        }
    }
}
