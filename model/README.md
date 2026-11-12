# Luma SR1

Local single-image luminance super-resolution. SR1 uses a bank of 17 ridge-regression filters, four 2× subpixel phases per filter, and a 5×5 neighborhood. Filter selection uses local gradient orientation and strength. The app runs the quantized weights on the CPU and interpolates chroma. No remote service or download is needed.

The 3,400-byte little-endian Q12 weights are trained by this project's model/train.py. The coefficients sum to 4096 for every phase, preserving flat colors. The inference implementation is in LearnedFilter.java. The Android renderer decodes JPEG tiles with a halo, fits the learned 2× result to the 12 MP target, and conservatively blends with the camera JPEG when only an already-enlarged source is available. For very small crops the final resize can exceed the learned 2× factor. Extra output pixels are model estimates.

## Training and validation

Run `OPENBLAS_NUM_THREADS=1 python3 model/train.py` with NumPy and Pillow, then `sha256sum -c model/SHA256SUMS`. The app and CI use the checked-in weights. Training pairs use box, bicubic, and Lanczos downsampling, geometric augmentation, and generated shape images. The two validation photographs are held out of training. A unit test runs the actual Java kernel on the test fixtures and compares mean square error against Pillow bicubic at the same size.

Training data comes from the scikit-image v0.24.0 sample data. Astronaut is a public-domain NASA image. Coffee is CC0 by Rachel Michetti. Brick, grass and gravel textures are CC0. The camera photograph is CC0 by Lav Varshney, and the rocket photograph is public-domain SpaceX imagery. The distributions and credits are documented in the [upstream catalog](https://github.com/scikit-image/scikit-image/blob/v0.24.0/skimage/data/_fetchers.py). The generated filter coefficients may be reused with this app, including redistribution of the trained weights.

The full-image, grayscale 2× bicubic degradation test measured 29.89 → 30.64 dB on camera and 31.05 → 31.59 dB on rocket in the reference evaluation. It tests a controlled resize. Real-world ColorOS processing, Thai lettering, noise, and moving subjects require device evaluation.
