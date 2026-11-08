import os, sys, time, frida

# usage: inject.py <display-name> [pkg-id]
NAME = sys.argv[1] if len(sys.argv) > 1 else "ClaudeWilson"
PKG  = sys.argv[2] if len(sys.argv) > 2 else "com.walter.claudewilson"
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
           "persona.compiled.js"), encoding="utf-8").read()

d = frida.get_usb_device(timeout=15)
pid = None
for p in d.enumerate_processes():
    if p.name.lower() == NAME.lower():
        pid = p.pid; break

if pid is None:                      # spawn path: wait for ART before loading
    pid = d.spawn([PKG])
    d.resume(pid)
    time.sleep(2.5)                  # let zygote fork + ActivityThread boot

sess = d.attach(pid)
def onmsg(m, data):
    print("[msg]", m.get("payload", m))
s = sess.create_script(src)
s.on("message", onmsg)
s.load()
print("injected pid", pid, "- ctrl-c to detach")
try:
    while True: time.sleep(1)
except KeyboardInterrupt:
    sess.detach()
