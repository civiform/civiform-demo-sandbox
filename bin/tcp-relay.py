#!/usr/bin/env python3
"""Minimal bidirectional TCP relay.

Exists because `session-manager-plugin` binds its forwarded port to 127.0.0.1
and offers no way to change that. A container on the default bridge network
reaches the host as the bridge gateway (192.168.9.1 here), not as loopback, so
it cannot see the tunnel at all -- the connection is refused at the host.

This relay accepts on an address the bridge can reach and forwards to the
loopback listener the plugin created.

socat does the same job in one line; this is here so the dev setup does not
need a root install:

    socat TCP-LISTEN:15432,bind=192.168.9.1,reuseaddr,fork TCP:127.0.0.1:15432

Usage:
    tcp-relay.py <listen-host> <listen-port> <target-host> <target-port>
"""

import socket
import sys
import threading


def pump(src: socket.socket, dst: socket.socket) -> None:
    """Copy bytes one way until the source closes, then half-close the target.

    The half-close matters: Postgres clients send a Terminate message and then
    expect the server side to finish. Tearing the whole pair down on the first
    EOF truncates the other direction mid-response.
    """
    try:
        while chunk := src.recv(65536):
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client: socket.socket, target: tuple[str, int]) -> None:
    try:
        upstream = socket.create_connection(target, timeout=10)
    except OSError as exc:
        # Most likely the SSM session died. Say so rather than leaking a bare
        # refused connection back to the caller, which is what sent us here.
        print(f"  relay: upstream {target[0]}:{target[1]} unreachable: {exc}", flush=True)
        client.close()
        return

    upstream.settimeout(None)
    with client, upstream:
        a = threading.Thread(target=pump, args=(client, upstream), daemon=True)
        b = threading.Thread(target=pump, args=(upstream, client), daemon=True)
        a.start()
        b.start()
        a.join()
        b.join()


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2

    listen_host, listen_port, target_host, target_port = (
        sys.argv[1],
        int(sys.argv[2]),
        sys.argv[3],
        int(sys.argv[4]),
    )

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind((listen_host, listen_port))
    except OSError as exc:
        print(f"relay: cannot bind {listen_host}:{listen_port}: {exc}", file=sys.stderr)
        return 1
    server.listen(64)
    print(f"  relay: {listen_host}:{listen_port} -> {target_host}:{target_port}", flush=True)

    try:
        while True:
            client, _ = server.accept()
            threading.Thread(
                target=handle, args=(client, (target_host, target_port)), daemon=True
            ).start()
    except KeyboardInterrupt:
        return 0
    finally:
        server.close()


if __name__ == "__main__":
    sys.exit(main())
