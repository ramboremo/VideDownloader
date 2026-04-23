from PIL import Image, ImageDraw

def draw_capsule(draw, pt1, pt2, width, color):
    draw.line([pt1, pt2], fill=color, width=width)
    r = width // 2
    draw.ellipse([pt1[0]-r, pt1[1]-r, pt1[0]+r, pt1[1]+r], fill=color)
    draw.ellipse([pt2[0]-r, pt2[1]-r, pt2[0]+r, pt2[1]+r], fill=color)

def create_icon():
    # Render at 4x resolution to achieve hyper-smooth anti-aliasing edges
    size = 2048
    img = Image.new('RGBA', (size, size), color='#FF8C00')
    draw = ImageDraw.Draw(img)

    lines = [
        # Stem
        ((1024, 350), (1024, 1150)),
        # Left wing
        ((624, 750), (1024, 1150)),
        # Right wing
        ((1424, 750), (1024, 1150)),
        # Bottom Bar
        ((500, 1550), (1548, 1550))
    ]

    # Draw black outline (Thick underlying capsules)
    for p1, p2 in lines:
        draw_capsule(draw, p1, p2, 300, '#000000')

    # Draw white fill (Thinner overlapping capsules)
    for p1, p2 in lines:
        draw_capsule(draw, p1, p2, 200, '#FFFFFF')

    # Scale down smoothly to final 512x512 Play Store size
    try:
        resample_filter = Image.Resampling.LANCZOS
    except AttributeError:
        resample_filter = Image.LANCZOS
        
    img = img.resize((512, 512), resample=resample_filter)
    img.save('play_store_icon.png')
    print("Anti-aliased smoothly rounded icon generated successfully at play_store_icon.png")

if __name__ == "__main__":
    create_icon()
