# ShareMe protocol v1

The transport is a TLS socket. The sender validates the receiver certificate against the exact SHA-256 pin supplied out-of-band in the QR/link; it never disables TLS validation globally. The receiver generates its identity and bearer token per application process. Identity persistence and revocation are future work.

The link is `shareme://IPv4:port?key=<64 lowercase hex>&token=<32 lowercase hex>`. Anyone who possesses it can upload. Pairing via a trusted screen is the trust establishment step; links must not be discovered by broadcasting credentials.

Framing currently uses Java `DataInput/OutputStream`: `writeUTF` modified UTF-8 with an unsigned 16-bit encoded byte length, and signed big-endian 64-bit sizes/offsets. All accepted paths are bounded to 2,048 characters. An iOS implementation must use this exact framing (including modified UTF-8), or introduce a negotiated protocol v2 using standard UTF-8. No implicit Java object serialization is used.

1. Client writes UTF `SHAREME/1`, then UTF bearer token.
2. Receiver checks the token in constant time and replies UTF `OK`, or rejects and closes.
3. One command follows per connection:
   - `PING`: receiver replies with device name.
   - `PAIR`: client supplies its name and own connection link over the authenticated TLS channel. Receiver stores the reverse endpoint using the connection’s actual source IP, and returns its name. Both sides can now send independently.
   - `DIRECTORY`: client supplies a relative path; receiver creates the directory and replies `OK`.
   - `FILE`: client supplies path, 64-bit size, and UTF SHA-256 hex. Receiver replies `READY` and 64-bit resume offset. Client streams exactly `size - offset` bytes. Receiver syncs, verifies, finalizes, and replies `OK`.

Application failures are UTF messages rather than `READY` or `OK`. Each socket has a 30-second idle timeout; the sender allows up to ten minutes for final integrity verification. The sender reconnects up to twice following I/O errors. Application rejections require the user to resolve the underlying issue.

Partial filenames are SHA-256 of path, size, and expected content digest. Completion receipts point to finalized files and are checked against actual contents before retry acknowledgements. These records support re-sending an unchanged file after a process restart, while session pairing must be repeated. No partially received file is exposed in the received-files folder.

Current constraints: IPv4 connection links, one incoming payload at a time, foreground mobile lifecycle, no sender queue persistence, and no partial-storage expiry. A paired sender is trusted to consume disk space; quotas beyond the available-space check are not yet implemented.
