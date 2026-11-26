#!/usr/bin/env python3
"""测试用的最小 MCP stdio Server：echo（文本）、boom（isError）、slow（不回响应，测超时/取消）。"""
import json, sys

TOOLS = [
    {"name": "echo", "description": "回显", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
     "annotations": {"readOnlyHint": True}},
    {"name": "boom", "description": "总是报错", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "slow", "description": "永不返回", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "bad name!", "description": "需清洗的名字", "inputSchema": {"type": "object", "properties": {}}},
]

def send(o):
    sys.stdout.write(json.dumps(o) + "\n"); sys.stdout.flush()

print("this is a log line on stdout, should be ignored", flush=True)
for line in sys.stdin:
    msg = json.loads(line)
    mid, method = msg.get("id"), msg.get("method")
    if mid is None:
        continue
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": mid, "result": {"protocolVersion": msg["params"]["protocolVersion"],
              "capabilities": {"tools": {}}, "serverInfo": {"name": "fake", "version": "0"}}})
    elif method == "tools/list":
        cursor = (msg.get("params") or {}).get("cursor")
        if cursor is None:
            send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS[:2], "nextCursor": "p2"}})
        else:
            send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS[2:]}})
    elif method == "tools/call":
        name = msg["params"]["name"]; args = msg["params"].get("arguments", {})
        if name == "echo":
            send({"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": "echo:" + args.get("text", "")}]}})
        elif name == "boom":
            send({"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": "kaboom"}], "isError": True}})
        elif name == "slow":
            pass
        else:
            send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": "unknown tool"}})
    else:
        send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "nope"}})
