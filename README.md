<h1 align="center">JustyBase NetezzaSQL JDBC</h1>

<p align="center">
  <strong>A dependency-free, Java 17+ JDBC 4.3 driver for IBM Netezza Performance Server.</strong>
</p>

<p align="center">
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-0B2A5B?style=flat-square">
  <img alt="Verified on Java 17, 21, 25" src="https://img.shields.io/badge/verified-17%2F21%2F25-0F766E?style=flat-square">
  <img alt="JDBC 4.3" src="https://img.shields.io/badge/JDBC-4.3-0F766E?style=flat-square">
  <img alt="Runtime dependencies: 0" src="https://img.shields.io/badge/runtime%20dependencies-0-A3C614?style=flat-square">
  <img alt="Offline tests: 127/127" src="https://img.shields.io/badge/offline%20tests-127%2F127-0B2A5B?style=flat-square">
  <img alt="LIVE tests: 181/181" src="https://img.shields.io/badge/LIVE%20tests-181%2F181-0F766E?style=flat-square">
  <img alt="Conformance: 108/108" src="https://img.shields.io/badge/conformance-108%2F108-A3C614?style=flat-square">
</p>

<p align="center">
  <a href="https://github.com/justybase/justybase.nz.java"><img alt="JustyBase Netezza Java JDBC driver" src="images/netezza-driver-badge-java.png" width="420"></a>
</p>

---

## Contents

- [Overview](#overview)
- [Project origin and license](#project-origin-and-license)
- [Highlights](#highlights)
- [Requirements](#requirements)
- [Java compatibility](#java-compatibility)
- [Build and install](#build-and-install)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Supported surface](#supported-surface)
- [Server notices](#server-notices)
- [External tables](#external-tables)
- [Bulk prepared batches](#bulk-prepared-batches)
- [Testing](#testing)
- [Bulk insert performance](#bulk-insert-performance)
- [Docker LIVE matrix](#docker-live-matrix-windows--wsl2)
- [Conformance](#conformance)
- [API comparison](#api-comparison)
- [Performance](#performance)
- [Design notes](#design-notes)
- [Limitations](#limitations)
- [Security](#security)
- [License](#license)
- [Status](#status)

## Overview

JustyBase NetezzaSQL JDBC is a pure Java implementation of the Netezza wire protocol. It exposes a standard JDBC 4.3 surface for Java 17+ applications, with no runtime dependencies beyond the JDK. Connection pooling is intentionally delegated to established pool libraries such as HikariCP, DBCP or c3p0.

The driver is built for predictable behaviour: it streams result rows, decodes DBOS values lazily, maps server failures to standard SQLStates, and keeps the session usable after recoverable errors. It is tested against a live Netezza appliance as part of the opt-in LIVE suite and the shared cross-driver conformance corpus.

## Project origin and license

This driver is based on the [JustyBase.NetezzaDriver](https://github.com/justybase/JustyBase.NetezzaDriver) codebase — a pure C# ADO.NET driver for IBM Netezza Performance Server — ported to Java and substantially extended for JDBC 4.3 and Java 17+.

The upstream project is licensed under the [Apache License, Version 2.0](LICENSE). JustyBase NetezzaSQL JDBC uses the same license; the original copyright notices are retained in [LICENSE](LICENSE).

The Netezza JDBC driver 11.1.0.0 (`nzjdbc`) mentioned in the comparison sections is not bundled with this project and is not a runtime dependency. It is used only as an external comparison target in the optional benchmark and parity tests.

## Highlights

| | |
|---|---|
| **Zero runtime dependencies** | The driver jar contains only the driver; JUnit and Maven are test/build tools. |
| **Modern JDBC surface** | JDBC 4.3, Java 17+ (verified on 17, 21 and 25), `java.time` getters, `getObject(..., Class)`, `executeLargeUpdate`, `closeOnCompletion`, JDBC escape processing and standard metadata. |
| **Streaming results** | Forward-only, read-only result sets with lazy DBOS decoding and bounded allocation. |
| **Prepared and callable statements** | Client-side `PreparedStatement` with `ParameterMetaData` and best-effort `getMetaData()`, plus positional `CallableStatement` support for `CALL`/`{call ...}`. Parameters are rendered as safely quoted SQL literals on the client; this is not server-side bind support. |
| **LOBs and streams** | In-memory `Blob`, `Clob`, `NClob` and `SQLXML`, stream getters/setters and bounded materialization through `maxStreamBytes`. |
| **Custom TLS trust** | Custom CA/PEM, JKS/PKCS12 trust stores and client certificates for mutual TLS, in addition to the standard `sslmode` modes. |
| **Catalog-backed metadata** | Tables, views, columns, keys, functions, procedures, type information and capability flags are read from Netezza catalog views. |
| **Timeouts and cancel** | `setQueryTimeout` schedules a server-side cancel and raises `SQLTimeoutException` with SQLState `57014`; the session is preserved when protocol synchronisation is certain. |
| **External tables** | Text and `INTERNAL`/`COMPRESS` transfers over `REMOTESOURCE 'jdbc'`, including log capture and deferred error reporting. |
| **Bulk prepared batches** | Two or more rows in a simple `INSERT ... VALUES` batch are streamed as one external-table transfer, with automatic row-by-row fallback for unsupported statements and values. |
| **Server notices** | `NoticeResponse` frames become `SQLWarning` chains on `Connection`, `Statement` and `ResultSet`. An optional listener receives notices as they arrive, including while a stored procedure is still running. |
| **TLS and password auth** | Plain, MD5 and SHA-256 authentication; `sslmode` supports `disable` through `verify-full`. Kerberos/GSSAPI is rejected explicitly with SQLState `0A000`. |

## Requirements

| Component | Requirement |
|---|---|
| Java | 17, 21 or 25 (build baseline: 17) |
| JDBC | 4.3 |
| Netezza | Release 11.2.x; tested on `Release 11.2.2.1 [Build 20]` |
| Maven | 3.9+ (build and tests only) |
| Runtime dependencies | None |

## Java compatibility

The driver targets Java 17 as its baseline. `maven.compiler.release` is set to `${java.baseline}` (17), so a single jar uses class file version 61 and runs unchanged on Java 17, 21 and 25. No multi-release jar or per-JDK artifacts are produced. `maven-enforcer-plugin` fails the build fast when it is run on a JDK older than 17.

The source deliberately uses only language features and APIs available in Java 17 (records, switch expressions, `instanceof` patterns, text blocks, `HexFormat`, `Stream.toList()`); JDBC 4.3 itself became standard in Java 17.

`.github/workflows/java-matrix.yml` runs `mvn verify` on Temurin 17, 21 and 25 for every push and pull request and checks that the compiled class file is version 61. The LIVE suite (181 tests), shared conformance (108/108) and Java/C# conformance (216/216) need a live appliance and are run separately on the development machine, not in the matrix. The local [Docker LIVE matrix](#docker-live-matrix-windows--wsl2) runs the same 181 LIVE tests on all three JDKs against that appliance.

## Build and install

```powershell
mvn verify        # offline unit/contract tests plus packaging
mvn package       # build the jar, -sources.jar and -javadoc.jar
mvn install       # install into the local Maven repository
```

The `-sources` and `-javadoc` jars are attached by the build and published alongside the driver jar.

Building requires JDK 17 or newer; select the compiler with `JAVA_HOME`. The resulting jar is compiled with `--release 17` (class file version 61), so the same artifact runs on Java 17, 21 and 25 — no toolchains or per-JDK builds are needed.

After `mvn install`, the driver can be referenced as:

```xml
<dependency>
  <groupId>com.justybase</groupId>
  <artifactId>netezza-sql</artifactId>
  <version>0.1.0</version>
</dependency>
```

Release jars are published as [GitHub Releases](https://github.com/justybase/justybase.netezza.java/releases) and in GitHub Packages under these coordinates; you can also build or install locally. The driver is registered through `META-INF/services/java.sql.Driver`. The primary class is `com.justybase.netezza.NetezzaDriver`; `org.netezza.Driver` is a compatibility alias for tools that load the legacy Netezza class name explicitly.

### Installing from GitHub Packages

GitHub Packages requires authentication even for public packages. Add the repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/justybase/justybase.netezza.java</url>
  </repository>
</repositories>
```

and a matching `server` in your `~/.m2/settings.xml` (use a GitHub personal access token with `read:packages`):

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_LOGIN</username>
      <password>YOUR_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>
</settings>
```

Alternatively, download the three jars directly from the GitHub Release page — no authentication needed.

## Quick start

```java
String url = "jdbc:netezza://nz-host:5480/ANALYTICS?sslmode=prefer&connectTimeout=15000";
Properties p = new Properties();
p.setProperty("user", "reader");
p.setProperty("password", "secret");

try (Connection c = DriverManager.getConnection(url, p);
     PreparedStatement s = c.prepareStatement("SELECT ?::INTEGER AS value")) {
    s.setInt(1, 42);
    try (ResultSet r = s.executeQuery()) {
        while (r.next()) System.out.println(r.getInt("value"));
    }
}
```

> **Security note:** Netezza does not expose server-side bind parameters. `PreparedStatement` renders every value as a safely quoted SQL literal on the client, so both the SQL template and the parameter values must come from trusted code. Do not pass unvalidated user input directly as a parameter. See [Limitations](#limitations).

`NetezzaDataSource` is also available for frameworks and pooling libraries.

## Configuration

Options can be supplied in the JDBC URL or in `Properties`:

| Option | Default | Meaning |
|---|---|---|
| `sslmode` | `prefer` | `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`. `require`, `verify-ca` and `verify-full` fail if the server declines TLS; `verify-full` also verifies the host name. |
| `connectTimeout` | `15000` | TCP connect timeout in milliseconds. |
| `socketTimeout` | `30000` | Socket read timeout in milliseconds. |
| `clientType` | `3` | Netezza handshake client type (`SqlJdbc`). |
| `char_varchar_encoding` | `latin1` | `latin1` or `utf8` for `CHAR`/`VARCHAR`; `NCHAR`/`NVARCHAR` are always decoded as UTF-8. |
| `socketBufferSize` | `0` | Socket send/receive buffer size; `0` uses the OS default. |
| `maxStreamBytes` | `16777216` | Maximum bytes/chars materialized from a stream setter. |
| `bulkInsert` | `true` | Use one external-table transfer for prepared batches with two or more `INSERT ... VALUES` rows. Set to `false` to force row-by-row execution. |
| `caCertificate` | none | PEM CA certificate (or chain) used to verify the server. May be combined with `trustStore`. |
| `trustStore`, `trustStorePassword`, `trustStoreType` | none | Custom trust store; type is `JKS` or `PKCS12` and is inferred from the file extension when omitted. |
| `clientCertificate`, `clientKey`, `clientKeyPassword` | none | PEM client certificate chain and PKCS#8 private key for mutual TLS; both certificate and key are required. |
| `keyStore`, `keyStorePassword`, `keyStoreType` | none | Client key store; type is `JKS` or `PKCS12` and is inferred from the file extension when omitted. Takes precedence over the PEM client certificate options. |

Credentials may be supplied in URL options or `Properties`; prefer `Properties` to avoid storing secrets in URLs.

Custom TLS material is used for TLS connections whenever at least one TLS option is present; `sslmode` still decides whether TLS is negotiated:

```java
Properties p = new Properties();
p.setProperty("user", "reader");
p.setProperty("password", "secret");
p.setProperty("sslmode", "verify-full");
p.setProperty("caCertificate", "certs/ca.pem");          // or trustStore/trustStorePassword
p.setProperty("clientCertificate", "certs/client.pem");  // optional mutual TLS
p.setProperty("clientKey", "certs/client-key.pem"); // PKCS#8, optionally encrypted
Connection c = DriverManager.getConnection(url, p);
```

PEM private keys must be PKCS#8 (`BEGIN PRIVATE KEY` or `BEGIN ENCRYPTED PRIVATE KEY`); traditional `BEGIN RSA PRIVATE KEY` files are rejected with SQLState `08001`. Missing files, bad passwords and incomplete certificate/key pairs also fail with `08001` before the connection is opened.

## Supported surface

- JDBC `Connection`, forward-only/read-only `Statement`, client-substituted `PreparedStatement` with `ParameterMetaData` and best-effort `getMetaData()`, positional `CallableStatement`, streaming `ResultSet`, `DataSource`, transactions, batch execution, multi-result navigation and `maxRows`.
- JDBC 4.3 `DatabaseMetaData`: catalogs, schemas, tables, views, columns, types, primary/foreign/cross-reference keys, functions and procedures, server version and the standard `supports*` flags and size limits. Where Netezza has no catalog equivalent, the driver returns the standard empty result shape instead of failing.
- JDBC escape processing for `{fn ...}`, `{d ...}`, `{t ...}`, `{ts ...}`, `{escape ...}`, `{call ...}` and outer joins; `setEscapeProcessing(false)` passes the SQL through unchanged.
- `ResultSet` standard overloads: `getObject` with `Map`/`Class`, `Calendar`-aware date/time/timestamp getters, scale-aware `getBigDecimal`, LOB and stream getters, and `SQLXML`.
- LOB and stream setters, `Connection.createBlob/createClob/createNClob/createSQLXML`, and in-memory LOB access. Stream materialization is capped by `maxStreamBytes`; `createArrayOf` and `ARRAY` parameters are rejected with SQLState `0A000`.
- `CallableStatement` executes Netezza `CALL` and JDBC `{call ...}` syntax with positional parameters. Registered output parameters map to the returned `ResultSet` column by index; named parameters are rejected with `0A000`.
- Query timeouts: `Statement.setQueryTimeout` schedules a server-side cancel, raises `SQLTimeoutException` with SQLState `57014`, and preserves the session when protocol synchronisation is certain. If synchronisation is uncertain, the physical connection is closed rather than reused.
- `Statement.cancel()` works concurrently with a blocking `execute()` and is protected by an operation generation token against late cancels.
- TLS negotiation for all `sslmode` values; plain, MD5 and SHA-256 password authentication. Kerberos/GSSAPI is rejected explicitly with SQLState `0A000`.
- External tables over local files with `REMOTESOURCE 'jdbc'`: export, import, compressed internal format, log capture and deferred error reporting. Virtual streams, `COPY` and `load_data` are outside the current surface.
- Server notices: `SQLWarning` chains on `Connection`, `Statement` and `ResultSet`, plus `NetezzaConnectionExt.setNoticeListener(...)` for notices delivered while a procedure is still executing.
- `PreparedStatement` parameters are rendered as SQL literals on the client. Use only trusted SQL templates and parameter values; this is not server-side bind support.

### Callable statements, LOBs and escapes

Netezza procedures are invoked with positional parameters. Registered output parameters are read from the first result column by index:

```java
try (CallableStatement call = c.prepareCall("{call ANALYTICS.ADD_ONE(?)}")) {
    call.setInt(1, 41);
    call.registerOutParameter(1, Types.INTEGER);
    call.execute();
    int result = call.getInt(1);          // 42
}
```

`CALL ANALYTICS.ADD_ONE(?)` works the same way with `executeQuery()`. Named parameters such as `registerOutParameter("result", ...)` are not supported and raise `SQLFeatureNotSupportedException` with SQLState `0A000`.

LOBs and streams are materialized in memory up to `maxStreamBytes` (16 MiB by default):

```java
try (PreparedStatement s = c.prepareStatement("SELECT ?::NVARCHAR(40)")) {
    s.setCharacterStream(1, new StringReader("zażółć"), 6);
    try (ResultSet r = s.executeQuery()) {
        String value = r.getString(1);
    }
}
```

JDBC escapes are translated on the client by default:

```java
Statement s = c.createStatement();
ResultSet r = s.executeQuery(
        "SELECT {fn UCASE('abc')}, {d '2024-02-29'}, {ts '2024-02-29 12:34:56.123456'}");
s.setEscapeProcessing(false); // subsequent SQL is sent verbatim
```

`Connection.nativeSQL(...)` exposes the same translation.

## Server notices

Netezza `RAISE NOTICE` and other `NoticeResponse` frames are parsed into standard `SQLWarning` objects. The warning chain is available from the connection, the executing statement and the current result set:

```java
try (Connection c = DriverManager.getConnection(url, props);
     Statement s = c.createStatement()) {
    s.execute("CALL ANALYTICS.LOAD_DAILY()");

    for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
        log.info("{}: {}", w.getSQLState(), w.getMessage());
    }
    s.clearWarnings();
}
```

A listener can observe notices while the procedure is still running, before `execute()` returns and before the final `CommandComplete` frame:

```java
NetezzaConnectionExt extended = c.unwrap(NetezzaConnectionExt.class);
extended.setNoticeListener(w -> log.info("server notice: {}", w.getMessage()));
try {
    s.execute("CALL ANALYTICS.LOAD_DAILY()");
} finally {
    extended.setNoticeListener(null);
}
```

The callback runs on the thread that reads the protocol response. It must be fast and must not execute another JDBC operation on the same connection; hand work off to an application executor if needed. Exceptions thrown by the listener are ignored so protocol processing is never interrupted. `NetezzaDataSource.setNoticeListener(...)` installs the same listener on every connection created by that data source. Notices raised before a statement fails remain available through `Statement.getWarnings()` and `Connection.getWarnings()`.

## External tables

The file is local to the client; the driver streams it over the existing JDBC connection. Paths are embedded in SQL, so callers must quote/escape them for their environment.

```java
Path file = Files.createTempFile("export-", ".dat");
String sqlFile = file.toAbsolutePath().toString().replace("'", "''");
String logDir = file.getParent().toAbsolutePath().toString().replace("'", "''");

try (Statement s = c.createStatement()) {
    s.execute("CREATE EXTERNAL TABLE '" + sqlFile + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
            logDir + "') AS SELECT * FROM SOURCE_TABLE");
    s.execute("CREATE TEMP TABLE DEST (ID INT, LABEL VARCHAR(40))");
    s.execute("INSERT INTO DEST SELECT * FROM EXTERNAL '" + sqlFile +
            "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + logDir + "')");
}
```

`FORMAT 'INTERNAL' COMPRESS 'TRUE'` is supported by the appliance and works through the same streaming path. Export/import errors, including log-transfer failures, are surfaced when the statement completes.

## Bulk prepared batches

When a `PreparedStatement` has two or more rows in a batch and its SQL is a single-row `INSERT ... VALUES (...)`, the driver rewrites it to one `INSERT ... SELECT ... FROM EXTERNAL` statement and streams the rows over the existing JDBC connection. No temporary table, temporary file or second connection is created. This is substantially faster than one server round-trip per row.

```java
try (PreparedStatement insert = c.prepareStatement(
        "INSERT INTO TARGET (ID, NAME, CREATED_AT) VALUES (?, ?, ?)")) {
    for (Row row : rows) {
        insert.setInt(1, row.id());
        insert.setString(2, row.name());
        insert.setObject(3, row.createdAt());
        insert.addBatch();
    }
    int[] counts = insert.executeBatch(); // Statement.SUCCESS_NO_INFO (-2) per row
}
```

The bulk path supports numbers, strings, booleans, `NULL`, `DATE`/`TIME`/`TIMESTAMP`, `TIMETZ` and the corresponding `java.time` values. It automatically falls back to row-by-row execution for `INSERT ... SELECT`, multi-row `VALUES`, binary/LOB parameters, unsupported control characters and any statement the parser cannot rewrite safely. `bulkInsert=false` disables the optimization for a connection. The transfer is one server statement, so a load failure is atomic and `BatchUpdateException.getUpdateCounts()` is empty; row-by-row fallback keeps its usual partial-count behaviour.

## Testing

The offline suite runs without an appliance. The LIVE suite is opt-in through the Maven `live` profile and the JUnit `live` tag; a plain `mvn test` never runs it.

### JDBC surface audit

The post-bulk audit compared every `java.sql` interface method with the JDBC 4.3 contract and verified the behaviour directly against Netezza. Notable fixes:

- proxy-based `Statement`, `Connection`, `ResultSet` and `DatabaseMetaData` now invoke JDBC default methods (`enquoteIdentifier`, `enquoteLiteral`, `enquoteNCharLiteral`, `isSimpleIdentifier`, ...);
- `getMaxFieldSize`/`setMaxFieldSize`, `setUnicodeStream`, `ResultSet.getURL` and `ResultSet.getUnicodeStream` are implemented;
- `DatabaseMetaData.getMaxLogicalLobSize()` no longer throws `ClassCastException`, and informational flags (`nullsAreSortedLow`, transactional DDL, open statements across commit/rollback, catalog-qualified DDL/privileges, ...) now match observed server behaviour;
- `SERIALIZABLE` isolation is applied and reported; the `currentSchema` URL property is honoured;
- `Connection.setCatalog` reconnects to a different database by opening a new physical session, preserves `autoCommit` and isolation settings, and keeps the current session usable when the target database cannot be opened;
- prepared SQL scanning matches Netezza: backslash is an ordinary character, block comments nest, and JDBC escapes are applied to batches in both bulk and row-by-row modes;
- `setObject` rejects unsupported values instead of inserting `toString()` output;
- bulk planning rejects `DEFAULT` in a `VALUES` tuple and falls back to the original statement.

### Offline suite — 127 tests

| Area | Classes | Tests |
|---|---|---:|
| Driver registration, URLs and options | `DriverContractTest`, `DriverParsingTest`, `DriverAliasTest` | 19 |
| Prepared SQL and parameter edges | `PreparedSqlTest`, `PreparedSqlEdgeTest` | 18 |
| Wire protocol, codecs and notices | `ProtocolUnitTest`, `ProtocolRobustnessTest`, `QueryResultCodecTest`, `NoticeProtocolTest` | 45 |
| Metadata result shapes | `MetadataResultSetsTest` | 3 |
| Multi-result navigation | `MultipleResultsTest` | 3 |
| External-table handler | `ExternalTableHandlerTest` | 6 |
| Bulk insert planning and rendering | `BulkInsertTest` | 13 |
| Escape processing | `JdbcEscapeProcessorTest` | 7 |
| JDBC type mapping and coercion | `JdbcTypeSupportTest` | 7 |
| LOB wrappers | `LobUnitTest` | 3 |
| TLS trust/key material | `TlsSupportTest` | 3 |
| **Total** | | **127** |

### LIVE suite — 181 tests

| Area | Classes | Tests |
|---|---|---:|
| Smoke, alias and session contract | `LiveSmokeTest`, `DriverAliasLiveTest`, `JdbcSessionContractLiveTest` | 17 |
| ResultSet navigation and conversions | `ResultSetContractLiveTest` | 13 |
| PreparedStatement binding and type matrix | `PreparedStatementLiveTest`, `TypeGettersLiveTest` | 30 |
| Full live data type matrix | `DataTypeMatrixLiveTest` | 11 |
| Transactions and error recovery | `TransactionLiveTest`, `ErrorRecoveryLiveTest` | 17 |
| Concurrency, timeouts and resource lifecycle | `ConcurrencyLiveTest`, `TimeoutCancelLiveTest`, `ResourceLifecycleLiveTest` | 24 |
| Catalog metadata | `JdbcMetadataLiveTest`, `JdbcMetadataDdlLiveTest`, `MetadataAdvancedLiveTest` | 21 |
| External tables | `ExternalTableLiveTest`, `ExternalTableBulkLiveTest` | 12 |
| Bulk prepared inserts | `BulkInsertLiveTest` | 5 |
| Notices and warnings | `NoticeLiveTest` | 4 |
| JDBC 4.3 surface, metadata and DataSource | `Jdbc43SurfaceLiveTest` | 6 |
| JDBC surface audit regressions | `JdbcGapsLiveTest` | 10 |
| Escape processing | `EscapeProcessingLiveTest` | 3 |
| LOBs and streams | `LobStreamLiveTest` | 4 |
| Callable statements | `CallableStatementLiveTest` | 4 |
| **Total** | | **181** |

Key scenarios include:

- session state: auto-commit/transaction transitions, schema switching, read-only and holdability flags, warnings, client info, network timeout and `isValid`;
- `ResultSet`: forward-only navigation, `isBeforeFirst`/`isAfterLast`, metadata flags, NULL handling, temporal/binary/unicode getters and `getObject(..., Class)`;
- `PreparedStatement`: every primitive and object binding, `java.time` values, NULLs, row-by-row and bulk batch execution, fallback for unsupported batch shapes, generated-key rejection and parameter metadata;
- full data type matrix: prepared-statement round-trips from `BYTEINT` through `NUMERIC(38,10)`, `CHAR`/`NCHAR` padding, unicode/emoji, binary values, `DATE`/`TIME`/`TIMETZ`/`TIMESTAMP` boundaries, canonical `INTERVAL` text, NULL defaults and `wasNull`, plus agreement between `ResultSetMetaData`, `DatabaseMetaData.getColumns` and `ParameterMetaData`;
- transactions: commit/rollback, savepoint rejection, re-`BEGIN` after commit/rollback, temp-table lifetime and isolation levels;
- errors: standard SQLState mapping for syntax, missing relation/column, cast, division, not-null and interval, plus recovery and connection validity after each failure;
- concurrency: parallel statements, timeout/cancel, cross-thread close/abort and statement tracking;
- metadata: catalogs, schemas, table/column patterns, keys, functions/procedures, capability flags and empty result shapes;
- external tables: 20,000-row text and `INTERNAL`/`COMPRESS` round-trips, `NVARCHAR` unicode, NULLs, paths with spaces, comma/semicolon delimiters, malformed imports and export failures;
- bulk prepared inserts: single external-table transfer for multi-row `INSERT ... VALUES` batches, unicode/control characters, NULLs, temporal and numeric values, 500-row chunking, binary and `INSERT ... SELECT` fallback, and `bulkInsert=false`;
- notices: NZPLSQL `RAISE NOTICE` chains on connection/statement/result set, listener delivery while a procedure is still running, notices retained after a procedure error, and listener exceptions isolated from the protocol;
- JDBC 4.3 surface: `ParameterMetaData` after every setter family, best-effort prepared `getMetaData()` for `SELECT`/`WITH`, `Calendar`/`Map`/`Class`/scale overloads, `DataSource` connection properties, client info round-trips and metadata size/capability limits;
- escape processing: `{fn ...}`, `{d ...}`, `{t ...}`, `{ts ...}`, `LIKE ... {escape ...}` and `nativeSQL`, including verbatim pass-through when escape processing is disabled;
- LOBs and streams: binary/character/ASCII/N-character streams, `Blob`/`Clob`/`NClob`/`SQLXML` getters and setters, connection factories and the `maxStreamBytes` limit;
- callable statements: `{call ...}` and `CALL`, positional input/output parameters, returned result sets, `Calendar`-aware temporal getters, out-of-range indices (`07009`) and named-parameter rejection (`0A000`).

JDBC's default `getObject()` mapping for `TIME` is `java.sql.Time`, which has millisecond precision. Use `getObject(index, LocalTime.class)` when microsecond precision is required; the type tests cover both paths.

`INTERVAL` columns are exposed as canonical server text, for example `2 days 03:04:05`, `-21:56:56`, `1 year 2 mons` and `1 year 2 mons 3 days 04:05:06`. `TIMETZ` text uses the server's `HH:MM:SS[.ffffff]+HH:MM` form; whole-hour offsets omit the minutes (`+02`, `-12`) and UTC is rendered as `+00`.

`TIMETZ` is exposed as `java.time.OffsetTime` by both `getObject()` and `getObject(..., OffsetTime.class)`, matching the class advertised by `ResultSetMetaData`. The temporal getters convert across related types (`TIMESTAMP` to `Date`/`Time`, `DATE` to `Timestamp`/`Time`, `TIME` to `Timestamp`/`Date`, and the corresponding `java.time` types); an offset-less value explicitly requested as `OffsetDateTime` or `OffsetTime` is interpreted as UTC. `getObject()` without a target class returns the JDBC class reported by `getColumnClassName()`.

Netezza has no `TIMESTAMP WITH TIME ZONE` SQL literal. `setObject` therefore rejects `OffsetDateTime` and `Instant` values with SQLState `0A000` instead of generating invalid SQL; convert explicitly with `setObject(index, value, Types.TIMESTAMP)` when a timezone-less timestamp is intended.

### LIVE environment

```powershell
$env:NZ_DEV_HOST = "127.0.0.1"
$env:NZ_DEV_PORT = "5480"
$env:NZ_DEV_USER = "admin"
$env:NZ_DEV_PASSWORD = "..."     # never commit or log this value
$env:NZ_DEV_DATABASE = "JUST_DATA"  # or NZ_DEV_DB
$env:NZ_DEV_ALLOW_FIXTURE_DDL = "1" # only for fixture-cleaning tests
$env:NZ_LOCAL_TMP_DIR = "..."       # optional; defaults to java.io.tmpdir/justybase-netezza-java-tests
$env:NZ_CONFORMANCE_SOCKET_TIMEOUT = "30000" # optional socket timeout for LIVE and conformance runs
```

Fixture DDL is restricted to the configured conformance schema or temporary `CASE_*`/`JAVA_*` tables and is skipped unless `NZ_DEV_ALLOW_FIXTURE_DDL=1`.

### Bulk insert performance

The bulk path has an opt-in performance suite, separate from the correctness LIVE suite:

```powershell
mvn -Pperf test
```

It runs four scenarios and appends every timed round to `target/performance/bulk-insert.csv`:

- 500 rows inserted as a bulk batch versus 500 row-by-row `executeUpdate` calls, three interleaved rounds, median reported;
- batch-size scaling at 10, 100, 1,000 and 5,000 rows;
- 2,000 wide unicode rows (`NVARCHAR(500)`, about 400 characters per name);
- 2,000 rows inside one transaction, with execute and commit timed separately.

Timing includes `addBatch` and `executeBatch`, but excludes table creation, `TRUNCATE`, row-count verification and result-set decoding. The bulk-vs-row test fails if the measured speedup is below `2x`; override the threshold with `-Dnz.perf.minSpeedup=...`.

Measured on the local Netezza simulator with Java 21 (median of 2–3 rounds; simulator timings vary between runs):

| Scenario | Result |
|---|---|
| 500 rows, bulk vs row-by-row | 183 ms vs 8,966 ms median, **49.0x** |
| 1,000 rows, bulk | 141 ms, ~7,100 rows/s |
| 5,000 rows, bulk | 212 ms, ~23,600 rows/s |
| 2,000 wide unicode rows | 162 ms, ~12,300 rows/s, 4.7 MiB/s of name payload |
| 2,000 rows in one transaction | 146 ms execute + 7 ms commit, ~13,700 rows/s |

These are relative measurements from a simulator, not a production sizing benchmark. The fixed external-table handshake dominates very small batches: a 10-row bulk took about 174 ms in the latest median run (461 ms in a colder run), so for latency-sensitive single-digit batches row-by-row execution (or a larger accumulated batch) can be preferable. The automatic path uses the two-row threshold; `bulkInsert=false` is the explicit opt-out.

### Docker LIVE matrix (Windows + WSL2)

`docker/live/run-live-matrix.ps1` builds ephemeral `maven:3.10-eclipse-temurin-{17,21,25}` images and runs the full LIVE suite once per JDK. Docker runs in one WSL2 distro (`Ubuntu` by default); the appliance may run in a separate distro (`NPS-UBU26`). Containers reach it through `host.docker.internal` (`--add-host=host.docker.internal:host-gateway`), so no database port needs to be published. External-table tests work in this setup because file transfer is multiplexed on the JDBC connection.

Prerequisites:

- Docker engine running in the chosen distro: `wsl -d Ubuntu -u root -e systemctl start docker` (if needed).
- `NZ_DEV_*` credentials in the PowerShell environment. The script writes them to a temporary env file and deletes it afterwards.
- The appliance reachable at `127.0.0.1:5480` from the Docker distro, or a different address passed with `-DatabaseHost`.

```powershell
# all three JDKs
.\docker\live\run-live-matrix.ps1

# one JDK, keep built images for reuse
.\docker\live\run-live-matrix.ps1 -JavaVersions 21 -KeepImages

# print the build/run commands without executing them
.\docker\live\run-live-matrix.ps1 -JavaVersions 17,25 -DryRun
```

Each JDK gets its own image, container and Surefire reports under `target\docker-live\jdk-<version>`. Runs are sequential because the LIVE tests share the conformance schema; use `-Schema` to point at a different one. A named Docker volume caches `/root/.m2/repository`; `-CleanCache` forces a fresh dependency download. `pwsh -File ... -JavaVersions 17,25` is accepted as well. Verified locally: **181/181 on each of Java 17, 21 and 25**.

## Conformance

The Java runner consumes the shared `conformance/cases/*.json` corpus from the sibling conformance checkout and emits JSONL:

```powershell
java -cp "target/test-classes;target/classes" com.justybase.netezza.conformance.Runner --profile full --out target/conformance.jsonl --verbose
java -cp "target/test-classes;target/classes" com.justybase.netezza.conformance.Runner --profile encoding --verbose
```

Profiles: `smoke`, `full`, `encoding`, `external`, `timeout-cancel`, `api`, `tls`, `pool`. Fixture profiles require `NZ_DEV_ALLOW_FIXTURE_DDL=1`. On the test appliance the Java driver passes:

| Profile | Result |
|---|---|
| `full` (includes `smoke`) | 108/108 |
| `encoding` | 10/10 |
| `external` | 1/1 |
| `timeout-cancel` | 2/2 |
| `api` | 1/1 |
| `tls` | 1/1 |

Negative cases (`errors.*`, `timeouts.command_timeout`, `tls.certificate_verification`) report `status=error` with the expected error class; the runner and the shared canonical comparator count them as passes. The canonical comparator reports 108/108 for `full` with zero differences.

`pool.reuse` is reported as `skip` with an `n/a` reason because pooling is intentionally delegated to external pool libraries. The shared canonical comparator treats any skip as a difference, so exclude or separately account for that case when comparing Java output; it is not silently omitted.

### Cross-driver conformance (Java and C#)

The shared kit can compare Java and C# on the same corpus. Both runners write JSONL files, and the orchestrator compares each file with the same canonical expectations:

```powershell
py -3.12 conformance\orchestrator\run.py --profile full --cases-dir conformance\cases `
  --out-dir justybase.nz.java\target\conformance-java-csharp-full `
  --runner "java=java -cp justybase.nz.java\target\test-classes;justybase.nz.java\target\classes com.justybase.netezza.conformance.Runner" `
  --runner "csharp=dotnet JustyBase.NetezzaDriver\tools\JustyBase.NetezzaDriver.Conformance\bin\Release\net10.0\JustyBase.NetezzaDriver.Conformance.dll"
```

The output directory contains `raw-java.jsonl`, `raw-csharp.jsonl`, `report.md`, `report.html`, `junit.xml` and `results.json`. On the test appliance the `full` profile passes **216/216** (108 cases × 2 drivers) with zero differences; `smoke` passes 24/24. Use `--bootstrap` when the `CONFORMANCE` fixtures are stale. Stray `JAVA_*`/`CASE_*` objects left by an interrupted live test can change `metadata.table_count`, so clean them before comparing.

## API comparison

The table compares the JDBC surface of this driver with the Netezza JDBC driver 11.1.0.0 (`nzjdbc`) on the same appliance. "Yes" means the method is implemented and usable; "No" means it throws a driver exception; "Partial" means a documented limitation. The comparison is informational; the 11.1.0.0 driver is an external comparison target, not a dependency.

| Area | JustyBase | nzjdbc 11.1.0.0 | Notes |
|---|---|---|---|
| JDBC level reported by `DatabaseMetaData` | 4.3 | 3.0 | `nzjdbc` still exposes some JDBC 4 methods |
| Java baseline | Java 17 bytecode, tested on 17/21/25 | Java 7 bytecode, tested on 21 here | both are single jars |
| Runtime dependencies | none | none | both jars are self-contained |
| Schema and catalog | yes | yes | both support `setSchema`; both reconnect on `setCatalog` |
| LOB/XML factories | yes | no | `createBlob`, `createClob`, `createNClob`, `createSQLXML` |
| Network timeout / abort | yes | no | `get/setNetworkTimeout`, `abort` |
| Large updates and large max rows | yes | no | `executeLargeUpdate`, `get/setLargeMaxRows`, `getLargeUpdateCount` |
| `closeOnCompletion` / `setPoolable` | yes | no | |
| `setMaxFieldSize` | stored and returned | ignored | `nzjdbc` always returns its 32,768 default |
| Prepared metadata | yes (best effort) | yes | `getMetaData`, `getParameterMetaData` |
| `setObject(..., SQLType)` | yes | no | |
| N-string/LOB setters | yes | no | `setNString`, `setNCharacterStream`, `setBlob`, `setClob`, `setSQLXML` |
| `ResultSet.getObject(..., Class)` | yes | no | |
| `ResultSet.getURL` | yes | no | |
| N-string/stream/LOB getters | yes | partial | `nzjdbc` supports `getNString`, but not the N-stream/NClob/SQLXML/Blob/Clob getters |
| Generated keys | empty result set | throws | |
| Callable statements | positional | positional | named parameters are unsupported by both |
| Metadata result shapes | standard JDBC columns | extra legacy columns | e.g. `getCatalogs` 1 vs 2, `getTables` 10 vs 12, `getColumns` 24 vs 27 |
| Metadata flags/functions | matches the server | legacy values | `nzjdbc` reports NULLs sorted high/at end and `supportsConvert=true`; the appliance sorts NULLs low and has no `CONVERT` |
| Bulk prepared batches | yes | yes | both are automatic for multi-row batches |
| Notices | warnings + listener | warnings | |
| TLS | `sslmode`, CA, trust store, client certificates | security levels and CA | |
| DataSource | `NetezzaDataSource` | `NzDatasource` | |
| Built-in pooling | no built-in pool; works with HikariCP/DBCP/c3p0 | `NzConnectionPool` classes | |
| Server-side parameter bind | no | no | both substitute escaped literals on the client |

## Performance

### Read throughput

`ThroughputBenchmark` runs a 200,000-row, seven-column query and reports row iteration and typed getters. The table is the median of three JVMs (5 warmup + 20 measured iterations) on the test appliance with Java 21:

| Mode | JustyBase | nzjdbc 11.1.0.0 |
|---|---:|---:|
| `next` (row iteration only) | 124.6 ms | 124.5 ms |
| `typed` (`getLong/getInt/getBigDecimal/getString`) | 131.0 ms | 134.8 ms |

Both drivers are in the same range for row iteration; the typed path is slightly faster here. Absolute times move with appliance load, JVM warmup and network latency, so compare medians rather than single runs.

```powershell
# JustyBase
java -Xms1g -Xmx1g -cp "target/test-classes;target/classes" `
     com.justybase.netezza.benchmark.ThroughputBenchmark --label justybase --mode typed --warmup 5 --iterations 20

# nzjdbc 11.1.0.0 (optional comparison target; place its jar on the classpath
# and do not add target/classes, because this project also ships an
# org.netezza.Driver compatibility alias)
java -Xms1g -Xmx1g -cp "target/test-classes;<nzjdbc.jar>" `
     com.justybase.netezza.benchmark.ThroughputBenchmark --label nzjdbc --driver org.netezza.Driver `
     --mode typed --warmup 5 --iterations 20
```

Use `--mode next` to measure row iteration only. The benchmark expects the `CONFORMANCE.FACT200K` fixture used by the conformance suite. The opt-in four-scenario bulk suite is described in [Bulk insert performance](#bulk-insert-performance); a cross-driver write comparison follows below.

### Bulk insert throughput

`BulkThroughputBenchmark` inserts 500 rows through a four-column prepared batch (`INTEGER`, `VARCHAR(100)`, `NUMERIC(12,2)`, `BOOLEAN`) in auto-commit mode. Timing covers `addBatch` and `executeBatch`; table creation, `TRUNCATE`, row-count verification and cleanup are excluded. The table is the median of five runs after two warmups on Java 21:

| Path | JustyBase | nzjdbc 11.1.0.0 |
|---|---:|---:|
| `addBatch` + `executeBatch`, 500 rows | 179.6 ms | 182.4 ms |

The bulk path uses Netezza's external-table transfer, so at 500 rows the handshake dominates the measurement; the two are effectively at parity, and larger batches amortize the handshake. On this driver the row-by-row path takes about 9 seconds for the same 500 rows (see [Bulk insert performance](#bulk-insert-performance)).

```powershell
# JustyBase
java -Xms1g -Xmx1g -cp "target/test-classes;target/classes" `
     com.justybase.netezza.benchmark.BulkThroughputBenchmark --label justybase --rows 500

# nzjdbc 11.1.0.0 (optional comparison target)
java -Xms1g -Xmx1g -cp "target/test-classes;<nzjdbc.jar>" `
     com.justybase.netezza.benchmark.BulkThroughputBenchmark --label nzjdbc --driver org.netezza.Driver --rows 500
```

The benchmark creates and drops `PERF_BULK_<LABEL>` and accepts `--warmup` and `--iterations` for longer runs.

## Design notes

- The frontend query packet is written to the socket in one call; splitting it into per-byte writes caused delayed-ACK stalls of roughly 40 ms per query on the test appliance.
- DBOS rows are indexed lazily and decoded only when a getter needs a value.
- Text `DataRow` frames use a reusable buffer together with the exact frame length, never the buffer capacity.
- Bulk rows are measured once and escaped/encoded straight into the reusable DATA packet buffer, so streaming a batch does not allocate per field, row or frame. Prepared batches store parameter snapshots and render SQL literals only if the bulk plan falls back to row-by-row execution.
- `QueryLatencyProbe` reports `executeQuery`/`next`/`close` phases per query when investigating regressions.

## Limitations

Named `CallableStatement` parameters, savepoints, scrollable/updatable result sets, Kerberos/GSSAPI, virtual streams, `COPY`/`load_data`, and built-in connection pooling are outside the current surface. Use HikariCP, DBCP, c3p0 or a container-provided `DataSource` for pooling. `Driver.jdbcCompliant()` therefore reports `false`.

`Connection.setCatalog` changes the database by opening a new physical session. An open transaction is rolled back, statements created before the switch are closed, session flags (`autoCommit`, isolation) are restored, and the schema returns to the connection default (`currentSchema` or the server default). If the target database cannot be opened, the current session stays usable and `getCatalog()` is unchanged; `setCatalog(null)` is a no-op. `setTransactionIsolation` applies `TRANSACTION_READ_COMMITTED` and `TRANSACTION_SERIALIZABLE` with `SET SESSION CHARACTERISTICS`, and keeps `TRANSACTION_NONE` as a client-side marker for compatibility. `commit()`/`rollback()` are intentionally lenient in auto-commit mode.

`getIndexInfo`, `getProcedureColumns`, `getFunctionColumns`, privilege and UDT metadata return the standard empty result shape where the Netezza catalog has no equivalent; they do not throw. Netezza rejects `DEFAULT` inside a `VALUES` tuple, so the bulk path falls back to the original statement and the server reports the error.

`PreparedStatement` parameters are rendered as SQL literals on the client. Use only trusted SQL templates and parameter values; this is not server-side bind support. `PreparedStatement.getMetaData()` is best-effort: it asks the server to describe `SELECT`/`WITH` statements and returns `null` for other statements or when the description fails.

LOBs and streams are materialized in memory and bounded by `maxStreamBytes`; the driver does not implement server-side LOB locators or true streaming LOBs.

Server types outside the supported JDBC surface fail explicitly instead of being guessed. `JSON`, `JSONB` and `JSONPATH` can be created in DDL, but selecting them fails with the server's `Client does not support JSON datatype` error. `GEOMETRY`/`ST_GEOMETRY`, `BYTEA`, `BLOB`/`CLOB`/`NCLOB`/`SQLXML`, `UUID` and `VECTOR` are not usable through the tested 11.2.2.1 appliance and client combination. `TIMESTAMPTZ` parameters are rejected with `0A000` because Netezza has no timestamp-with-time-zone literal; `TIMETZ` is supported as `java.time.OffsetTime`.

## Security

- Supply credentials through `Properties` rather than URLs, and never commit or log passwords.
- The LIVE suite reads credentials from `NZ_DEV_*` environment variables; test output does not print secret values.
- Use `sslmode=verify-full` when the server certificate and host name must be verified.
- Custom CAs, trust stores and client certificates are supported through `caCertificate`, `trustStore` and the `clientCertificate`/`clientKey` or `keyStore` options; private keys are read locally and never sent to the server.

## License

JustyBase NetezzaSQL JDBC is licensed under the [Apache License, Version 2.0](LICENSE).

Copyright notices:

- Copyright Krzysztof Duśko
- Copyright 2020 IBM, Inc.
- Copyright 2007–2009 Mathieu Fenniak

See [LICENSE](LICENSE) for the full license text.

## Status

Version `0.1.0` - first public release. The driver targets Java 17 and is verified by CI on Java 17, 21 and 25. The 127-test offline suite, the 181-test LIVE suite (also green on all three JDKs through the ephemeral Docker/WSL2 matrix), the shared conformance corpus (108/108 for `full`, 216/216 for Java + C#) and the opt-in four-scenario bulk-insert performance suite are green on the test appliance. Release jars are published as GitHub Releases and in GitHub Packages under `com.justybase:netezza-sql:0.1.0`, including sources and javadoc.
