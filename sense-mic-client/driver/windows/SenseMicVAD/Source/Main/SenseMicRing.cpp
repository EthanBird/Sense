#include "SenseMicRing.h"

namespace
{
    // 500 ms at 48 kHz stereo PCM16. Overflow discards the oldest bytes, so a stalled
    // capture client resumes near live audio instead of replaying a multi-second backlog.
    constexpr ULONG kSenseMicRingCapacity = 48000u * 2u * sizeof(INT16) / 2u;
    UCHAR g_SenseMicRing[kSenseMicRingCapacity] = {};
    KSPIN_LOCK g_SenseMicRingLock;
    ULONG g_SenseMicReadOffset = 0;
    ULONG g_SenseMicWriteOffset = 0;
    ULONG g_SenseMicBytesAvailable = 0;

    VOID CopyIntoRing(_In_reads_bytes_(ByteCount) const UCHAR* Source, _In_ ULONG ByteCount)
    {
        const ULONG first = min(ByteCount, kSenseMicRingCapacity - g_SenseMicWriteOffset);
        RtlCopyMemory(g_SenseMicRing + g_SenseMicWriteOffset, Source, first);
        if (ByteCount > first)
        {
            RtlCopyMemory(g_SenseMicRing, Source + first, ByteCount - first);
        }
        g_SenseMicWriteOffset = (g_SenseMicWriteOffset + ByteCount) % kSenseMicRingCapacity;
    }

    VOID CopyFromRing(_Out_writes_bytes_(ByteCount) UCHAR* Destination, _In_ ULONG ByteCount)
    {
        const ULONG first = min(ByteCount, kSenseMicRingCapacity - g_SenseMicReadOffset);
        RtlCopyMemory(Destination, g_SenseMicRing + g_SenseMicReadOffset, first);
        if (ByteCount > first)
        {
            RtlCopyMemory(Destination + first, g_SenseMicRing, ByteCount - first);
        }
        g_SenseMicReadOffset = (g_SenseMicReadOffset + ByteCount) % kSenseMicRingCapacity;
    }
}

VOID SenseMicRingInitialize()
{
    KeInitializeSpinLock(&g_SenseMicRingLock);
    SenseMicRingReset();
}

VOID SenseMicRingReset()
{
    KIRQL oldIrql;
    KeAcquireSpinLock(&g_SenseMicRingLock, &oldIrql);
    g_SenseMicReadOffset = 0;
    g_SenseMicWriteOffset = 0;
    g_SenseMicBytesAvailable = 0;
    KeReleaseSpinLock(&g_SenseMicRingLock, oldIrql);
}

VOID SenseMicRingWrite(const UCHAR* Source, ULONG ByteCount)
{
    if (Source == nullptr || ByteCount == 0)
    {
        return;
    }
    if (ByteCount >= kSenseMicRingCapacity)
    {
        Source += ByteCount - kSenseMicRingCapacity;
        ByteCount = kSenseMicRingCapacity;
    }

    KIRQL oldIrql;
    KeAcquireSpinLock(&g_SenseMicRingLock, &oldIrql);
    const ULONG freeBytes = kSenseMicRingCapacity - g_SenseMicBytesAvailable;
    if (ByteCount > freeBytes)
    {
        const ULONG discard = ByteCount - freeBytes;
        g_SenseMicReadOffset = (g_SenseMicReadOffset + discard) % kSenseMicRingCapacity;
        g_SenseMicBytesAvailable -= discard;
    }
    CopyIntoRing(Source, ByteCount);
    g_SenseMicBytesAvailable += ByteCount;
    KeReleaseSpinLock(&g_SenseMicRingLock, oldIrql);
}

VOID SenseMicRingRead(UCHAR* Destination, ULONG ByteCount)
{
    if (Destination == nullptr || ByteCount == 0)
    {
        return;
    }
    ULONG copied = 0;
    KIRQL oldIrql;
    KeAcquireSpinLock(&g_SenseMicRingLock, &oldIrql);
    copied = min(ByteCount, g_SenseMicBytesAvailable);
    if (copied > 0)
    {
        CopyFromRing(Destination, copied);
        g_SenseMicBytesAvailable -= copied;
    }
    KeReleaseSpinLock(&g_SenseMicRingLock, oldIrql);

    if (copied < ByteCount)
    {
        RtlZeroMemory(Destination + copied, ByteCount - copied);
    }
}
