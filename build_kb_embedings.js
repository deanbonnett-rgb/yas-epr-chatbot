import fs from "fs";
import OpenAI from "openai";

const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

async function main() {
  const kb = JSON.parse(fs.readFileSync("yas_epr_kb_full.json", "utf8"));
  const out = [];

  for (const item of kb) {
    const text = [
      item.id,
      item.category,
      item.subcategory || "",
      item.summary || "",
      (item.triggers || []).join(" "),
      (item.steps || []).join(" ")
    ].join("\n");

    const resp = await client.embeddings.create({
      model: "text-embedding-3-large",
      input: text
    });

    out.push({
      id: item.id,
      embedding: resp.data[0].embedding
    });

    console.log("Embedded", item.id);
  }

  fs.writeFileSync("yas_epr_kb_vectors.json", JSON.stringify(out, null, 2));
  console.log("Done, wrote yas_epr_kb_vectors.json");
}

main().catch(console.error);
