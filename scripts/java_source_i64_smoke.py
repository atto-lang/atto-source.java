#!/usr/bin/env python3
"""Execute the reviewed portable i64 *exit-code subset* on the REAL Graal compiler.

The Java/Graal probe exposes values via process status, not a full-width i64
result API. Four cases can be observed losslessly; three cannot. Never claim
7/7 executable i64 parity from this harness.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "contracts/v1/source-i64/portable-i64.v1.json"
BLOB = "bbc319c19ecd007109d33592e210091eb267e5a2"
IDS = ("precedence","grouping","signed-multiplication","subtraction",
       "negative-result","max-minus-one","signed-minimum")

def require(ok,reason):
    if not ok:raise RuntimeError(reason)

def read_corpus():
    raw=CORPUS.read_bytes()
    require(len(raw)<=8192 and
            hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()==BLOB,
            "fixture bytes differ from reviewed corpus")
    def nodups(pairs):
        result={}
        for k,v in pairs:
            require(k not in result,"duplicate corpus JSON key")
            result[k]=v
        return result
    value=json.loads(raw.decode("utf-8"),object_pairs_hook=nodups)
    require(type(value) is dict and set(value)=={"schema_version","status","scope","cases"},
            "unexpected corpus shape")
    require(value["schema_version"]=="1.0.0" and value["status"]=="reference-only-not-compiled-execution",
            "fixture unexpectedly claims execution")
    items=value["cases"]
    require(type(items) is list and len(items)==7,"corpus case count mismatch")
    for n,c in enumerate(items):
        require(type(c) is dict and set(c)=={"id","expression","expected"},"case schema mismatch")
        require(c["id"]==IDS[n],"case ID or ordering mismatch")
        require(type(c["expression"]) is str and re.fullmatch(r"[0-9+*() \-]{1,256}",c["expression"]),"unsupported corpus expression")
        require(type(c["expected"]) is str and re.fullmatch(r"-?(?:0|[1-9][0-9]*)",c["expected"]),"noncanonical i64 expectation")
    return items

def run(argv):
    return subprocess.run(argv,cwd=ROOT,stdin=subprocess.DEVNULL,
                          capture_output=True,timeout=90,check=False)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--report",type=Path)
    args=parser.parse_args()
    launcher=ROOT/"target/classes/dev/oreslang/launcher/OresMain.class"
    classpath=ROOT/"target/conformance-classpath.txt"
    probe=ROOT/"contracts/v1/java_probe.py"
    for target in (launcher,classpath,probe):
        require(target.is_file(),"built Java compiler / probe prerequisite missing: "+str(target))
    require(classpath.read_text(encoding="utf-8").strip(),"resolved classpath is empty")
    cases=read_corpus()
    results=[]
    with tempfile.TemporaryDirectory(prefix="ores-graal-i64-") as folder:
        source=Path(folder)/"fixture.ores"
        for index,c in enumerate(cases):
            program="pub fnc main():int{return "+c["expression"]+";}\n"
            source.write_text(program,encoding="utf-8")
            compilation=run([sys.executable,str(probe),str(source)])
            require(compilation.returncode==0,
                    "Graal source compilation failed: "+c["id"]+" "+compilation.stderr.decode("utf-8","replace")[:1024])
            if index < 4:
                expected=int(c["expected"])
                require(0<=expected<=255,"not representable in process exit code")
                process=run([sys.executable,str(probe),str(source),"--execute"])
                require(process.returncode==expected,
                        "Java guest execution result differs: "+c["id"]+" "+repr(process.returncode))
                results.append({"id":c["id"],"exit_status":process.returncode,
                                "source_sha256":hashlib.sha256(program.encode()).hexdigest()})
        for expr in ("3/2","9223372036854775808"):
            source.write_text("pub fnc main():int{return "+expr+";}\n",encoding="utf-8")
            neg=run([sys.executable,str(probe),str(source)])
            diagnostics=neg.stderr.decode("utf-8","replace")
            require(neg.returncode==1 and not neg.stdout and
                    re.search(r"\b(error|invalid|unsupported|overflow|out of range|type mismatch|cannot|failed)\b",
                              diagnostics,re.IGNORECASE) is not None and
                    re.search(r"Traceback|NoClassDefFoundError|ClassNotFoundException|OutOfMemoryError",
                              diagnostics,re.IGNORECASE) is None,
                    "source diagnostic missing or infrastructure failure: "+
                    expr+" "+repr((neg.returncode,diagnostics[:1200])))
    checkout=run(["git","rev-parse","--verify","HEAD"])
    require(checkout.returncode==0 and
            re.fullmatch(rb"[0-9a-f]{40}",checkout.stdout.strip()) is not None,
            "cannot independently identify Git checkout")
    clean=run(["git","status","--porcelain=v1","--untracked-files=no"])
    require(clean.returncode==0 and clean.stdout==b"",
            "compiler checkout has unexpected tracked source modifications")
    receipt={
        "kind":"graal-java-real-compiler-exit-subset",
        "actual_checkout_commit":checkout.stdout.decode("ascii").strip(),
        "source_commit_binary_link_attested":False,
        "fixture_git_blob":BLOB,
        "launcher_class_sha256":hashlib.sha256(launcher.read_bytes()).hexdigest(),
        "executed":len(results),"corpus_total":len(cases),
        "not_executed_due_to_lossy_os_exit":[c["id"] for c in cases[4:]],
        "native_image_attested":False,"observed":results
    }
    if args.report:
        args.report.write_text(json.dumps(receipt,sort_keys=True,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(receipt,sort_keys=True))
    return 0

if __name__=="__main__":
    try:sys.exit(main())
    except (OSError,RuntimeError,ValueError,UnicodeError,subprocess.SubprocessError) as error:
        print("Java source-i64 execution DENIED: "+str(error),file=sys.stderr)
        sys.exit(1)
