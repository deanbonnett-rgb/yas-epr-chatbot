import express from "express";
import bodyParser from "body-parser";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import OpenAI from "openai";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
app.use(bodyParser.json());

// Serve the frontend
app.use(express.static(path.join(__dirname, "public")));

const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

// Load KB and (optionally) vectors
const kb = JSON.parse(fs.readFileSync("yas_epr_kb_full.json", "utf8"));
let kbVectors = [];
try {
  kbVectors = JSON.parse(fs.readFileSync("yas_epr_kb_vectors.json", "utf8"));
  console.log("Loaded KB vectors:", kbVectors.length);
} catch (e) {
  console.warn("No yas_epr_kb_vectors.json found – falling back to simple search.");
}

// Simple cosine similarity
function cosineSim(a, b) {
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    na += a[i] * a[i];
    nb += b[i] * b[i];
  }
  return dot / (Math.sqrt(na) * Math.sqrt(nb) || 1);
}

// Embedding-based search
async function searchKBEmbeddings(query, topK = 5) {
  if (!kbVectors.length) return [];

  const embResp = await client.embeddings.create({
    model: "text-embedding-3-large",
    input: query
  });

  const qEmb = embResp.data[0].embedding;

  const scored = kbVectors.map(rec => ({
    id: rec.id,
    score: cosineSim(qEmb, rec.embedding)
  }));

  scored.sort((a, b) => b.score - a.score);
  const top = scored.slice(0, topK);

  const byId = Object.fromEntries(kb.map(x => [x.id, x]));
  return top
    .filter(x => byId[x.id])
    .map(x => byId[x.id]);
}

// Fallback simple search if no vectors
function searchKBSimple(query, topK = 5) {
  const q = query.toLowerCase();
  return kb
    .map(item => {
      const txt = JSON.stringify(item).toLowerCase();
      const score = txt.includes(q) ? 1 : 0;
      return { item, score };
    })
    .filter(x => x.score > 0)
    .slice(0, topK)
    .map(x => x.item);
}

app.post("/chat", async (req, res) => {
  const { message, history = [] } = req.body;
  if (!message) return res.status(400).json({ error: "message is required" });

  let relevant = [];
  try {
    if (kbVectors.length) {
      relevant = await searchKBEmbeddings(message, 5);
    } else {
      relevant = searchKBSimple(message, 5);
    }
  } catch (e) {
    console.error("Search error:", e);
    relevant = searchKBSimple(message, 5);
  }

  const kbText = relevant.map(a => {
    return [
      `ID: ${a.id}`,
      `Category: ${a.category} – ${a.subcategory || ""}`.trim(),
      `Summary: ${a.summary || ""}`,
      a.steps ? `Steps: ${a.steps.join(" | ")}` : ""
    ].join("\n");
  }).join("\n\n---\n\n");

  const systemPrompt = `
You are the Yorkshire Ambulance Service ePR & Getac support assistant.
Use ONLY the information in the KNOWLEDGE BASE below.
If you are unsure, say so and advise the user to contact the ePR on-call or Service Desk.

KNOWLEDGE BASE:
${kbText || "No relevant KB entries found."}
`;

  try {
    const completion = await client.chat.completions.create({
      model: "gpt-4o-mini",
      messages: [
        { role: "system", content: systemPrompt },
        ...history,
        { role: "user", content: message }
      ]
    });

    const reply = completion.choices[0].message.content;

    res.json({
      reply,
      usedKB: relevant.map(a => a.id)
    });
  } catch (err) {
    console.error(err);
    res.status(500).json({
      reply: "There was an error contacting the AI service. Please try again or contact the Service Desk.",
      usedKB: []
    });
  }
});

const port = process.env.PORT || 3000;
app.listen(port, () => {
  console.log(`YAS ePR chatbot backend listening on port ${port}`);
});
