/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package org.netezza;

import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Compatibility entry point for tools that expect the historical Netezza driver class name.
 * All work is delegated to {@link com.justybase.netezza.NetezzaDriver}.
 */
public final class Driver implements java.sql.Driver {
    /** Creates the compatibility driver; all calls are delegated to {@link com.justybase.netezza.NetezzaDriver}. */
    public Driver() {}
    private static final com.justybase.netezza.NetezzaDriver DELEGATE =
            new com.justybase.netezza.NetezzaDriver();

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return DELEGATE.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return DELEGATE.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return DELEGATE.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return DELEGATE.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return DELEGATE.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return DELEGATE.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() {
        return DELEGATE.getParentLogger();
    }
}
