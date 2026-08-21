#!/usr/bin/env python3
"""Strict, stdlib-only preflight checks mirroring the BDS parser's safe subset."""
from __future__ import annotations
import argparse, struct, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]; LIMITS=(32*1024*1024,64*1024*1024,16*1024*1024,4096)
REQ={"minimal":{"Info.txt","demo.png","port/gen.ini","port/py_26.ini","port/num_26.ini","port/res/default.css"},"showcase":{"Info.txt","demo.png","port/gen.ini","land/gen.ini","port/py_26.ini","port/en_26.ini","port/num_26.ini","port/symbol.ini","land/py_26.ini","land/en_26.ini","land/num_26.ini","land/symbol.ini","res/logo/pop_menu_icons.png","res/logo/pop_menu_icons.til","res/logo/pop_input_icons.png","res/logo/pop_input_icons.til"}}
def dims(data):
    if data[:8]!=b'\x89PNG\r\n\x1a\n': raise ValueError('not PNG')
    return struct.unpack('>II',data[16:24])
def ini(data):
    out={}; sec='';
    for line in data.decode('utf-8-sig').splitlines():
        line=line.strip()
        if line.startswith('[') and line.endswith(']'): sec=line[1:-1];out.setdefault(sec,{})
        elif '=' in line and not line.startswith((';','#')): out.setdefault(sec,{})[line.split('=',1)[0].strip()]=line.split('=',1)[1].strip()
    return out
def check_til(z,name):
    til=ini(z.read(name)); pngname=name[:-4]+'.png'; w,h=dims(z.read(pngname)); tiles=[v for k,v in til.items() if k.upper().startswith('IMG')]
    assert int(til['GLOBAL']['TILE_NUM'])==len(tiles),f'{name}: TILE_NUM mismatch'
    for x in tiles:
        a=list(map(int,x['SOURCE_RECT'].split(',')));assert a[2]>0 and a[3]>0 and a[0]>=0 and a[1]>=0 and a[0]+a[2]<=w and a[1]+a[3]<=h,f'{name}: SOURCE_RECT out of bounds'
def verify(path,kind):
    assert path.read_bytes()[:4] in (b'PK\x03\x04',b'PK\x05\x06',b'PK\x07\x08'),'ZIP magic'
    assert path.stat().st_size<=LIMITS[0],'archive too large'
    with zipfile.ZipFile(path) as z:
        names=set(z.namelist());assert len(names)<=LIMITS[3],'too many files';assert REQ[kind]<=names,f'missing {REQ[kind]-names}'
        total=0
        for i in z.infolist():
            assert not i.filename.startswith('/') and '..' not in i.filename.split('/') and '\\' not in i.filename,'unsafe zip path'
            assert i.file_size<=LIMITS[2],'entry too large';total+=i.file_size
            if i.filename.endswith('.png'): dims(z.read(i.filename))
            if i.filename.endswith('.til'): check_til(z,i.filename)
        assert total<=LIMITS[1],'expanded archive too large'
        meta=ini(z.read('Info.txt'));assert meta.get('').get('Name'),'Info.txt name'
        for orientation in ('port','land'):
            if f'{orientation}/gen.ini' not in names: continue
            gen=ini(z.read(f'{orientation}/gen.ini')); w,h=map(int,gen['PANEL']['SIZE'].split(','))
            styles=ini(z.read(f'{orientation}/res/default.css'))
            for section, values in styles.items():
                for field in ('NM_IMG','HL_IMG'):
                    if field not in values: continue
                    atlas,tile=values[field].split(',',1); til=f'{orientation}/res/{atlas}.til'
                    if til not in names: til=f'res/{atlas}.til'
                    assert til in names and til[:-4]+'.png' in names,f'{orientation}: missing atlas {atlas}'
                    assert f'IMG{int(tile)}' in ini(z.read(til)),f'{orientation}: missing {atlas} tile {tile}'
            for layout in (n for n in names if n.startswith(orientation+'/') and n.endswith('.ini') and n.split('/')[-1] not in ('gen.ini','logo.ini')):
                doc=ini(z.read(layout));
                if 'PANEL' not in doc: continue
                touches=[]
                for sec,v in doc.items():
                    if not sec.upper().startswith('KEY') or 'VIEW_RECT' not in v: continue
                    r=list(map(int,v.get('TOUCH_RECT',v['VIEW_RECT']).split(',')));assert r[0]>=0 and r[1]>=0 and r[2]>0 and r[3]>0 and r[0]+r[2]<=w and r[1]+r[3]<=h,f'{layout}: touch out of canvas';touches.append(r)
                assert touches,f'{layout}: no keys'
                assert len({tuple(r) for r in touches})==len(touches),f'{layout}: duplicate touch rectangles'
                # Row bands may have normal key gaps, but must not leave a large
                # un-keyed section in a populated band. This catches accidental
                # coordinate omissions without rejecting intentionally different rows.
                for y in sorted({r[1] for r in touches}):
                    row=[r for r in touches if r[1]==y]
                    if len(row)>1:
                        left=min(r[0] for r in row); right=max(r[0]+r[2] for r in row)
                        covered=sum(r[2] for r in row)
                        assert covered >= (right-left)*0.85,f'{layout}: obvious row gap'
        print(f'OK {path.relative_to(ROOT)} ({len(names)} files, {total} bytes unpacked)')
def main():
    ap=argparse.ArgumentParser();ap.add_argument('archives',nargs='*');a=ap.parse_args();archives=[Path(x) for x in a.archives] or [ROOT/'build/bds-templates/bds-minimal-template.bds',ROOT/'build/bds-templates/bds-showcase-template.bds']
    for p in archives: verify(p,'showcase' if 'showcase' in p.name else 'minimal')
if __name__=='__main__':main()
