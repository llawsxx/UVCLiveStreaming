import io
import struct

from PIL import Image, ImageDraw


def generate(width, height, frames, destination):
    colors = [(255, 255, 255), (255, 255, 0), (0, 255, 255), (0, 255, 0),
              (255, 0, 255), (255, 0, 0), (0, 0, 255), (0, 0, 0)]
    grays = [0, 32, 128, 235]
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    for index, color in enumerate(colors):
        draw.rectangle((index * width // 8, 0, (index + 1) * width // 8 - 1, height // 2 - 1), fill=color)
    for index, gray in enumerate(grays):
        draw.rectangle((index * width // 4, height // 2, (index + 1) * width // 4 - 1, height - 1),
                       fill=(gray, gray, gray))
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as output:
        output.write(b"MJPG" + struct.pack(">III", width, height, frames))
        for frame_id in range(frames):
            for top in (0, height - 16):
                draw.rectangle((0, top, 255, top + 15), fill=(0, 0, 0))
                for bit in range(16):
                    if frame_id & (1 << bit):
                        draw.rectangle((bit * 16, top, bit * 16 + 15, top + 15), fill=(255, 255, 255))
            encoded = io.BytesIO()
            image.save(encoded, format="JPEG", quality=95, subsampling=2)
            payload = encoded.getvalue()
            output.write(struct.pack(">I", len(payload)))
            output.write(payload)
