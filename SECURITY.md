# Security policy

NavFrame is experimental software. Do not rely on it as the sole source of directions, speed information, or road safety decisions.

## Reporting a vulnerability

Please report security issues privately. If GitHub private vulnerability reporting is enabled for this repository, use the **Security → Advisories** flow. Otherwise contact the maintainers using the private contact channel listed on the repository profile. Do not publish exploit details or sensitive data in a public issue.

Include the affected version or commit, Android version/device where relevant, steps to reproduce, and the impact. Remove personal data, exact coordinates, Bluetooth addresses, credentials, and access tokens from logs or screenshots.

## Scope and data handling

The app can send search text to the configured geocoder, origin and destination coordinates to the configured routing service, and map-resource requests for the visible region to the configured style provider. Regional package catalogs and archives are fetched over HTTPS and checked against catalog sizes and SHA-256 values before package installation. A hash detects accidental or malicious content changes relative to the catalog; it does not authenticate a catalog whose source the user has changed.

Please do not send production traffic or large-scale automated requests to public demo services as part of vulnerability research. Use a local service or a provider explicitly authorizing the test.

## Supported versions

There is no formal long-term support policy yet. Report issues against the latest public release and include the exact version/build when known.
