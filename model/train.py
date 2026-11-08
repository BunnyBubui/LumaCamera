"""Train a small orientation-conditioned bank of least-squares subpixel filters.

Python 3, numpy, Pillow. Downloads pinned, publicly available training images on
first run. Inference on the phone uses only the resulting 3,400-byte weight file.
"""
import hashlib
from pathlib import Path
from urllib.request import urlopen
import numpy as np
from numpy.lib.stride_tricks import sliding_window_view
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
DATA = HERE / 'data'
WEIGHTS = HERE.parent / 'app/src/main/assets/luma-sr1.bin'
URL = 'https://raw.githubusercontent.com/scikit-image/scikit-image/v0.24.0/skimage/data/'
TRAIN = ['astronaut.png', 'coffee.png', 'brick.png', 'grass.png', 'gravel.png']
HOLDOUT = ['camera.png', 'rocket.jpg']
SCALE = 4096
rng = np.random.default_rng(6118)


def read_image(name):
    DATA.mkdir(exist_ok=True)
    path = DATA / name
    if not path.exists():
        path.write_bytes(urlopen(URL + name, timeout=60).read())
    return Image.open(path).convert('L')


def geometry():
    image = Image.new('L', (320, 320), int(rng.integers(20, 235)))
    draw = ImageDraw.Draw(image)
    for _ in range(80):
        a, b = rng.integers(-10, 310, 2).tolist()
        c, d = rng.integers(4, 60, 2).tolist()
        coords = (a, b, a+c, b+d)
        shade = int(rng.integers(10, 245))
        kind = rng.integers(3)
        if kind == 0:
            draw.ellipse(coords, fill=shade)
        elif kind == 1:
            draw.line(coords, fill=shade, width=int(rng.integers(1, 6)))
        else:
            draw.rectangle(coords, fill=shade)
    return image


def features(low):
    patches = sliding_window_view(np.pad(low, 2, mode='edge'), (5, 5)).reshape(-1, 25).astype(np.float64)
    gx = patches[:, 13] - patches[:, 11]
    gy = patches[:, 17] - patches[:, 7]
    angle = np.floor(np.arctan2(gy, gx) * (8/np.pi) + 0.5).astype(int) % 8
    strength = np.abs(gx) + np.abs(gy)
    bucket = np.where(strength < 10, 0, 1 + angle + 8*(strength >= 48))
    return patches, bucket


def pair(image, method):
    width, height = image.size
    image = image.crop((0, 0, width // 2 * 2, height // 2 * 2))
    low = image.resize((image.width // 2, image.height // 2), method)
    return image, low


def fit():
    xx = np.zeros((17, 25, 25))
    xy = np.zeros((17, 25, 4))
    count = np.zeros(17)
    images = [read_image(x) for x in TRAIN] + [geometry() for _ in range(10)]
    for i, original in enumerate(images):
        for turn in range(4):
            image = original.rotate(turn*90, expand=True)
            if (i+turn) % 2:
                image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            method = [Image.Resampling.BICUBIC, Image.Resampling.LANCZOS, Image.Resampling.BOX][(i+turn) % 3]
            high, low = pair(image, method)
            x, bucket = features(np.asarray(low))
            target = np.asarray(high).reshape(low.height, 2, low.width, 2).transpose(0, 2, 1, 3).reshape(-1, 4)
            for group in range(17):
                mask = bucket == group
                a = x[mask]
                xx[group] += a.T @ a
                xy[group] += a.T @ target[mask]
                count[group] += len(a)
    weights = []
    for g in range(17):
        w = np.linalg.solve(xx[g] + np.eye(25)*count[g]*1.5, xy[g]).T
        q = np.rint(w*SCALE).astype('int32')
        q[:, 12] += SCALE - q.sum(axis=1)
        weights.append(q)
    weights = np.asarray(weights, dtype='<i2')
    WEIGHTS.write_bytes(weights.tobytes())
    return weights.astype('int32')


def score(high, low, weights):
    x, g = features(np.asarray(low))
    y = np.einsum('ij,ikj->ik', x, weights[g])/SCALE
    result = y.reshape(low.height, low.width, 2, 2).transpose(0, 2, 1, 3).reshape(high.height, high.width)
    ref = np.asarray(high).astype(float)
    bilinear = np.asarray(low.resize(high.size, Image.Resampling.BILINEAR)).astype(float)
    # Match the Android limit on corrections to an interpolated luminance.
    sr = np.clip(bilinear + np.clip(np.rint(result) - bilinear, -24, 24), 0, 255)
    base = np.asarray(low.resize(high.size, Image.Resampling.BICUBIC)).astype(float)
    def psnr(a):
        return -10*np.log10(np.mean(((ref[4:-4, 4:-4] - a[4:-4, 4:-4])/255)**2))
    return psnr(base), psnr(sr)


if __name__ == '__main__':
    weights = fit()
    print('SHA-256', hashlib.sha256(WEIGHTS.read_bytes()).hexdigest())
    for name in HOLDOUT:
        high, low = pair(read_image(name), Image.Resampling.BICUBIC)
        print(name, 'bicubic %.2f / learned %.2f dB' % score(high, low, weights))
