/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.sql.NClob;

/** In-memory NClob; Netezza stores Unicode text as UTF-8/UTF-16 through the JDBC layer. */
final class NetezzaNClob extends NetezzaClob implements NClob {
    NetezzaNClob(String data) {
        super(data);
    }
}
