# Local vision models

Vision models are intentionally separate from `models/language`.

Place compatible `.gguf` or `.onnx` vision model files in this directory. They
will appear in the web application's **Vision model** selector. Selecting a
file does not send images to the reasoning model.

The runtime adapter and a recommended downloadable model will be documented
when the local vision engine is bundled. Until then, camera capture and manual
labels continue to work, and the UI reports vision inference as unavailable.
