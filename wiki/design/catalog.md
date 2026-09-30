# Group file catalog

AFM owns flat per-group names and references; Spirit owns admission history, signed
identities and verified bytes. A live entry has a 16-byte random ID, a canonical
64-lowercase-hex author ID, generation, one UTF-8 name segment (1–255 bytes), a
64-lowercase-hex BLAKE3 hash and a nonnegative size. `(author, generation, entry)`
is the entry identity: two authors choosing the same random bytes create separate
entries. A remove signs the full target identity. Concurrent same-name adds remain
visible. Names reject invalid UTF-16, controls and format characters, both path
separators, and `.`/`..`; file-save paths **must still be sanitized** at export.
Meshes use Spirit's canonical `mesh1_` plus 43 unpadded URL-safe base64 characters.

`afm/catalog/op/1` signs deterministic bytes independent of JSON: ASCII
`AFM-CATALOG-OP-1`, big-endian unsigned-16 UTF-8 mesh and author lengths and bytes,
big-endian 64-bit generation and sequence, one byte kind (1 add, 3 remove), and
16 raw entry-ID bytes. Add appends length-prefixed UTF-8 name, 32 raw hash bytes,
and big-endian 64-bit size. Remove appends length-prefixed target author and 64-bit
target generation. The on-disk and network record wraps signed bytes with a 32-bit
length and length-prefixed Spirit signature. Decoders reject noncanonical encodings.

An op must match the mesh, verify under its author, belong to a generation in
Spirit's trusted admission history (including departed members), and have exactly
the next contiguous sequence for its `(author, generation)` clock. Batches validate
ops independently; invalid or premature ops are reported and other ops continue.
A premature op is requested again because the vector does not advance. Conflicting
signed variants of the same clock/sequence resolve to the lexicographically
lowest canonical record. Only the winner and the lowest known losing variant persist for a key (at most
`MAX_LOSER_PROOFS_PER_KEY = 1`). Replacing either atomically rewrites the log,
so unlimited equivocations cannot grow disk; the losing proof lets later peers
observe and report the conflict. The local
log is trusted after CRC and structural validation on reopen; signatures are
rechecked when operations arrive over the network. This avoids repeated native
signature checks on each app startup, but a malicious local disk writer can forge
catalog entries. A future migration may add asynchronous re-verification.

`MAX_SEQ_PER_CLOCK` is 1,024: ordinary small-group file history gets 1,024 writes
per device per admission, while one admitted key cannot advertise an unbounded
clock, make gaps permanent, or force unbounded retry. `MAX_LIVE_ENTRIES` is 8,192,
well below Spirit's 65,536-hash share limit. `MAX_CATALOG_CLOCKS = 256` and 1,024
sequences per clock give at most 262,144 winner records plus one bounded loser
proof per key; with a record up to roughly 750 bytes (255-byte name, legacy
64-byte mesh ID and 256-byte signature), the operation log is under 400 MiB per
group. An atomic rewrite temporarily needs another full-size log; corruption
backups also consume disk until removed. Small sequence counters and decoded
operations and entry objects can require several hundred MiB of memory worst-case. These are per-group
bounds, not an app-wide disk quota; old logs and corruption backups need manual
retention policy before larger-scale deployments.

The node root's sibling `groups/<meshId>/` stores CRC32-framed `ops.log` and
atomically replaced `seq-<author>-<generation>` counters. The append batch fsyncs
before publishing its view. A bad final frame is discarded; a bad frame followed
by another valid frame moves the whole log to `ops.log.corrupt-<time>`, retains
local sequence counters, and requests resync. An interrupted append truncates to
the committed offset. Directory metadata is fsynced after creating a log or
replacing a counter. Leaving deletes only that group's catalog, not its blobs.

A churning peer may occupy an exchange slot until its 15-second deadline; each
new lower signed winner triggers a full atomic log rewrite. After mid-log
corruption, own writes are refused until peers resupply the missing operations
so a sequence counter cannot skip a gap.
