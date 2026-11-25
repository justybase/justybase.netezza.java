# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [0.1.0]

### Added

- Pure Java JDBC 4.3 driver for IBM Netezza Performance Server, Java 17+, no runtime dependencies.
- Streaming forward-only/read-only result sets with lazy DBOS decoding.
- Prepared and callable statements, JDBC escapes, `java.time` getters and `getObject(..., Class)`.
- JDBC 4.3 `DatabaseMetaData` backed by Netezza catalog views.
- Plain, MD5 and SHA-256 password authentication; TLS with `sslmode`, custom CAs, trust stores and mutual TLS.
- External-table import/export over `REMOTESOURCE 'jdbc'`, including compressed internal format and log capture.
- Automatic bulk path for multi-row `INSERT ... VALUES` batches with row-by-row fallback.
- Server notices exposed as `SQLWarning` chains and through an optional listener.
- Query timeouts and cancellation with SQLState `57014` and session recovery.
- `NetezzaDataSource` and the `org.netezza.Driver` compatibility alias.
- Offline (127), LIVE (181) and shared conformance (108/108, 216/216 Java + C#) test suites.
- Release build metadata: sources and javadoc jars, GitHub Packages deployment, GitHub Release workflow and reproducible archives.
- `CHANGELOG.md`, `SECURITY.md` and `CONTRIBUTING.md`.

### Changed

- The conformance runner moved from the published jar to the test sources.
- The jar declares `Automatic-Module-Name: com.justybase.netezza`.

### Known limitations

- Parameters are rendered as SQL literals on the client; there is no server-side bind support.
- No named `CallableStatement` parameters, savepoints, scrollable/updatable result sets, Kerberos/GSSAPI or built-in pooling.
- `Driver.jdbcCompliant()` reports `false`.
