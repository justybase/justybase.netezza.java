/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.sql.SQLWarning;

/**
 * Receives Netezza server notices as soon as their protocol frames arrive.
 *
 * <p>The callback is invoked on the thread that is reading the JDBC response. For a stored
 * procedure that raises notices while it is still running, this means the listener can observe
 * a notice before {@code execute()} returns and before the final {@code CommandComplete} frame.
 * Callbacks must be fast and must not execute another JDBC operation on the same connection.</p>
 */
@FunctionalInterface
public interface NetezzaNoticeListener {
    /**
     * Called for every server notice.
     *
     * @param warning parsed notice; never {@code null}
     */
    void notice(SQLWarning warning);
}
