import math
from pathlib import Path
from PIL import Image, ImageDraw

# Output directory (res/drawable)
RES_DIR = Path(__file__).resolve().parent.parent / 'app' / 'src' / 'main' / 'res' / 'drawable'
SIZE = 512  # 512x512 px
CENTER = SIZE // 2
RADIUS_OUTER = int(SIZE * 0.48)  # leave small transparent margin
STROKE = max(2, SIZE // 128)  # ~4 px at 512
STROKE_COLOR = (128, 128, 128, 255)  # #808080
BLACK = (0, 0, 0, 255)
TRANSPARENT = (0, 0, 0, 0)

COLORS = {
    'ic_signal_green': (0, 255, 0, 255),
    'ic_signal_yellow': (255, 215, 0, 255),  # gold
    'ic_signal_red': (255, 0, 0, 255),
    'ic_signal_white': (255, 255, 255, 255),
    'ic_signal_gray': (106, 106, 106, 255),  # off fill
}


def draw_circle(draw: ImageDraw.ImageDraw, cx, cy, r, fill):
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill)


def draw_ring(draw: ImageDraw.ImageDraw, cx, cy, r, stroke, color):
    # Simulate stroke by drawing an outer circle then a smaller transparent hole
    draw_circle(draw, cx, cy, r, color)
    inner = r - stroke
    if inner > 0:
        draw_circle(draw, cx, cy, inner, TRANSPARENT)


def save_icon(name: str, painter):
    img = Image.new('RGBA', (SIZE, SIZE), TRANSPARENT)
    draw = ImageDraw.Draw(img)
    painter(draw)
    path = RES_DIR / f"{name}.png"
    img.save(path, 'PNG')
    print(f"Saved {path}")

# Simple colored signals with stroke
for key, fill in COLORS.items():
    def make_painter(fill_color=fill):
        return lambda d: (draw_circle(d, CENTER, CENTER, RADIUS_OUTER, fill_color),
                          draw_ring(d, CENTER, CENTER, RADIUS_OUTER, STROKE, STROKE_COLOR))
    save_icon(key, make_painter())

# Two-color yellow/red with horizontal divider and thin black line
# Keep same outer ring logic

def painter_yellow_red(d: ImageDraw.ImageDraw):
    # Full outer ring first (transparent center)
    draw_circle(d, CENTER, CENTER, RADIUS_OUTER, (255, 215, 0, 255))  # start with yellow
    # Cover bottom half with red
    d.pieslice((CENTER - RADIUS_OUTER, CENTER - RADIUS_OUTER, CENTER + RADIUS_OUTER, CENTER + RADIUS_OUTER),
               start=0, end=-180, fill=(255, 0, 0, 255))
    # Black separator line (rectangle for crispness)
    line_h = max(2, STROKE)
    d.rectangle((CENTER - RADIUS_OUTER, CENTER - line_h//2, CENTER + RADIUS_OUTER, CENTER + line_h//2), fill=BLACK)
    # Outer gray ring
    draw_ring(d, CENTER, CENTER, RADIUS_OUTER, STROKE, STROKE_COLOR)

save_icon('ic_signal_yellow_red', painter_yellow_red)

# Settings gear icon (stylized) similar to vector version

def painter_gear(d: ImageDraw.ImageDraw):
    # Gear parameters
    teeth = 8
    outer_r = RADIUS_OUTER
    inner_r = int(RADIUS_OUTER * 0.62)
    bore_r_outer = int(RADIUS_OUTER * 0.40)
    bore_r_inner = int(RADIUS_OUTER * 0.24)
    light = (151, 192, 230, 255)  # light half
    dark = (78, 120, 163, 255)    # dark base
    mid = (107, 145, 184, 255)

    # Base gear dark
    points = []
    for i in range(teeth * 2):
        angle = (math.pi * i) / (teeth * 2)
        r = outer_r if i % 2 == 0 else inner_r
        x = CENTER + int(math.cos(angle) * r)
        y = CENTER + int(math.sin(angle) * r)
        points.append((x, y))
    d.polygon(points, fill=dark)

    # Overlay left half lighter ring
    d.pieslice((CENTER - bore_r_outer, CENTER - bore_r_outer, CENTER + bore_r_outer, CENTER + bore_r_outer),
               start=90, end=270, fill=light)
    d.pieslice((CENTER - bore_r_outer, CENTER - bore_r_outer, CENTER + bore_r_outer, CENTER + bore_r_outer),
               start=-90, end=90, fill=mid)

    # Punch inner hole
    draw_circle(d, CENTER, CENTER, bore_r_inner, (30, 30, 30, 255))

save_icon('ic_settings_gear', painter_gear)

print("All PNG icons generated.")
