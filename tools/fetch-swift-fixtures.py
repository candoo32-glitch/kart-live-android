#!/usr/bin/env python3
"""Fetch immutable, hash-verified native captures; keep raw CSVs out of this repo."""
import argparse, hashlib, json, shutil, urllib.parse, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
COMMIT='3c9b2a878fcb24988579e5903116873a6cecfb25'
FILES={
 '117':('docs/orientation-research/data/iteration117-test/KartLive-Diagnostics(10).csv','5841b2719a1c22333d786bbe56779cb2053ec7e2f14230d8021425191c004164'),
 '118':('docs/orientation-research/iteration118-physical-test-2026-10-10/KartLive-Diagnostics(20261010-173203).csv','3529b53f52db47d6da8ca7cf050c5729a609716263a62443ecfd07ea1a3bdeb2'),
 '122':('docs/orientation-research/iteration122-physical-test-2026-10-11/A2DDC25E-1A83-482F-B6DB-C598B2984333.csv','552ce42071e74182f9044d616f95f44206467047ea22a53754c3b3c58be60435')}
p=argparse.ArgumentParser();p.add_argument('--source-repo',type=Path);args=p.parse_args()
out=ROOT/'app/build/swift-fixtures';out.mkdir(parents=True,exist_ok=True)
for version,(path,checksum) in FILES.items():
 target=out/(version+'.csv')
 if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()==checksum:continue
 if args.source_repo: data=(args.source_repo/path).read_bytes()
 else:
  url='https://raw.githubusercontent.com/candoo32-glitch/kart-live-swift-playgrounds/'+COMMIT+'/'+urllib.parse.quote(path)
  with urllib.request.urlopen(url,timeout=120) as response:data=response.read()
 if hashlib.sha256(data).hexdigest()!=checksum:raise SystemExit('Fixture hash mismatch: '+version)
 target.write_bytes(data);print('Verified capture',version,len(data),'bytes')
(out/'manifest.json').write_text(json.dumps({'swift_evidence_commit':COMMIT,'files':FILES},indent=2)+'\n')
