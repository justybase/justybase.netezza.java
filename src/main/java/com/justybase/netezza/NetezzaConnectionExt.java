/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.sql.Connection;

/**
 * Extended JDBC connection surface for Netezza-specific capabilities that have no standard JDBC
 * equivalent.
 *
 * <p>A physical connection returned by this driver implements this interface, so callers may
 * either cast it or use {@link Connection#unwrap(Class)}.</p>
 */
public interface NetezzaConnectionExt extends Connection {
    /**
     * Registers a listener for server notices. Pass {@code null} to remove the listener.
     *
     * <p>The listener is called while the response is being read. It therefore also fires for
     * notices raised by a stored procedure before that procedure finishes. Exceptions thrown by
     * the listener are ignored so that protocol processing is never interrupted.</p>
     *
     * @param listener notice callback or {@code null}
     */
    void setNoticeListener(NetezzaNoticeListener listener);

    /**
     * Returns the currently registered notice listener.
     *
     * @return the listener, or {@code null} when none is registered
     */
    NetezzaNoticeListener getNoticeListener();
}
