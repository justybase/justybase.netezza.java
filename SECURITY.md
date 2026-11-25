# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | Yes |

Security fixes are released as a new patch version. Published GitHub Releases are immutable;
a fixed release gets a new version number.

## Reporting a vulnerability

Please **do not** open a public issue for a security problem. Use GitHub's private vulnerability reporting
for this repository: [Report a vulnerability](https://github.com/justybase/justybase.nz.java/security/advisories/new).
Include the driver version, JDK version, Netezza release, a minimal reproduction and the impact you observed.
You should receive an acknowledgement within a few working days. If the report is accepted, a fix and an
advisory are prepared together before the details are made public.

## Security notes for users

- Supply credentials through `Properties`, never in URLs, logs or source control.
- Use `sslmode=verify-full` when the server certificate and hostname must be verified; custom CAs,
  trust stores and mutual TLS are supported through the documented connection options.
- Netezza does not expose server-side bind parameters. This driver renders `PreparedStatement` values as
  quoted SQL literals on the client: **both the SQL template and the parameter values must be trusted**.
  Validate untrusted input before passing it as a parameter.
- `maxStreamBytes` bounds stream materialization (16 MiB by default); lower it for untrusted streams.
