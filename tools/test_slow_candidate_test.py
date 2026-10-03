import struct
import unittest
from unittest.mock import patch
from test_slow_candidate import Jdwp


def reply(identity, data=b"", error=0):
    return struct.pack(">IIBH", 11 + len(data), identity, 0x80, error) + data


class FragmentedSocket:
    def __init__(self, data):
        self.data = data
        self.sent = []
        self.closed = False

    def recv(self, length):
        size = min(length, 3)
        result, self.data = self.data[:size], self.data[size:]
        return result

    def sendall(self, data):
        self.sent.append(data)

    def close(self):
        self.closed = True


class SlowWorkerProtocolTest(unittest.TestCase):
    def test_fragmented_packets_non_suspending_event_and_target_thread_only(self):
        event = struct.pack(">IIBBB", 16, 9, 0, 64, 100) + b"\0\0\0\0\0"
        identity = (77).to_bytes(8, "big")
        name = b"sense-candidate-decoder"
        stream = (b"JDWP-Handshake" + event + reply(1, struct.pack(">5I", 8, 8, 8, 8, 8)) +
                  reply(2, struct.pack(">I", 1) + identity) + reply(3, struct.pack(">I", len(name)) + name) +
                  reply(4, struct.pack(">II", 4, 0)) + reply(5) + reply(6) + reply(7))
        sock = FragmentedSocket(stream)
        with patch("socket.create_connection", return_value=sock):
            debugger = Jdwp(123)
            thread = debugger.idle_candidate_thread()
            self.assertEqual(identity, thread)
            debugger.command(11, 2, thread)
            debugger.command(11, 3, thread)
            debugger.close()
        self.assertTrue(sock.closed)
        self.assertEqual((11, 2), tuple(sock.sent[5][9:11]))
        self.assertEqual(identity, sock.sent[5][11:])
        self.assertNotIn(bytes([1, 8]), [packet[9:11] for packet in sock.sent[1:]])

    def test_initialization_failure_closes_connection(self):
        sock = FragmentedSocket(b"bad handshakes!")
        with patch("socket.create_connection", return_value=sock), self.assertRaises(ValueError):
            Jdwp(123)
        self.assertTrue(sock.closed)

    def test_suspending_event_is_rejected_and_socket_closed(self):
        event = struct.pack(">IIBBB", 16, 9, 0, 64, 100) + b"\2\0\0\0\0"
        sock = FragmentedSocket(b"JDWP-Handshake" + event)
        with patch("socket.create_connection", return_value=sock), self.assertRaises(ValueError):
            Jdwp(123)
        self.assertTrue(sock.closed)


if __name__ == "__main__":
    unittest.main()
