#!/usr/bin/env python3
"""Generate parity expectations using the actual Swift 122 recorder, not a Python filter."""
import argparse,csv,hashlib,json,os,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];SOURCE='1126441d303150371c80a2b4e7afebde4c37b738'
p=argparse.ArgumentParser();p.add_argument('--swift-repo',type=Path,required=True);args=p.parse_args()
files=['Sources/IMUAttitudeMath.swift','Sources/InstrumentAttitude.swift','Sources/InstrumentCalibration.swift','Sources/KartTelemetry.swift','Sources/IMUAccelerationQuality.swift','Sources/KartCSVRecorder.swift','Tests/LiveInstrumentRunner.swift']
for file in files:
 expected=subprocess.check_output(['git','show',SOURCE+':'+file],cwd=args.swift_repo)
 if (args.swift_repo/file).read_bytes()!=expected:raise SystemExit('Unexpected Swift source: '+file)
exe=ROOT/'app/build/swift-fixtures/swift-oracle'
subprocess.run([os.environ.get('SWIFTC','swiftc'),'-swift-version','5','-whole-module-optimization','-Onone',*[str(args.swift_repo/f) for f in files],'-o',str(exe)],check=True)
out=ROOT/'app/src/test/resources/swift122';out.mkdir(parents=True,exist_ok=True)
manifest={'swift_source_commit':SOURCE,'request_elapsed_seconds':3,'expectations':{},'source_files':{f:hashlib.sha256((args.swift_repo/f).read_bytes()).hexdigest() for f in files}}
for version in ['117','118','122']:
 input_path=ROOT/('app/build/swift-fixtures/'+version+'.csv');rs=list(csv.DictReader(input_path.open()))
 data=''.join(json.dumps({'time':float(r['elapsed_seconds']),'hex':r['raw_packet_hex']})+'\n' for r in rs)
 run=subprocess.run([str(exe)],input=data,text=True,stdout=subprocess.PIPE,check=True)
 records=[]
 for line in run.stdout.splitlines():
  record=json.loads(line)
  records.append({k:record[k] for k in ['elapsed','packet','phase','NINTENDO','GYROSCOPE','FUSION'] if k in record}|({'linear_g':record['linear_g']} if 'linear_g' in record else {}))
 output=out/(version+'.json');output.write_text(json.dumps(records,separators=(',',':'))+'\n')
 manifest['expectations'][version]={'input_sha256':hashlib.sha256(input_path.read_bytes()).hexdigest(),'packets':len(rs),'subsamples':sum(int(r['sample_count']) for r in rs),'comparisons':len(records),'golden_sha256':hashlib.sha256(output.read_bytes()).hexdigest()}
 print('Swift production oracle:',version,len(records),'selected target snapshots')
(out/'MANIFEST.json').write_text(json.dumps(manifest,indent=2)+'\n')
