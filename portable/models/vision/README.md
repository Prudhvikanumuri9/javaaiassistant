# Local vision models

Vision models are intentionally separate from `models/language`.

Run `setup-vision.cmd` from the portable application's root to install and
verify the recommended Qwen2.5-VL model plus its `mmproj` vision projector.
See `docs/LOCAL_VISION.md` in the source repository for download sources,
checksums, requirements, and usage.

Main model files appear in the web application's **Vision model** selector.
Files beginning with `mmproj-` are paired automatically and are not selectable.
Camera images stay local and are never sent to the separate reasoning model.
