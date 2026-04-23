from PIL import Image
import os
import shutil

def update_app_icon():
    icon_path = r'C:\Users\bhara\Downloads\VideDownloader\new_icon.png'
    res_dir = r'C:\Users\bhara\Downloads\VideDownloader\app\src\main\res'

    if not os.path.exists(icon_path):
        print(f"Error: {icon_path} not found.")
        return

    sizes = {
        'mdpi': 48,
        'hdpi': 72,
        'xhdpi': 96,
        'xxhdpi': 144,
        'xxxhdpi': 192
    }

    try:
        img = Image.open(icon_path).convert("RGBA")
    except Exception as e:
        print(f"Failed to open image: {e}")
        return

    # Generate all Android icon sizes
    for density, size in sizes.items():
        folder = os.path.join(res_dir, f'mipmap-{density}')
        os.makedirs(folder, exist_ok=True)
        
        try:
            resample_filter = Image.Resampling.LANCZOS
        except AttributeError:
            resample_filter = Image.LANCZOS
            
        resized_img = img.resize((size, size), resample=resample_filter)
        
        # Save standard and round legacy icons
        resized_img.save(os.path.join(folder, 'ic_launcher.png'))
        resized_img.save(os.path.join(folder, 'ic_launcher_round.png'))

    # Adaptive icons (v26) use XML that points to foregrounds/backgrounds. 
    # To strictly use the provided PNG as the absolute icon without weird Android masking,
    # we delete the v26 wrapper so it falls back gracefully to the raw HD PNGs we just generated.
    v26_dir = os.path.join(res_dir, 'mipmap-anydpi-v26')
    if os.path.exists(v26_dir):
        shutil.rmtree(v26_dir)
        print("Removed conflicting mipmap-anydpi-v26 wrapper.")

    print("Successfully replaced app icons with new_icon.png in all mipmap dimensions.")

if __name__ == "__main__":
    update_app_icon()
