"""Package original AIHOT artwork and generate Android monochrome / previews."""
from pathlib import Path
import base64, io, shutil
import cairosvg
from PIL import Image, ImageDraw, ImageFont
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'design'
original = OUT / 'site-original/apple-icon.png'
encoded = base64.b64encode(original.read_bytes()).decode()
# Exact O-symbol paths from the site's inline wordmark.
MARK = ('M1013.851562 167.84375C1042.09375 223.269531 1020.054688 291.09375 964.632812 319.335938'
        'C909.207031 347.578125 841.378906 325.542969 813.136719 270.117188'
        'C784.894531 214.691406 806.929688 146.867188 862.355469 118.625'
        'C894.484375 102.253906 932.503906 102.253906 964.632812 118.625L947.675781 151.894531'
        'C910.628906 133.019531 865.292969 147.75 846.414062 184.796875'
        'C827.539062 221.84375 842.269531 267.183594 879.320312 286.058594'
        'C916.367188 304.933594 961.703125 290.203125 980.578125 253.15625'
        'C991.519531 231.683594 991.519531 206.269531 980.578125 184.792969Z '
        'M932.308594 179.636719L952.933594 200.261719L932.308594 220.890625L911.683594 200.261719Z')
def svg(mono=False):
    head = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="108" height="108" viewBox="0 0 108 108">'
    art = (f'<g transform="translate(-201.778 -7.315) scale(.28)"><path fill="white" d="{MARK}"/></g>' if mono else f'<image width="108" height="108" xlink:href="data:image/png;base64,{encoded}"/>')
    return head + art + '</svg>'
for filename, mono in [('ai-sulan-logo.svg',False),('ai-sulan-mark.svg',True)]:
    (OUT/filename).write_text(svg(mono),encoding='utf-8')
    cairosvg.svg2png(bytestring=svg(mono).encode(),write_to=str((OUT/filename).with_suffix('.png')),output_width=1024,output_height=1024)
drawable=ROOT/'app/src/main/res/drawable'
nodpi=ROOT/'app/src/main/res/drawable-nodpi'
nodpi.mkdir(exist_ok=True)
shutil.copyfile(original,nodpi/'aihot_site_icon.png')
(drawable/'ic_launcher_foreground.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/aihot_site_icon" android:gravity="fill" android:filter="true" />
''',encoding='utf-8')
(drawable/'ic_launcher_monochrome.xml').write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="-201.778" android:translateY="-7.315" android:scaleX="0.28" android:scaleY="0.28">
        <path android:fillColor="#FFFFFF" android:pathData="{MARK}" />
    </group>
</vector>
''',encoding='utf-8')
sheet=Image.new('RGB',(1200,660),'#F3F6F5')
d=ImageDraw.Draw(sheet)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',28)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
d.text((52,35),'AI 速览 · 沿用 AIHOT 网站标志',fill='#173C38',font=font)
for i,label in enumerate(['圆形','圆角方形','系统单色']):
    x=75+i*380
    im=Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg().encode(),output_width=280,output_height=280))).convert('RGBA')
    if i==2:
        im=Image.new('RGBA',(280,280),'#D1FAE5')
        mark=Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg(True).encode(),output_width=280,output_height=280)))
        im.paste(Image.new('RGBA',(280,280),'#134E4A'),(0,0),mark.getchannel('A'))
    mask=Image.new('L',(280,280),0)
    md=ImageDraw.Draw(mask)
    if i==0: md.ellipse((0,0,279,279),fill=255)
    else: md.rounded_rectangle((0,0,279,279),radius=65,fill=255)
    sheet.paste(im,(x,130),mask)
    d.text((x+86,437),label,fill='#173C38',font=font)
    for j,size in enumerate([48,64]):
        sheet.paste(im.resize((size,size),Image.Resampling.LANCZOS),(x+60+j*95,505),mask.resize((size,size),Image.Resampling.LANCZOS))
    d.text((x+70,590),'48 px       64 px',fill='#526862',font=small)
sheet.save(OUT/'logo-preview.png')
print('AIHOT original icon packaged; monochrome and previews generated.')
