import subprocess, re, os, time

SRC = 'src/torrent_pieces.lfe'
original = open(SRC).read()
ANSI = re.compile(r'\x1b\[[0-9;]*m')

mutants = [
    ("M1 complete? counts needed instead of verified",
     """(defun complete? (state)
  (== (sets:size (maps:get 'verified state))
     (maps:get 'total-pieces state)))""",
     """(defun complete? (state)
  (== (sets:size (maps:get 'needed state)) 0))"""),
    ("M2 endgame? ignores in-flight",
     """(defun endgame? (piece-state threshold)
  (=< (+ (needed-count piece-state) (in-flight-count piece-state))
      threshold))""",
     """(defun endgame? (piece-state threshold)
  (=< (needed-count piece-state) threshold))"""),
    ("M3 select-piece tie-break picks highest index",
     "(tuple (held-by-count piece-index all-peers-available)\n                               piece-index))",
     "(tuple (held-by-count piece-index all-peers-available)\n                               (- piece-index)))"),
    ("M4 piece-length drops the zero clamp",
     "(max 0 (- finish start))",
     "(- finish start)"),
    ("M5 total-piece-count floors instead of ceilings",
     "(+ (div (- total-length 1) standard-piece-length) 1)",
     "(div total-length standard-piece-length)"),
    ("M6 mark-verified leaves the piece in flight",
     """      (tuple 'ok (maps:put 'in-flight (sets:del_element piece-index in-flight)
                     (maps:put 'verified
                               (sets:add_element piece-index
                                                 (maps:get 'verified state))
                               state)))""",
     """      (tuple 'ok (maps:put 'verified
                     (sets:add_element piece-index
                                       (maps:get 'verified state))
                     state))"""),
    ("M7 select-pieces-endgame drops in-flight pieces",
     "(sets:union (maps:get 'needed piece-state)\n                                (maps:get 'in-flight piece-state))",
     "(maps:get 'needed piece-state)"),
    ("M8 block size is 16383",
     "(defun block-size () 16384)",
     "(defun block-size () 16383)"),
    ("M9 verify-piece only rejects an empty hash",
     "((not (== 20 (byte_size expected-hash)))",
     "((== 0 (byte_size expected-hash))"),
    ("M10 select-piece frequency counts every peer",
     "(if (sets:is_element piece-index (as-set peer))\n       (+ acc 1)\n       acc)",
     "(+ acc 1)"),
    ("M11 initial-piece-state omits the last piece",
     "(lists:seq 0 (- total-pieces 1))",
     "(lists:seq 0 (- total-pieces 2))"),
    ("M12 select-piece unions in-flight pieces into candidates",
     "(sets:intersection\n                      (as-set peer-available)\n                      needed)",
     "(sets:union\n                      (as-set peer-available)\n                      needed)"),
]

def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)

def pattern_for(snippet):
    return re.compile(r'\s+'.join(re.escape(part) for part in snippet.split()))

def apply_mutant(text, old, new):
    pattern = pattern_for(old)
    return pattern.sub(lambda _m: new, text, count=1), len(pattern.findall(text))

def refresh(src_text):
    open(SRC, 'w').write(src_text)
    os.utime(SRC, None)
    for f in ('test/torrent_pieces.beam',
              '_build/test/lib/lfe_spike/ebin/torrent_pieces.beam'):
        try: os.remove(f)
        except FileNotFoundError: pass

def examples():
    r = run('rebar3 as test lfe ltest')
    out = ANSI.sub('', r.stdout + r.stderr)
    m = re.search(r'Tests:\s*(\d+)\s+Passed:\s*(\d+)\s+Skipped:\s*(\d+)\s+Failed:\s*(\d+)\s+Erred:\s*(\d+)', out)
    if not m:
        return None, r.returncode, out
    failed = int(m.group(4)) + int(m.group(5))
    return failed == 0, r.returncode, out

def props():
    r = run("erl -noshell -pa _build/test/lib/*/ebin -eval "
            "'io:format(\"RESULT ~p~n\",[proper:module(prop_torrent_pieces,[{numtests,100}])]), halt().'")
    out = ANSI.sub('', r.stdout + r.stderr)
    m = re.search(r'RESULT (\[.*?\])\n', out, re.S)
    if not m:
        return None, out
    return m.group(1).strip() == '[]', out

results = []
for name, old, new in mutants:
    mutated, matches = apply_mutant(original, old, new)
    if matches != 1:
        results.append((name, 'PATCH-FAILED (%d matches)' % matches, '', ''))
        print(results[-1], flush=True)
        continue
    refresh(mutated)
    r = run('rebar3 as test compile')
    if r.returncode != 0:
        results.append((name, 'COMPILE-FAILED', '', r.stderr[-400:]))
        print(results[-1], flush=True)
        continue
    ex_ok, ex_code, ex_out = examples()
    pr_ok, pr_out = props()
    results.append((name,
                    ('examples PASS (exit %d)' % ex_code) if ex_ok else 'examples FAIL',
                    'props PASS' if pr_ok else ('props FAIL' if pr_ok is False else 'props ERROR'),
                    ''))
    print('%-58s %-24s %s' % results[-1][:3], flush=True)

refresh(original)
run('rebar3 as test compile')
print('\nSUMMARY')
for r in results:
    print('  %-58s %-24s %s' % r[:3])
