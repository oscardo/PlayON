import { GoogleGenAI, Type } from "@google/genai";

const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY || "" });

export async function analyzeAudio(audioBase64: string, mimeType: string, language: 'es' | 'en') {
  const model = "gemini-1.5-flash-latest";
  
  const prompt = language === 'es' 
    ? `Analiza este audio de una reunión. 
       1. Genera una transcripción literal identificando a los diferentes hablantes (ej. Hablante 1, Hablante 2).
       2. Genera un resumen ejecutivo altamente profesional, concreto y serio. El resumen debe extraer fechas clave, datos de interés, nombres de servidores, aplicaciones, equipos mencionados y acuerdos.
       Responde ÚNICAMENTE en formato JSON con esta estructura: 
       { "transcription": "texto...", "summary": "texto..." }`
    : `Analyze this meeting audio.
       1. Generate a literal transcription identifying different speakers (e.g., Speaker 1, Speaker 2).
       2. Generate a highly professional, concise, and serious executive summary. Extract key dates, points of interest, server names, applications, equipment mentioned, and agreements.
       Respond ONLY in JSON format with this structure:
       { "transcription": "text...", "summary": "text..." }`;

  try {
    const response = await ai.models.generateContent({
      model: model,
      contents: [
        {
          role: "user",
          parts: [
            { text: prompt },
            { inlineData: { data: audioBase64, mimeType } }
          ]
        }
      ],
      config: {
        responseMimeType: "application/json",
        responseSchema: {
          type: Type.OBJECT,
          properties: {
            transcription: { type: Type.STRING },
            summary: { type: Type.STRING }
          },
          required: ["transcription", "summary"]
        }
      }
    });

    return JSON.parse(response.text || "{}");
  } catch (error) {
    console.error("Error analyzing audio:", error);
    throw error;
  }
}
