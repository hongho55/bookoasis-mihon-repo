# BookOasis Mihon Extension

A private-library Mihon source for [BookOasis](https://book.giwa.top).

## Add to Mihon

1. Open **More → Settings → Browse → Extension repos**.
2. Add this URL:

   ```text
   https://raw.githubusercontent.com/hongho55/bookoasis-mihon-repo/main/index.min.json
   ```

3. Open **Browse → Extensions**.
4. Enable the **Multi** language filter and install **BookOasis**.
5. Open the BookOasis source settings and enter the BookOasis username and password.

The server address is already configured as `https://book.giwa.top`; it should not be added as an extension repository URL.

## Supported scope

- General BookOasis library
- Basic Auth
- Series search and browsing
- ZIP/CBZ books rendered as Mihon image pages

EPUB, PDF, adult libraries, and direct file download are intentionally outside this first release.

## Source and build

The adapter source is in [`src/`](src/). It is built with the Keiyoushi Mihon extension toolchain and uses the `KeiSource` API. The upstream build framework is available at [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

The published APK is signed with the repository's public signing fingerprint in [`repo.json`](repo.json). The private signing key is never published.

## License

The extension build framework and this adapter are distributed under the Apache License 2.0. See [`LICENSE`](LICENSE).
