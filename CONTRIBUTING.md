# Contributing

Thanks for helping improve the JustyBase NetezzaSQL JDBC driver.

## Before you start

- The driver targets Java 17+ and JDBC 4.3. Build with JDK 17 or newer; one jar is compiled with `--release 17`.
- The driver must stay free of runtime dependencies. JUnit is test-only.
- SQL behaviour is validated against a live Netezza appliance in the opt-in LIVE suite; offline tests must pass without one.

## Build and test

```powershell
mvn verify                       # offline tests + package (sources and javadoc jars included)
mvn -Plive test                  # LIVE suite; requires NZ_DEV_* environment variables
mvn -Pparity test                # shared cross-driver parity cases
mvn -Pperf test                  # opt-in bulk performance scenarios
```

See the [Testing](README.md#testing) section of the README for the required environment variables and fixture rules.
Fixture DDL runs only with `NZ_DEV_ALLOW_FIXTURE_DDL=1` and is restricted to the configured conformance schema.

## Pull requests

1. Keep changes focused; add or update a test for every behaviour change.
2. Run `mvn verify` before opening the PR. The GitHub Actions matrix checks JDK 17, 21 and 25 and the bytecode baseline.
3. Update the README when you change connection options, supported surface or limitations, and add a line to `CHANGELOG.md`.
4. Do not commit credentials, log files, `target/` output or anything under `orginal_reference/`.

## Releasing (maintainers)

1. Set the release version in `pom.xml`, update `README.md` and `CHANGELOG.md`, commit and tag `v<version>`.
2. Push the tag. `.github/workflows/release.yml` checks that the tag matches `project.version`, runs `mvn deploy` to publish `com.justybase:netezza-sql` to GitHub Packages, and attaches the jar, sources and javadoc jars to a GitHub Release.
3. No extra secrets are needed: the workflow uses the built-in `GITHUB_TOKEN` with `packages: write` and `contents: write`. The repository must allow write permissions for workflows.

To publish locally instead, create a GitHub personal access token with `write:packages` and add a `server` with id `github` to `~/.m2/settings.xml`, then run `mvn deploy`. Consumers need a token with `read:packages` — see [Installing from GitHub Packages](README.md#installing-from-github-packages).

## Reporting problems

Use GitHub Issues for bugs and GitHub's private vulnerability reporting for security problems — see [SECURITY.md](SECURITY.md).
Include the driver version, JDK version, Netezza release and a minimal reproduction.
