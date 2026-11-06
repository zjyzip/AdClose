"""Real grpcio HTTP/2 traffic fixture. Third-party packages are installed only under build/."""
import argparse
import base64
from concurrent import futures
import gzip
import json
from pathlib import Path
import socket
import struct
import sys
import threading
import zlib


def capture(output):
    import grpc
    import google.protobuf
    from google.protobuf.wrappers_pb2 import StringValue

    def unary(request, context):
        return StringValue(value="reply:" + request.value)

    def compressed(request, context):
        context.set_compression(grpc.Compression.Gzip)
        return StringValue(value="compressed-reply:" + request.value)

    def server_stream(request, context):
        for index in range(3):
            yield StringValue(value=f"stream:{index}:{request.value}")

    def bidi(requests, context):
        for request in requests:
            yield StringValue(value="bidi:" + request.value)

    def empty(request, context):
        return StringValue()

    def denied(request, context):
        context.abort(grpc.StatusCode.PERMISSION_DENIED, "fixture denied")

    ser = StringValue.SerializeToString
    parse = StringValue.FromString
    handler = grpc.method_handlers_generic_handler("fixture.Service", {
        "Unary": grpc.unary_unary_rpc_method_handler(unary, request_deserializer=parse, response_serializer=ser),
        "Gzip": grpc.unary_unary_rpc_method_handler(compressed, request_deserializer=parse, response_serializer=ser),
        "ServerStream": grpc.unary_stream_rpc_method_handler(server_stream, request_deserializer=parse, response_serializer=ser),
        "Bidi": grpc.stream_stream_rpc_method_handler(bidi, request_deserializer=parse, response_serializer=ser),
        "Empty": grpc.unary_unary_rpc_method_handler(empty, request_deserializer=parse, response_serializer=ser),
        "Denied": grpc.unary_unary_rpc_method_handler(denied, request_deserializer=parse, response_serializer=ser),
    })
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    server.add_generic_rpc_handlers((handler,))
    server_port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    stop = threading.Event()
    lock = threading.Lock()
    output.mkdir(parents=True, exist_ok=True)
    trace = (output / "traffic.bin").open("wb")
    counts = {"tx": 0, "rx": 0}
    errors = []
    connections = []

    def relay(source, destination, writing):
        try:
            while not stop.is_set():
                data = source.recv(65536)
                if not data:
                    break
                with lock:
                    trace.write(struct.pack(">BI", int(writing), len(data)))
                    trace.write(data)
                    counts["tx" if writing else "rx"] += len(data)
                destination.sendall(data)
        except OSError as error:
            if not stop.is_set():
                errors.append(str(error))
        finally:
            try:
                destination.shutdown(socket.SHUT_WR)
            except OSError:
                pass

    def proxy():
        client, _ = listener.accept()
        upstream = socket.create_connection(("127.0.0.1", server_port))
        connections.extend([client, upstream])
        a = threading.Thread(target=relay, args=(client, upstream, True))
        b = threading.Thread(target=relay, args=(upstream, client, False))
        a.start(); b.start(); a.join(); b.join()

    worker = threading.Thread(target=proxy, daemon=True)
    worker.start()
    channel = grpc.insecure_channel(f"127.0.0.1:{listener.getsockname()[1]}", options=(("grpc.enable_retries", 0),))
    cases = []

    def record(name, requests, responses, status="0"):
        cases.append({"path": "/fixture.Service/" + name,
                      "requests": [base64.b64encode(ser(value)).decode() for value in requests],
                      "responses": [base64.b64encode(ser(value)).decode() for value in responses],
                      "grpcStatus": status})

    try:
        grpc.channel_ready_future(channel).result(timeout=10)
        for name, value, compress in [("Unary", "hello gRPC 中文", False), ("Gzip", "compress me " * 8192, True), ("Empty", "", False)]:
            request = StringValue(value=value)
            call = channel.unary_unary("/fixture.Service/" + name, request_serializer=ser, response_deserializer=parse)
            response = call(request, timeout=10, compression=grpc.Compression.Gzip if compress else None)
            expected = "" if name == "Empty" else ("compressed-reply:" if compress else "reply:") + value
            assert response.value == expected
            record(name, [request], [response])
        request = StringValue(value="server streaming")
        call = channel.unary_stream("/fixture.Service/ServerStream", request_serializer=ser, response_deserializer=parse)
        responses = list(call(request, timeout=10))
        assert [r.value for r in responses] == [f"stream:{i}:{request.value}" for i in range(3)]
        record("ServerStream", [request], responses)
        requests = [StringValue(value=f"client message {i}") for i in range(3)]
        call = channel.stream_stream("/fixture.Service/Bidi", request_serializer=ser, response_deserializer=parse)
        responses = list(call(iter(requests), timeout=10))
        assert [r.value for r in responses] == ["bidi:" + r.value for r in requests]
        record("Bidi", requests, responses)
        request = StringValue(value="denied")
        call = channel.unary_unary("/fixture.Service/Denied", request_serializer=ser, response_deserializer=parse)
        try:
            call(request, timeout=10)
            raise AssertionError("Denied RPC unexpectedly succeeded")
        except grpc.RpcError as error:
            assert error.code() == grpc.StatusCode.PERMISSION_DENIED
        record("Denied", [request], [], "7")
    finally:
        channel.close()
        server.stop(0).wait(timeout=5)
        stop.set()
        for connection in connections:
            connection.close()
        listener.close()
        worker.join(timeout=5)
        assert not worker.is_alive()
        trace.close()
    assert counts["tx"] and counts["rx"], (counts, errors)
    manifest = {"grpcio": grpc.__version__, "protobuf": google.protobuf.__version__, "wireBytes": counts, "cases": cases}
    (output / "expected.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Real grpcio {grpc.__version__}: {len(cases)} RPCs completed over a TCP recording proxy; {counts}")


def headers(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    return dict(zip(lines[::2], lines[1::2]))


def messages(data, encoding):
    result, offset, compressed = [], 0, 0
    while offset < len(data):
        assert len(data) - offset >= 5
        flag, size = struct.unpack_from(">BI", data, offset)
        offset += 5
        assert flag in (0, 1) and size <= len(data) - offset
        body = data[offset:offset + size]
        offset += size
        if flag:
            compressed += 1
            body = gzip.decompress(body) if encoding == "gzip" else zlib.decompress(body)
        result.append(body)
    return result, compressed


def verify(output, replay):
    manifest = json.loads((output / "expected.json").read_text(encoding="utf-8"))
    cases = {case["path"]: case for case in manifest["cases"]}
    paths = list(replay.glob("*/path.txt"))
    assert len(paths) == len(cases), (len(paths), len(cases))
    compressed_total = 0
    for path in paths:
        directory = path.parent
        case = cases[path.read_text(encoding="utf-8")]
        assert (directory / "scheme.txt").read_text() == "http"
        for direction in ("request", "response"):
            h = headers(directory / (direction + ".headers"))
            if direction == "response":
                assert h["grpc-status"] == case["grpcStatus"]
            values, compressed = messages((directory / (direction + ".bin")).read_bytes(), h.get("grpc-encoding", "identity"))
            assert values == [base64.b64decode(value) for value in case[direction + "s"]], (case["path"], direction)
            compressed_total += compressed
        print("PASS production capture:", case["path"], "grpc-status=" + case["grpcStatus"])
    assert compressed_total >= 2, "The real library did not compress both request and response"
    (replay / "expected.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Verified all captured protobuf messages and trailers; {compressed_total} real compressed messages")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path, help="Temporary pip --target directory containing grpcio and protobuf")
    parser.add_argument("--out", type=Path, default=Path("build/grpc-validation/fixture"))
    parser.add_argument("--verify", type=Path, help="Verify bodies emitted by run_native_tests.py --replay")
    args = parser.parse_args()
    if args.dependencies:
        sys.path.insert(0, str(args.dependencies.resolve()))
    if args.verify:
        verify(args.out, args.verify)
    else:
        capture(args.out)
