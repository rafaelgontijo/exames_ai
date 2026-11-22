"""Stream only explicitly selected synthetic instrumentation events; discard other output."""
import json
from queue import Queue, Empty
import subprocess
from threading import Thread
from time import monotonic


def run_events(adb, arguments, event_key, on_event, timeout):
    process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r'] + arguments,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    lines = Queue(maxsize=128)

    def reader():
        try:
            while True:
                line = process.stdout.readline(1024 * 1024 + 1)
                if not line:
                    break
                if len(line) > 1024 * 1024:
                    lines.put(RuntimeError('INSTRUMENTATION_LINE_LIMIT'))
                    return
                lines.put(line.decode('utf-8', errors='replace').rstrip())
        finally:
            lines.put(None)

    Thread(target=reader, daemon=True).start()
    deadline = monotonic() + timeout
    summary = {'runnerPassed': False}
    prefix = 'INSTRUMENTATION_STATUS: ' + event_key + '='
    try:
        while True:
            remaining = deadline - monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired('synthetic-instrumentation', timeout)
            try:
                line = lines.get(timeout=remaining)
            except Empty:
                raise subprocess.TimeoutExpired('synthetic-instrumentation', timeout)
            if line is None:
                break
            if isinstance(line, Exception):
                raise line
            if line.startswith(prefix):
                event = json.loads(line[len(prefix):])
                on_event(event)
                # Phase is our fixed test marker; no source, generated text or stack is printed.
                print('phase: ' + str(event.get('phase', 'unknown')), flush=True)
            elif line.startswith('INSTRUMENTATION_STATUS: stack='):
                summary['failureType'] = line.split('stack=', 1)[1].split(':', 1)[0]
            elif line.lstrip().startswith('at br.com.exames.app.') and 'firstTestFrame' not in summary:
                summary['firstTestFrame'] = line.strip()
            elif line == 'OK (1 test)':
                summary['runnerPassed'] = True
            elif 'FAILURES!!!' in line:
                summary['runnerPassed'] = False
        if process.wait(timeout=10) != 0:
            summary['runnerPassed'] = False
        return summary
    finally:
        if process.poll() is None:
            process.kill()
        process.stdout.close()
