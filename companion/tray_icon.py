"""The tray icon: an ink-bordered square with a serif M and a green corner; a progress line while it works."""
from PIL import Image, ImageDraw, ImageFont


def _font(size):
    for name in ("georgiab.ttf", "Georgia Bold.ttf", "DejaVuSerif-Bold.ttf", "Times New Roman Bold.ttf", "timesbd.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            continue
    try:
        return ImageFont.load_default(size=size)
    except Exception:
        return ImageFont.load_default()


def image(progress=None, away=False, size=64):
    """progress: 0..1 while transcribing, else None. away: the tablet is out of reach (no green corner)."""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    s = size
    b = max(2, s // 16)
    d.rectangle([b, b, s - b - 1, s - b - 1], fill=(255, 255, 255, 255), outline=(0, 0, 0, 255), width=b + 1)
    f = _font(int(s * 0.62))
    box = d.textbbox((0, 0), "M", font=f)
    w, h = box[2] - box[0], box[3] - box[1]
    top = s * 0.47 - h / 2 - box[1] if progress is not None else s / 2 - h / 2 - box[1]
    d.text(((s - w) / 2 - box[0], top), "M", font=f, fill=(0, 0, 0, 255))
    if not away:
        c = int(s * 0.34)
        d.polygon([(s - c - b, b), (s - b, b), (s - b, b + c)], fill=(21, 128, 61, 255))
    if progress is not None:
        y = int(s * 0.80)
        d.rectangle([b * 3, y, s - b * 3 - 1, y + b * 2], fill=(212, 212, 216, 255))
        d.rectangle([b * 3, y, b * 3 + int((s - b * 6) * max(0.0, min(1.0, progress))), y + b * 2], fill=(0, 0, 0, 255))
    return im
