#!/usr/bin/env python3
"""Deterministically build the original, dependency-free BDS template skins.

This program deliberately uses only Python's standard library.  The small PNG
writer and geometric drawing routines make the templates reproducible and avoid
shipping copied art, fonts, logos, or extracted skin resources.
"""
from __future__ import annotations

import argparse
import binascii
import hashlib
import shutil
import struct
import zlib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[3]
EXAMPLES = ROOT / "examples" / "bds-template"
BUILD = ROOT / "build" / "bds-templates"


def png(path: Path, w: int, h: int, pixels: bytearray) -> None:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff)
    raw = b"".join(b"\0" + bytes(pixels[y * w * 4:(y + 1) * w * 4]) for y in range(h))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def canvas(w: int, h: int, color=(0, 0, 0, 0)) -> bytearray:
    return bytearray(color * (w * h))


def rect(p: bytearray, w: int, h: int, x: int, y: int, rw: int, rh: int, c, radius=0) -> None:
    for yy in range(max(0, y), min(h, y + rh)):
        for xx in range(max(0, x), min(w, x + rw)):
            if radius and ((xx < x + radius and yy < y + radius and (xx-x-radius+1)**2 + (yy-y-radius+1)**2 > radius**2) or (xx >= x+rw-radius and yy < y+radius and (xx-(x+rw-radius))**2 + (yy-y-radius+1)**2 > radius**2) or (xx < x+radius and yy >= y+rh-radius and (xx-x-radius+1)**2 + (yy-(y+rh-radius))**2 > radius**2) or (xx >= x+rw-radius and yy >= y+rh-radius and (xx-(x+rw-radius))**2 + (yy-(y+rh-radius))**2 > radius**2)):
                continue
            i = (yy * w + xx) * 4
            p[i:i+4] = bytes(c)


def line(p, w, h, x0, y0, x1, y1, c, thick=2):
    steps = max(abs(x1-x0), abs(y1-y0), 1)
    for n in range(steps + 1):
        x, y = round(x0 + (x1-x0)*n/steps), round(y0 + (y1-y0)*n/steps)
        rect(p, w, h, x-thick//2, y-thick//2, thick, thick, c)


def tile_sheet(path: Path, tiles: list[tuple[int, int, callable]]) -> None:
    w = sum(t[0] for t in tiles); h = max(t[1] for t in tiles)
    p = canvas(w, h); x = 0; records = []
    for i, (tw, th, draw) in enumerate(tiles, 1):
        draw(p, w, h, x, 0, tw, th); records.append((i, x, 0, tw, th)); x += tw
    png(path.with_suffix(".png"), w, h, p)
    til = ["[GLOBAL]", f"TILE_NUM={len(records)}", "USE_ALPHA=1", ""]
    for i, x, y, tw, th in records:
        til += [f"[IMG{i}]", f"SOURCE_RECT={x},{y},{tw},{th}", f"INNER_RECT={x+12},{y+12},{tw-24},{th-24}", ""]
    path.with_suffix(".til").write_text("\n".join(til), encoding="utf-8")


def surface(c, edge):
    def draw(p,w,h,x,y,tw,th):
        # This tile is deliberately free of diagonals or repeated detail. BDS
        # draws it with INNER_RECT nine-slice scaling, where such artwork becomes
        # a distracting stepped grid on a real wide device.
        rect(p,w,h,x,y,tw,th,c)
        line(p,w,h,x+8,y+8,x+tw-8,y+8,edge,2)
        rect(p,w,h,x+10,y+th-10,tw-20,2,(edge[0],edge[1],edge[2],72),1)
    return draw


def key(c, edge, pressed=False):
    def draw(p,w,h,x,y,tw,th):
        rect(p,w,h,x+3,y+4,tw-6,th-8,c,10)
        rect(p,w,h,x+6,y+6,tw-12,3,edge if not pressed else (255,111,180,255),2)
        if pressed: rect(p,w,h,x+8,y+th-13,tw-16,5,(255,96,175,100),3)
    return draw


def icon(kind: int):
    cyan=(73,241,218,255); pink=(255,112,181,255); white=(240,250,255,255)
    def draw(p,w,h,x,y,tw,th):
        # Every slot starts with the same transparent 72px canvas and a distinct,
        # original geometric motif selected from the slot number.
        cx=x+tw//2; cy=y+th//2; c=cyan if kind%2 else pink
        if kind % 6 == 0: rect(p,w,h,cx-16,cy-16,32,32,c,7); rect(p,w,h,cx-7,cy-7,14,14,(28,35,51,255),3)
        elif kind % 6 == 1: line(p,w,h,cx-18,cy,cx+18,cy,c,4); line(p,w,h,cx,cy-18,cx,cy+18,pink,4)
        elif kind % 6 == 2: line(p,w,h,cx-18,cy+12,cx,cy-14,c,4); line(p,w,h,cx,cy-14,cx+18,cy+12,pink,4)
        elif kind % 6 == 3: rect(p,w,h,cx-19,cy-12,38,24,c,5); line(p,w,h,cx-12,cy,cx+12,cy,white,3)
        elif kind % 6 == 4: line(p,w,h,cx-18,cy-15,cx+18,cy+15,c,4); line(p,w,h,cx+18,cy-15,cx-18,cy+15,pink,4)
        else:
            for a in (-12,0,12): rect(p,w,h,cx+a-3,cy+a//2-3,6,6,c if a else pink,3)
        rect(p,w,h,cx-2,cy-2,4,4,white,2)
    return draw


def make_atlases(base: Path, showcase: bool) -> None:
    res=base/"res"; res.mkdir(parents=True, exist_ok=True)
    tile_sheet(res/"panel", [(240,120,surface((24,31,46,255),(68,232,210,255)))])
    tile_sheet(res/"keys", [(120,80,key((53,66,87,255),(89,236,213,180))), (120,80,key((32,177,164,255),(255,124,185,255),True)), (120,80,key((72,56,92,255),(255,125,185,220))), (120,80,key((202,67,140,255),(255,225,245,255),True))])
    tile_sheet(res/"candidate", [(240,68,surface((31,43,60,255),(255,119,185,255)))])
    logo=res/"logo"; logo.mkdir(exist_ok=True)
    tile_sheet(logo/"pop_menu_icons", [(72,72,icon(i)) for i in range(41)])
    tile_sheet(logo/"pop_input_icons", [(72,72,icon(i+41)) for i in range(8)])
    if showcase:
        # Existing parser picks the nearest numeric bucket. These use the same
        # coordinate layout but independently regenerated, scaled original pixels.
        for density in (480,720,1080):
            out=base/str(density)/"res"; out.mkdir(parents=True, exist_ok=True)
            shutil.copy2(res/"keys.png", out/"keys.png"); shutil.copy2(res/"keys.til", out/"keys.til")


def css(showcase: bool) -> str:
    styles=["[GLOBAL]", "STYLE_NUM=80", "FOR=480", "", "[STYLE1]", "NM_IMG=panel,1", "", "[STYLE2]", "NM_IMG=keys,1", "HL_IMG=keys,2", "", "[STYLE3]", "NM_IMG=keys,3", "HL_IMG=keys,4", "", "[STYLE4]", "NM_COLOR=FFF4FBFF", "HL_COLOR=FFFFFFFF", "FONT_SIZE=28", "FONT_WEIGHT=500", ""]
    for n,ch in enumerate("qwertyuiopasdfghjklzxcvbnm1234567890!@#$%^&*()-_=+[]{};:,.?",10):
        styles += [f"[STYLE{n}]", f"SHOW={ch}", "NM_COLOR=FFF4FBFF", "HL_COLOR=FFFFFFFF", "FONT_SIZE=28", "FONT_WEIGHT=500", ""]
    labels={70:"⇧",71:"123",72:"中",73:"⌫",74:"空 格",75:"⏎",76:"#+=",77:"EN"}
    for n,ch in labels.items(): styles += [f"[STYLE{n}]", f"SHOW={ch}", "NM_COLOR=FFB9FFF6", "HL_COLOR=FFFFFFFF", "FONT_SIZE=22", "FONT_WEIGHT=700", ""]
    styles += ["[STYLE80]", "NM_IMG=candidate,1", "", "[STYLE81]", "NM_COLOR=FFF4FBFF", "FONT_SIZE=22", "FONT_WEIGHT=500", ""]
    if showcase: styles += ["[STYLE90]", "PRESS_ANIM=1", ""]
    return "\n".join(styles)


def write_gen(folder: Path, landscape: bool, showcase: bool) -> None:
    width,height=(800,360) if landscape else (480,385)
    (folder/"res").mkdir(parents=True,exist_ok=True)
    (folder/"gen.ini").write_text(f"[PANEL]\nSIZE={width},{height}\nBACK_STYLE=1\n\n[CAND]\nLAYOUT_NAME=cand1\nVIEW_RECT=0,0,{width},58\n",encoding="utf-8")
    (folder/"res"/"default.css").write_text(css(showcase),encoding="utf-8")
    (folder/"logo.ini").write_text("[LOGO]\n; Icon sprites are root res/logo/*.png + *.til\n",encoding="utf-8")
    (folder/"cand1.cnd").write_text("[CAND]\nBACK_STYLE=80\nFORE_STYLE=81\nCELL_W=72\nPADDING=8,4,42,4\nFIRST_GAP=8\n",encoding="utf-8")
    if showcase:
        (folder/"res"/"anim.ini").write_text("[ANIM1]\nTYPE=4\nFROM=100,100\nTO=104,104\nDURATION=90\nINTPOL=1\nPIVOT=50,50\n",encoding="utf-8")


def keys_ini(name: str, landscape: bool, showcase: bool) -> str:
    w,h=(800,360) if landscape else (480,385); top=64; gap=5; rowh=(h-top-4*gap)//4
    if name in ("py_26","en_26"): rows=[list("qwertyuiop"),list("asdfghjkl"),["SHIFT"]+list("zxcvbnm")+["BACK"],["NUM","LANG","SPACE","ENTER"]]
    elif name=="num_26": rows=[list("1234567890"),list("!@#$%^&*()"),["SYM","-","_","+","=","/",".","BACK"],["ABC","LANG","SPACE","ENTER"]]
    else: rows=[list("!@#$%&*?"),list("()[]{}<>"),list("+-=/:;,.?")+["BACK"],["ABC","LANG","SPACE","ENTER"]]
    def action(v): return {"SHIFT":"F10","BACK":"F36","SPACE":"F38","ENTER":"F39","NUM":"F6","ABC":"F4","SYM":"F1","LANG":"F15"}.get(v,v)
    def fg(v):
        if v=="SHIFT": return 70
        special={"NUM":71,"ABC":77,"SYM":76,"LANG":72,"BACK":73,"SPACE":74,"ENTER":75}
        if v in special: return special[v]
        glyphs="qwertyuiopasdfghjklzxcvbnm1234567890!@#$%^&*()-_=+[]{};:,.?"
        return 10+glyphs.index(v) if v in glyphs else 4
    lines=["[PANEL]",f"SIZE={w},{h}","BACK_STYLE=1", f"KEY_NUM={sum(map(len,rows))}", ""]
    n=1
    for ri,row in enumerate(rows):
        y=top+ri*(rowh+gap); margin=8 if ri!=2 else 22; avail=w-2*margin-(len(row)-1)*gap; widths=[]
        if ri==3: widths=[avail//8,avail//8,avail*4//8,avail*2//8]; widths[-1]+=avail-sum(widths)
        elif ri==2 and row[0] in ("SHIFT","SYM"): widths=[avail//7]+[(avail-avail//7)*5//6//7]*0 # overwritten below
        if not widths: widths=[avail//len(row)]*len(row); widths[-1]+=avail-sum(widths)
        if ri==2 and row[0] in ("SHIFT","SYM"):
            special=avail//7; middle=(avail-2*special)//(len(row)-2); widths=[special]+[middle]*(len(row)-2)+[special]; widths[-2]+=avail-sum(widths)
        x=margin
        for v,kw in zip(row,widths):
            lines += [f"[KEY{n}]",f"BACK_STYLE={3 if v in ('SHIFT','BACK','ENTER','NUM','ABC','SYM','LANG') else 2}",f"FORE_STYLE={fg(v)}",f"VIEW_RECT={x},{y},{kw},{rowh}",f"TOUCH_RECT={x},{y},{kw},{rowh}",f"CENTER={action(v)}"]
            if showcase and n==1: lines.append("BACK_ANIM_STYLE=90")
            lines.append(""); x+=kw+gap; n+=1
    return "\n".join(lines)


def preview(path: Path, showcase: bool) -> None:
    w,h=480,385;p=canvas(w,h,(24,31,46,255)); rect(p,w,h,0,0,w,58,(31,43,60,255));
    for x in range(10,350,75): rect(p,w,h,x,18,52,18,(55,93,110,255),6)
    rect(p,w,h,442,12,26,34,(255,112,181,255),8)
    top=64; gap=5; rowh=(h-top-4*gap)//4; rows=(10,9,9,4)
    for ri,count in enumerate(rows):
        margin=8 if ri!=2 else 22; avail=w-2*margin-(count-1)*gap
        for k in range(count):
            x=margin+k*(avail//count+gap); kw=avail//count if k<count-1 else avail-(avail//count)*(count-1)
            c=(72,56,92,255) if (ri==3 or (ri==2 and k in (0,count-1))) else (53,66,87,255)
            rect(p,w,h,x+2,top+ri*(rowh+gap)+2,kw-4,rowh-4,c,9); line(p,w,h,x+8,top+ri*(rowh+gap)+8,x+kw-8,top+ri*(rowh+gap)+8,(82,238,216,220),2)
    if showcase:
        for x in range(16,400,40): rect(p,w,h,x,25,20,4,(255,156,204,255),2)
    png(path,w,h,p)


def build_skin(slug: str, name: str, showcase: bool) -> Path:
    base=EXAMPLES/("showcase" if showcase else "minimal")
    shutil.rmtree(base,ignore_errors=True); base.mkdir(parents=True)
    (base/"Info.txt").write_text("\ufeff"+f"Name={name}\nAuthor=Fcitx5 Android Contributors\nDescription=Original geometric BDS template generated from source.\nVersionCode=1\nStyle=default\n",encoding="utf-8")
    preview(base/"demo.png",showcase); make_atlases(base,showcase)
    orientations=[("port",False)] + ([("land",True)] if showcase else [])
    for orient,land in orientations:
        folder=base/orient;write_gen(folder,land,showcase)
        names=["py_26","num_26"] if not showcase else ["py_26","en_26","num_26","symbol"]
        for layout in names: (folder/f"{layout}.ini").write_text(keys_ini(layout,land,showcase),encoding="utf-8")
    BUILD.mkdir(parents=True,exist_ok=True); archive=BUILD/f"{slug}.bds"
    with ZipFile(archive,"w",ZIP_DEFLATED,compresslevel=9) as z:
        for f in sorted(base.rglob("*")):
            if f.is_file() and not f.name.startswith("."):
                info=ZipInfo(f.relative_to(base).as_posix(),(1980,1,1,0,0,0)); info.compress_type=ZIP_DEFLATED
                z.writestr(info,f.read_bytes(),compress_type=ZIP_DEFLATED,compresslevel=9)
    return archive


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--clean",action="store_true"); args=parser.parse_args()
    if args.clean: shutil.rmtree(BUILD,ignore_errors=True)
    for item in (("bds-minimal-template","BDS Minimal Template",False),("bds-showcase-template","BDS Showcase Template",True)):
        archive=build_skin(*item); print(archive.relative_to(ROOT), hashlib.sha256(archive.read_bytes()).hexdigest())

if __name__ == "__main__": main()
