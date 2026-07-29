(() => {
  let stream, context, source, processor;
  let chunks = [];
  let inputRate = 48000;
  let speechQueue = Promise.resolve();
  let streamRaw = "", streamIndex = 0, streamSpeakable = "", insideCode = false;
  const record = document.querySelector("#voice-record");
  const stop = document.querySelector("#voice-stop");
  const status = document.querySelector("#voice-status");
  const field = document.querySelector('#assistant-form [name="message"]');

  function setStatus(message) { status.textContent = message; }

  async function checkCapabilities() {
    try {
      const response = await fetch("/api/assistant/status");
      const capabilities = await response.json();
      record.disabled = !capabilities.voiceUploadAvailable;
      document.querySelector("#speak-replies").disabled = !capabilities.speechOutputAvailable;
      if (!capabilities.voiceUploadAvailable) setStatus("Bundled local Whisper is unavailable.");
    } catch {
      record.disabled = true;
    }
  }

  async function startRecording() {
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
        video: false
      });
      context = new AudioContext();
      inputRate = context.sampleRate;
      source = context.createMediaStreamSource(stream);
      processor = context.createScriptProcessor(4096, 1, 1);
      chunks = [];
      processor.onaudioprocess = event => {
        chunks.push(new Float32Array(event.inputBuffer.getChannelData(0)));
      };
      source.connect(processor);
      processor.connect(context.destination);
      record.hidden = true;
      stop.hidden = false;
      setStatus("Listening… select Stop when finished.");
    } catch (error) {
      setStatus(`Microphone unavailable: ${error.message}`);
    }
  }

  async function stopRecording() {
    stop.hidden = true;
    record.hidden = false;
    processor?.disconnect();
    source?.disconnect();
    stream?.getTracks().forEach(track => track.stop());
    await context?.close();
    if (!chunks.length) {
      setStatus("No audio was captured.");
      return;
    }
    setStatus("Transcribing locally with Whisper…");
    try {
      const wav = encodeWav(downsample(flatten(chunks), inputRate, 16000), 16000);
      const form = new FormData();
      form.append("audio", wav, "voice-input.wav");
      const response = await fetch("/api/assistant/transcribe", { method: "POST", body: form });
      if (!response.ok) throw new Error((await response.json()).error || "Transcription failed");
      const result = await response.json();
      field.value = result.text;
      setStatus("Transcription ready. Sending to the assistant…");
      document.querySelector("#assistant-form").requestSubmit();
    } catch (error) {
      setStatus(`Transcription failed: ${error.message}`);
    }
  }

  function flatten(parts) {
    const result = new Float32Array(parts.reduce((sum, part) => sum + part.length, 0));
    let offset = 0;
    for (const part of parts) { result.set(part, offset); offset += part.length; }
    return result;
  }

  function downsample(input, sourceRate, targetRate) {
    if (sourceRate === targetRate) return input;
    const ratio = sourceRate / targetRate;
    const output = new Float32Array(Math.floor(input.length / ratio));
    for (let i = 0; i < output.length; i++) {
      const start = Math.floor(i * ratio), end = Math.min(input.length, Math.floor((i + 1) * ratio));
      let sum = 0;
      for (let j = start; j < end; j++) sum += input[j];
      output[i] = sum / Math.max(1, end - start);
    }
    return output;
  }

  function encodeWav(samples, rate) {
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);
    const text = (offset, value) => [...value].forEach((char, i) => view.setUint8(offset + i, char.charCodeAt(0)));
    text(0, "RIFF"); view.setUint32(4, 36 + samples.length * 2, true); text(8, "WAVE");
    text(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
    view.setUint16(22, 1, true); view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true);
    view.setUint16(32, 2, true); view.setUint16(34, 16, true); text(36, "data");
    view.setUint32(40, samples.length * 2, true);
    for (let i = 0; i < samples.length; i++) {
      const value = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(44 + i * 2, value < 0 ? value * 32768 : value * 32767, true);
    }
    return new Blob([buffer], { type: "audio/wav" });
  }

  window.playAssistantSpeech = async text => {
    if (!document.querySelector("#speak-replies").checked) return;
    setStatus("Generating speech locally with Piper…");
    try {
      const response = await fetch("/api/assistant/speech", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text })
      });
      if (!response.ok) throw new Error((await response.json()).error || "Speech generation failed");
      const url = URL.createObjectURL(await response.blob());
      const audio = new Audio(url);
      audio.onended = () => { URL.revokeObjectURL(url); setStatus("Ready."); };
      await audio.play();
    } catch (error) {
      setStatus(`Spoken reply failed: ${error.message}`);
    }
  };

  function enqueueSpeech(text) {
    if (!document.querySelector("#speak-replies").checked || !text.trim()) return;
    speechQueue = speechQueue.then(async () => {
      setStatus("Speaking streamed response locally…");
      const response = await fetch("/api/assistant/speech", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text })
      });
      if (!response.ok) throw new Error((await response.json()).error || "Speech generation failed");
      const url = URL.createObjectURL(await response.blob());
      await new Promise((resolve, reject) => {
        const audio = new Audio(url);
        audio.onended = resolve;
        audio.onerror = reject;
        audio.play().catch(reject);
      });
      URL.revokeObjectURL(url);
    }).catch(error => setStatus(`Spoken reply failed: ${error.message}`));
  }

  function releaseSentences(force = false) {
    while (streamSpeakable.length) {
      const match = streamSpeakable.match(/^([\s\S]*?[.!?])(?:\s+|$)/);
      if (!match && !force && streamSpeakable.length < 320) return;
      const length = match ? match[0].length : Math.min(streamSpeakable.length, 320);
      enqueueSpeech(streamSpeakable.slice(0, length).trim());
      streamSpeakable = streamSpeakable.slice(length);
      if (!force && !match) return;
    }
  }

  window.beginStreamingSpeech = () => {
    streamRaw = ""; streamIndex = 0; streamSpeakable = ""; insideCode = false;
  };
  window.pushStreamingSpeech = token => {
    streamRaw += token;
    while (streamIndex < streamRaw.length) {
      if (streamRaw.length - streamIndex < 3 && streamRaw[streamIndex] === "`") break;
      if (streamRaw.startsWith("```", streamIndex) || streamRaw.startsWith("~~~", streamIndex)) {
        insideCode = !insideCode;
        streamIndex += 3;
        continue;
      }
      if (!insideCode) streamSpeakable += streamRaw[streamIndex];
      streamIndex++;
    }
    releaseSentences(false);
  };
  window.finishStreamingSpeech = () => {
    if (!insideCode && streamIndex < streamRaw.length) streamSpeakable += streamRaw.slice(streamIndex);
    streamIndex = streamRaw.length;
    releaseSentences(true);
  };

  record.addEventListener("click", startRecording);
  stop.addEventListener("click", stopRecording);
  checkCapabilities();
})();
