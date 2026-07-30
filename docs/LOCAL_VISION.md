# Local vision, OCR, and inventory scanning

The application uses **Qwen2.5-VL 3B Instruct Q4_K_M** as a separate local
vision model. It does not send camera images to the reasoning model or a cloud
service. The bundled `llama.cpp` multimodal server loads the vision model only
when an image is scanned.

## Install on Windows

From the portable application directory:

```cmd
setup-vision.cmd
```

The script does not download files again when they already exist. It verifies
both published SHA-256 checksums. The download is approximately 2.77 GB; allow
roughly 5 GB of free disk space for installation and packaging. 16 GB RAM is
recommended.

Files installed under `models\vision`:

- `Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf` (1,929,901,056 bytes)
- `mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf` (844,757,728 bytes)

Restart the app after installing. In **Add an item**, either start the camera
and capture a photo or choose **Choose image** to upload an existing JPEG, PNG,
or WebP file. Uploads are resized when necessary and converted to JPEG inside
the browser before being sent to this PC; no cloud upload is used. Choose
**Scan items & read labels**. The result lists all detected items. Untracked
items have **Add this item**; matched inventory records have **Update existing**.
The update action adds the detected visible count to the saved quantity and
merges label details into the form. Review the values and press **Save item**—
vision results are never saved automatically.

After capturing or uploading, use **Take another photo** or **Choose image** to
replace it before or after a scan. Scan progress and errors remain visible below
the photo controls. The first scan may take several minutes while the local
vision model loads; subsequent scans are faster.

For best OCR, fill the frame with the products, use bright indirect light, keep
labels facing the camera, and avoid blur. Model results can be wrong, especially
for expiry dates and partially hidden quantities, so confirm before saving.

## Official sources

- Original model and capability card:
  https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct
- llama.cpp-supported GGUF repository and downloads:
  https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF
- llama.cpp multimodal documentation:
  https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md

The selected official model card describes common-object recognition, text,
chart and layout analysis, structured extraction, and visual localization.
Review the model repository's current license before organizational use.
