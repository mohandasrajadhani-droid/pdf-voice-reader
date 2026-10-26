// First Sarvam AI call: turn a Malayalam sentence into speech with Bulbul v3 and save it as a WAV file.
// Run: node --env-file=.env sarvam-tts.mjs
import { SarvamAIClient } from "sarvamai";
import fs from "fs";

if (!process.env.SARVAM_API_KEY) {
  console.error("SARVAM_API_KEY is missing. Put it in .env and run: node --env-file=.env sarvam-tts.mjs");
  process.exit(1);
}

const client = new SarvamAIClient({ apiSubscriptionKey: process.env.SARVAM_API_KEY });

const text = "നമസ്കാരം. ഇത് സർവം എഐയുടെ മലയാളം ശബ്ദമാണ്. കണ്ണൂർ, തളിപ്പറമ്പ്, ഇരിട്ടി.";

const response = await client.textToSpeech.convert({
  text,
  language_code: "ml-IN",
  model: "bulbul:v3",
  speaker: "shubh",
});

const audio = Buffer.from(response.audios.join(""), "base64");
fs.writeFileSync("sarvam-sample.wav", audio);
console.log(`Request ${response.request_id}: saved sarvam-sample.wav (${audio.length.toLocaleString()} bytes) for: ${text}`);
