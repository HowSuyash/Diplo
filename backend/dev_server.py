#!/usr/bin/env python3
"""
Diplo Local Preview Server
Serves the web UI and provides mock responses matching reference/API_CONTRACT.md
so you can test voice, SSE steps, flows, and confirmation dialogs live on phone & web.
"""

import http.server
import json
import os
import socketserver
import time
import urllib.parse

PORT = 8000
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")

class DiploHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB_DIR, **kwargs)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            payload = {
                "ok": True,
                "mock_tinyfish": True,
                "mock_flows": ["scholarship_status", "pnr_status"],
                "mock_search": False,
                "llm": True,
                "flows": ["scholarship_status", "pnr_status", "web_search"],
            }
            self.wfile.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/flows":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            flows = [
                {"id": "scholarship_status", "title": "स्कॉलरशिप स्टेटस", "required": ["application_id"]},
                {"id": "pnr_status", "title": "PNR स्टेटस", "required": ["pnr"]},
                {"id": "web_search", "title": "वेब सर्च", "required": ["query"]},
            ]
            self.wfile.write(json.dumps(flows, ensure_ascii=False).encode("utf-8"))
            return

        super().do_GET()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        try:
            req_data = json.loads(body)
        except Exception:
            req_data = {}

        if parsed.path == "/intent":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

            text = req_data.get("text", "").strip().lower()
            resp = self._resolve_intent(text)
            self.wfile.write(json.dumps(resp, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/web/run":
            flow = req_data.get("flow", "web_search")
            params = req_data.get("params", {})
            self._handle_sse_stream(flow, params)
            return

        self.send_error(404, "Endpoint not found")

    def _resolve_intent(self, text):
        if "scholarship" in text or "स्कॉलरशिप" in text or "छात्रवृत्ति" in text:
            return {
                "kind": "web",
                "action": "web_task",
                "params": {"flow": "scholarship_status", "application_id": "20261234"},
                "needs_confirmation": True,
                "speak": "क्या मैं एप्लीकेशन 20261234 का स्कॉलरशिप स्टेटस चेक करूं?",
            }
        if "pnr" in text or "टिकट" in text or "train" in text or "ट्रेन" in text:
            return {
                "kind": "web",
                "action": "web_task",
                "params": {"flow": "pnr_status", "pnr": "1234567890"},
                "needs_confirmation": True,
                "speak": "क्या मैं PNR 1234567890 का लाइव स्टेटस पता करूं?",
            }
        if "कॉल" in text or "call" in text:
            return {
                "kind": "phone",
                "action": "call",
                "params": {"contact": "Mom"},
                "needs_confirmation": True,
                "speak": "क्या माँ को फ़ोन मिला दूं?",
            }
        if "मैसेज" in text or "message" in text or "whatsapp" in text:
            return {
                "kind": "phone",
                "action": "send_message",
                "params": {"contact": "सुरेश", "message": "मैं 7 बजे आऊंगा", "channel": "whatsapp"},
                "needs_confirmation": True,
                "speak": "सुरेश को व्हाट्सएप पर मैसेज भेजना है: 'मैं 7 बजे आऊंगा'?",
            }
        if "अलार्म" in text or "alarm" in text:
            return {
                "kind": "phone",
                "action": "set_alarm",
                "params": {"hour": "6", "minute": "0", "label": "Morning"},
                "needs_confirmation": False,
                "speak": "सुबह 6:00 बजे का अलार्म लगा दिया है।",
            }
        if "गाना" in text or "song" in text:
            return {
                "kind": "chat",
                "action": "none",
                "params": {},
                "needs_confirmation": False,
                "speak": "सारे जहां से अच्छा, हिन्दोस्तां हमारा। हम बुलबुलें हैं इसकी, ये गुलसितां हमारा!",
            }
        if "kisan" in text or "किसान" in text:
            return {
                "kind": "web",
                "action": "web_search",
                "params": {"flow": "web_search", "query": "PM Kisan yojana kya hai"},
                "needs_confirmation": False,
                "speak": "पीएम किसान सम्मान निधि योजना के बारे में जानकारी देख रहा हूं।",
            }
        if "मौसम" in text or "weather" in text:
            return {
                "kind": "web",
                "action": "web_search",
                "params": {"flow": "web_search", "query": "Delhi weather today"},
                "needs_confirmation": False,
                "speak": "आज दिल्ली का मौसम साफ और सुहावना रहेगा, तापमान 28 डिग्री सेल्सियस है।",
            }
        return {
            "kind": "web",
            "action": "web_search",
            "params": {"flow": "web_search", "query": text},
            "needs_confirmation": False,
            "speak": f"‘{text}’ के बारे में जानकारी मिल गई है।",
        }

    def _handle_sse_stream(self, flow, params):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

        def send_ev(data_obj):
            msg = f"data: {json.dumps(data_obj, ensure_ascii=False)}\n\n"
            self.wfile.write(msg.encode("utf-8"))
            self.wfile.flush()

        try:
            if flow == "scholarship_status":
                send_ev({"type": "progress", "message": "Opening National Scholarship Portal in headless browser..."})
                time.sleep(0.8)
                send_ev({"type": "progress", "message": f"Entering Application ID: {params.get('application_id', '20261234')}"})
                time.sleep(0.9)
                send_ev({"type": "progress", "message": "Verifying PFMS disbursement status..."})
                time.sleep(0.8)
                send_ev({
                    "type": "result",
                    "speak": "आपकी स्कॉलरशिप स्वीकृत हो गई है और ₹45,000 की राशि प्रोसेस में है।",
                    "data": {
                        "status": "स्वीकृत (Approved)",
                        "details": "राशि ₹45,000 जल्द ही आपके आधार-लिंक्ड बैंक खाते में ट्रांसफर कर दी जाएगी।",
                    },
                })
            else:
                send_ev({"type": "progress", "message": "Searching official web portal..."})
                time.sleep(0.8)
                send_ev({
                    "type": "result",
                    "speak": "जानकारी मिल गई है।",
                    "data": {"status": "Complete"},
                })
        except Exception:
            pass

class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

if __name__ == "__main__":
    print(f"Starting Diplo dev server on port {PORT}")
    with ReusableTCPServer(("", PORT), DiploHandler) as httpd:
        httpd.serve_forever()
