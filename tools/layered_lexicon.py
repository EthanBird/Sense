"""SPLX/4 provenance: recover the exact SPLX/3 base by removing only tier-2 entries."""
import struct

MAX_BYTES = 64 * 1024 * 1024
MAX_RECORDS = 1_250_000


def records(data):
    if not 10 <= len(data) <= MAX_BYTES or data[:4] != b"SPLX":
        raise ValueError("Invalid or oversized SPLX payload")
    version, count = struct.unpack_from(">HI", data, 4)
    if version not in (3, 4) or not 1 <= count <= min(MAX_RECORDS, (len(data)-10)//12):
        raise ValueError("Invalid SPLX version/count")
    offset, previous = 10, b""
    for _ in range(count):
        if offset >= len(data): raise ValueError("Truncated record")
        length = data[offset]; offset += 1
        if not length or offset+length >= len(data): raise ValueError("Truncated code")
        code = data[offset:offset+length]; offset += length
        if any(c > 127 for c in code) or code <= previous: raise ValueError("Codes must be sorted, unique ASCII")
        previous = code
        candidate_count = data[offset]; offset += 1
        if not candidate_count: raise ValueError("Empty candidate record")
        values = []
        for _ in range(candidate_count):
            start = offset
            if offset >= len(data): raise ValueError("Truncated candidate")
            text_length = data[offset]; offset += 1
            if not text_length or offset+text_length+5 > len(data): raise ValueError("Truncated text/weight")
            data[offset:offset+text_length].decode("utf-8")
            offset += text_length+4
            initials_length = data[offset]; offset += 1
            if not initials_length or offset+initials_length >= len(data): raise ValueError("Truncated initials/tier")
            if any(c < 97 or c > 122 for c in data[offset:offset+initials_length]): raise ValueError("Invalid initials")
            offset += initials_length
            tier = data[offset]; offset += 1
            if tier > (2 if version == 4 else 1): raise ValueError("Unknown source tier")
            if tier == 2 and (not 2 <= initials_length <= 8 or len(code) > 48 or any(c < 97 or c > 122 for c in code)):
                raise ValueError("Supplement is full-pinyin multi-character vocabulary only")
            values.append((tier, data[start:offset]))
        yield code, values
    if offset != len(data): raise ValueError("Trailing SPLX bytes")


def project_base(data):
    output, count = bytearray(b"SPLX" + struct.pack(">HI", 3, 0)), 0
    for code, values in records(data):
        base = [raw for tier, raw in values if tier != 2]
        if not base:
            continue
        count += 1
        output.extend(bytes([len(code)]) + code + bytes([len(base)]))
        for value in base:
            output.extend(value)
    if not count: raise ValueError("Layered dictionary has no base records")
    struct.pack_into(">I", output, 6, count)
    return bytes(output)
