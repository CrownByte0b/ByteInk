#!/usr/bin/env python3
"""Gives a stripped ELF library and its separate debug copy a GNU build ID derived from the
stripped library's own bytes: the MD5 of the library with its build ID zeroed.

lld's --build-id=md5 hashes the whole unstripped output, debug information included, and Zig's
compiler-rt records the absolute path of the Bazel sandbox that built it there. Libraries whose
code and data are byte-identical would otherwise get different IDs on different machines. Both
files get the same ID, which is what debuggers pair them by.

Usage: set-content-build-id.py LIBRARY DEBUG_COPY
"""
import hashlib
import struct
import sys


def build_id(data: bytes) -> slice:
    """Where the build ID's bytes are, from the ELF64 little-endian section headers."""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("expected a little-endian ELF64 file")
    (headers,) = struct.unpack_from("<Q", data, 0x28)
    entry_size, count, names_index = struct.unpack_from("<HHH", data, 0x3A)

    def section(index: int) -> tuple[int, int, int]:
        name, _, _, _, offset, size = struct.unpack_from("<IIQQQQ", data, headers + index * entry_size)
        return name, offset, size

    names = section(names_index)[1]
    for index in range(count):
        name, offset, _ = section(index)
        if data[names + name:data.index(b"\0", names + name)] == b".note.gnu.build-id":
            name_size, id_size, note_type = struct.unpack_from("<III", data, offset)
            if name_size != 4 or note_type != 3 or data[offset + 12:offset + 16] != b"GNU\0" or id_size != 16:
                raise SystemExit("expected a 16-byte GNU build ID note")
            return slice(offset + 16, offset + 16 + id_size)
    raise SystemExit("no .note.gnu.build-id section")


library, debug_copy = sys.argv[1:3]
stripped = bytearray(open(library, "rb").read())
stripped[build_id(stripped)] = bytes(16)
identity = hashlib.md5(stripped).digest()
for path in (library, debug_copy):
    data = bytearray(open(path, "rb").read())
    data[build_id(data)] = identity
    open(path, "wb").write(data)
print(identity.hex())
