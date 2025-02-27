/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.config.impl;

import org.apache.seatunnel.config.ConfigException.BugOrBroken;
import org.apache.seatunnel.config.ConfigOrigin;

class Token {
    private final TokenType tokenType;
    private final String debugString;
    private final ConfigOrigin origin;
    private final String tokenText;

    Token(TokenType tokenType, ConfigOrigin origin) {
        this(tokenType, origin, null);
    }

    Token(TokenType tokenType, ConfigOrigin origin, String tokenText) {
        this(tokenType, origin, tokenText, null);
    }

    Token(TokenType tokenType, ConfigOrigin origin, String tokenText, String debugString) {
        this.tokenType = tokenType;
        this.origin = origin;
        this.debugString = debugString;
        this.tokenText = tokenText;
    }

    // this is used for singleton tokens like COMMA or OPEN_CURLY
    static Token newWithoutOrigin(TokenType tokenType, String debugString, String tokenText) {
        return new Token(tokenType, null, tokenText, debugString);
    }

    final TokenType tokenType() {
        return tokenType;
    }

    public String tokenText() {
        return tokenText;
    }

    // this is final because we don't always use the origin() accessor,
    // and we don't because it throws if origin is null
    final ConfigOrigin origin() {
        // code is only supposed to call origin() on token types that are
        // expected to have an origin.
        if (origin == null) {
            throw new BugOrBroken(
                    "tried to get origin from token that doesn't have one: " + this);
        }
        return origin;
    }

    final int lineNumber() {
        if (origin != null) {
            return origin.lineNumber();
        }
        return -1;
    }

    @Override
    public String toString() {
        if (debugString != null) {
            return debugString;
        }
        return tokenType.name();
    }

    protected boolean canEqual(Object other) {
        return other instanceof Token;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Token) {
            // origin is deliberately left out
            return canEqual(other) && this.tokenType == ((Token) other).tokenType;
        }
        return false;
    }

    @Override
    public int hashCode() {
        // origin is deliberately left out
        return tokenType.hashCode();
    }
}
