#pragma once

#include <ntddk.h>

// Shared non-paged render-to-capture FIFO. Both WaveRT endpoints are fixed to
// 48 kHz, stereo, signed PCM16 so the driver can copy bytes without conversion.
VOID SenseMicRingInitialize();
VOID SenseMicRingReset();
VOID SenseMicRingWrite(_In_reads_bytes_(ByteCount) const UCHAR* Source, _In_ ULONG ByteCount);
VOID SenseMicRingRead(_Out_writes_bytes_(ByteCount) UCHAR* Destination, _In_ ULONG ByteCount);
